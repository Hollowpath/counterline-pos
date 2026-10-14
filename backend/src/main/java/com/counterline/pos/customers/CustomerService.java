package com.counterline.pos.customers;

import com.counterline.pos.common.BadRequestException;
import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.common.ConflictException;
import com.counterline.pos.common.PageResponse;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.customers.Customer.CustomerDetails;
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
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

@Service
@Transactional
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository customerRepository;
    private final OutletProperties outlet;
    private final Clock clock;

    CustomerService(CustomerRepository customerRepository, OutletProperties outlet, Clock clock) {
        this.customerRepository = customerRepository;
        this.outlet = outlet;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerSummary> search(SearchField field, String query, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("fullName", "id"));
        String term = query == null ? "" : query.strip();
        if (term.isEmpty()) {
            return PageResponse.from(customerRepository.findByDeletedAtIsNull(pageable), CustomerSummary::from);
        }
        Page<Customer> customers = switch (field) {
            case NAME -> customerRepository.searchByName(contains(term), pageable);
            case EMAIL -> customerRepository.searchByEmail(contains(term.toLowerCase(Locale.ROOT)), pageable);
            case PHONE -> {
                String digits = PhoneNumbers.searchDigits(term, outlet.defaultPhoneCountryCode());
                yield digits.isEmpty() ? Page.empty(pageable) : customerRepository.searchByPhone(contains(digits), pageable);
            }
            case REFERENCE -> customerRepository.findByReferenceIdAndDeletedAtIsNull(term, pageable);
        };
        return PageResponse.from(customers, CustomerSummary::from);
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(Long id) {
        return CustomerResponse.from(findActive(id));
    }

    /** For attaching a customer to a sale: the customer must exist and not be deleted. */
    @Transactional(readOnly = true)
    public Customer getForSale(Long id) {
        return customerRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessRuleException("Customer " + id + " is not available"));
    }

    /** The member with this phone number, however it was typed ("011…", "+6011…"); empty if none or not a number. */
    @Transactional(readOnly = true)
    public Optional<Customer> findByPhone(String phone) {
        return PhoneNumbers.normalize(phone == null ? "" : phone.strip(), outlet.defaultPhoneCountryCode())
                .flatMap(customerRepository::findByPhoneAndDeletedAtIsNull);
    }

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

    public void delete(Long id) {
        findActive(id).delete(clock.instant());
        log.info("Customer {} deleted", id);
    }

    private Customer findActive(Long id) {
        return customerRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer " + id + " not found"));
    }

    private CustomerDetails normalize(CustomerRequest r) {
        String phone = blankToNull(r.phone());
        if (phone != null) {
            phone = PhoneNumbers.normalize(phone, outlet.defaultPhoneCountryCode())
                    .orElseThrow(() -> new BadRequestException("Enter a valid phone number"));
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

    /** Friendly 409s for the common case; the partial unique indexes still guard against races. */
    private void requireUnique(CustomerDetails details, Long selfId) {
        clash(details.phone(), customerRepository::findByPhoneAndDeletedAtIsNull, selfId, "phone number");
        clash(details.email(), customerRepository::findByEmailAndDeletedAtIsNull, selfId, "email");
        clash(details.referenceId(), customerRepository::findByReferenceIdAndDeletedAtIsNull, selfId, "reference ID");
    }

    private static void clash(String value, Function<String, Optional<Customer>> lookup, Long selfId, String label) {
        if (value == null) {
            return;
        }
        lookup.apply(value)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new ConflictException("Customer " + existing.getFullName() + " already has this " + label);
                });
    }

    private Customer saveChecked(Customer customer) {
        try {
            return customerRepository.saveAndFlush(customer);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Another customer was just saved with the same phone, email or reference ID");
        }
    }

    /** "%term%" with LIKE wildcards in the user's input escaped ('!' is the escape character). */
    private static String contains(String term) {
        return "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
