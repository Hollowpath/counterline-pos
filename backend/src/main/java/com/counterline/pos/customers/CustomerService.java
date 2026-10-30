package com.counterline.pos.customers;

import com.counterline.pos.common.BadRequestException;
import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.common.ConflictException;
import com.counterline.pos.common.PageResponse;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.customers.Customer.CustomerDetails;
import com.counterline.pos.customers.CustomerConflictException.Field;
import com.counterline.pos.customers.CustomerDtos.CustomerRequest;
import com.counterline.pos.customers.CustomerDtos.CustomerResponse;
import com.counterline.pos.customers.CustomerDtos.CustomerSummary;
import com.counterline.pos.customers.CustomerDtos.SearchField;
import com.counterline.pos.outlet.OutletProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Members are shared by every outlet (a member can buy at any of them). Anyone at a till may add and look
 * up members; changing or deactivating one is a manager's or head office's (the controller checks). A member is
 * never removed: deactivated, they keep their identity, points and orders, and no second member can take their phone
 * number, email or reference ID.
 */
@Service
@Transactional
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    /** A till's search needs this much to go on: it finds someone, it does not list everyone. */
    static final int MIN_TERM = 3;
    static final int MIN_PHONE_DIGITS = 4;
    static final String TOO_SHORT = "Type at least " + MIN_TERM + " characters (" + MIN_PHONE_DIGITS + " digits for a phone number) to search";

    private final CustomerRepository customerRepository;
    private final OutletProperties outlet;
    private final Clock clock;

    CustomerService(CustomerRepository customerRepository, OutletProperties outlet, Clock clock) {
        this.customerRepository = customerRepository;
        this.outlet = outlet;
        this.clock = clock;
    }

    /**
     * @param dashboard a manager's or head office's dashboard session: it may list everyone, deactivated members
     *                  included. A till's search needs a meaningful query and finds active members only.
     * @throws BadRequestException (400) for a till's query that is empty or too short
     */
    @Transactional(readOnly = true)
    public PageResponse<CustomerSummary> search(SearchField field, String query, int page, int size, boolean dashboard) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("fullName", "id"));
        String term = query == null ? "" : query.strip();
        if (!dashboard) {
            requireMeaningful(field, term);
        }
        if (term.isEmpty()) {
            return PageResponse.from(customerRepository.findAll(pageable), CustomerSummary::from);
        }
        Page<Customer> customers = switch (field) {
            case NAME -> customerRepository.searchByName(contains(term), dashboard, pageable);
            case EMAIL -> customerRepository.searchByEmail(contains(term.toLowerCase(Locale.ROOT)), dashboard, pageable);
            case PHONE -> {
                String digits = PhoneNumbers.searchDigits(term, outlet.defaultPhoneCountryCode());
                yield digits.isEmpty() ? Page.empty(pageable) : customerRepository.searchByPhone(contains(digits), dashboard, pageable);
            }
            case REFERENCE -> customerRepository.searchByReferenceId(term, dashboard, pageable);
        };
        return PageResponse.from(customers, CustomerSummary::from);
    }

    private static void requireMeaningful(SearchField field, String term) {
        boolean enough = switch (field) {
            case NAME, EMAIL -> term.length() >= MIN_TERM;
            case PHONE -> term.chars().filter(Character::isDigit).count() >= MIN_PHONE_DIGITS;
            case REFERENCE -> !term.isEmpty();
        };
        if (!enough) {
            throw new BadRequestException(TOO_SHORT);
        }
    }

    /** An active member; 404 for an unknown or deactivated one. */
    @Transactional(readOnly = true)
    public CustomerResponse get(Long id) {
        return CustomerResponse.from(findActive(id));
    }

    /** Any member, deactivated included (the dashboard, to show and reactivate them). */
    @Transactional(readOnly = true)
    public CustomerResponse getIncludingInactive(Long id) {
        return CustomerResponse.from(find(id));
    }

    /** For attaching a customer to a sale: the customer must exist and be active. */
    @Transactional(readOnly = true)
    public Customer getForSale(Long id) {
        return customerRepository.findByIdAndDeactivatedAtIsNull(id)
                .orElseThrow(() -> new BusinessRuleException("Customer " + id + " is not available"));
    }

    /**
     * For an order being edited: the member it already has is kept even if they were deactivated since
     * ({@code keptId}); anyone else must be active.
     */
    @Transactional(readOnly = true)
    public Customer getForOrder(Long id, Long keptId) {
        if (id.equals(keptId)) {
            return customerRepository.findById(id).orElseThrow(() -> new BusinessRuleException("Customer " + id + " is not available"));
        }
        return getForSale(id);
    }

    /** The active member with this id, if there is one (a member waiting on a display may have been deactivated meanwhile). */
    @Transactional(readOnly = true)
    public Optional<Customer> findActiveMember(Long id) {
        return id == null ? Optional.empty() : customerRepository.findByIdAndDeactivatedAtIsNull(id);
    }

    /** The active member with this phone number, however it was typed ("011…", "+6011…"); empty if none or not a number. */
    @Transactional(readOnly = true)
    public Optional<Customer> findByPhone(String phone) {
        return PhoneNumbers.normalize(phone == null ? "" : phone.strip(), outlet.defaultPhoneCountryCode())
                .flatMap(customerRepository::findByPhoneAndDeactivatedAtIsNull);
    }

    /** @throws CustomerConflictException (409) if another member, deactivated or not, has the phone, email or reference ID */
    public CustomerResponse create(CustomerRequest request) {
        CustomerDetails details = normalize(request);
        requireUnique(details, null);
        Customer customer = saveChecked(new Customer(details, clock.instant()));
        log.info("Customer {} created", customer.getId());
        return CustomerResponse.from(customer);
    }

    public CustomerResponse update(Long id, CustomerRequest request) {
        Customer customer = findActive(id);
        if (request.version() == null) {
            throw new BadRequestException("version is required when editing a customer");
        }
        if (request.version() != customer.getVersion()) {
            // Someone else saved this customer after the cashier opened the form.
            throw new ObjectOptimisticLockingFailureException(Customer.class, id);
        }
        CustomerDetails details = normalize(request);
        requireUnique(details, id);
        customer.update(details, clock.instant());
        return CustomerResponse.from(saveChecked(customer));
    }

    /** Idempotent. The member keeps everything; they just cannot be used on a sale until reactivated. */
    public CustomerResponse deactivate(Long id) {
        Customer customer = find(id);
        if (customer.isActive()) {
            customer.deactivate(clock.instant());
            log.info("Customer {} deactivated", id);
        }
        return CustomerResponse.from(customer);
    }

    /** Idempotent. */
    public CustomerResponse reactivate(Long id) {
        Customer customer = find(id);
        if (!customer.isActive()) {
            customer.reactivate(clock.instant());
            log.info("Customer {} reactivated", id);
        }
        return CustomerResponse.from(customer);
    }

    private Customer findActive(Long id) {
        return customerRepository.findByIdAndDeactivatedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer " + id + " not found"));
    }

    private Customer find(Long id) {
        return customerRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer " + id + " not found"));
    }

    private CustomerDetails normalize(CustomerRequest r) {
        String phone = blankToNull(r.phone());
        if (phone != null) {
            phone = PhoneNumbers.normalize(phone, outlet.defaultPhoneCountryCode())
                    .orElseThrow(() -> new BadRequestException("Enter a valid phone number"));
        }
        // Today is the outlet's; a birth date from today back is fine.
        if (r.dateOfBirth() != null && r.dateOfBirth().isAfter(LocalDate.ofInstant(clock.instant(), outlet.timeZone()))) {
            throw new BadRequestException("Date of birth can't be in the future");
        }
        String email = blankToNull(r.email());
        return new CustomerDetails(blankToNull(r.referenceId()), r.firstName().strip(), blankToNull(r.lastName()),
                blankToNull(r.companyName()), r.dateOfBirth(), r.gender(),
                email == null ? null : email.toLowerCase(Locale.ROOT), phone,
                Boolean.TRUE.equals(r.marketingEmail()), Boolean.TRUE.equals(r.marketingSms()),
                Boolean.TRUE.equals(r.marketingCall()),
                blankToNull(r.streetAddress()), blankToNull(r.aptSuite()), blankToNull(r.city()),
                blankToNull(r.postalCode()), blankToNull(r.country()), blankToNull(r.notes()));
    }

    /** Friendly 409s for the common case; the unique indexes (all members, deactivated too) still guard against races. */
    private void requireUnique(CustomerDetails details, Long selfId) {
        clash(details.phone(), customerRepository::findByPhone, selfId, Field.PHONE, "phone number");
        clash(details.email(), customerRepository::findByEmail, selfId, Field.EMAIL, "email");
        clash(details.referenceId(), customerRepository::findByReferenceId, selfId, Field.REFERENCE, "reference ID");
    }

    private static void clash(String value, Function<String, Optional<Customer>> lookup, Long selfId, Field field, String label) {
        if (value == null) {
            return;
        }
        lookup.apply(value)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw existing.isActive()
                            ? new CustomerConflictException(field, false, "Customer " + existing.getFullName() + " already has this " + label)
                            : new CustomerConflictException(field, true,
                                    "A deactivated member already has this " + label + ". A manager can reactivate them.");
                });
    }

    /** Two requests can pass the checks above together; the index then refuses the second, and the answer says which field. */
    private Customer saveChecked(Customer customer) {
        try {
            return customerRepository.saveAndFlush(customer);
        } catch (DataIntegrityViolationException e) {
            String cause = causes(e);
            if (cause.contains("customer_phone_uq")) {
                throw new CustomerConflictException(Field.PHONE, false, "Another customer was just saved with this phone number");
            }
            if (cause.contains("customer_email_uq")) {
                throw new CustomerConflictException(Field.EMAIL, false, "Another customer was just saved with this email");
            }
            if (cause.contains("customer_reference_uq")) {
                throw new CustomerConflictException(Field.REFERENCE, false, "Another customer was just saved with this reference ID");
            }
            throw new ConflictException("Another customer was just saved with the same phone, email or reference ID");
        }
    }

    private static String causes(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            text.append(cause.getMessage()).append(' ');
        }
        return text.toString();
    }

    /** "%term%" with LIKE wildcards in the user's input escaped ('!' is the escape character). */
    private static String contains(String term) {
        return "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
