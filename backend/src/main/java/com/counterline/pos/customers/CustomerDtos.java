package com.counterline.pos.customers;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class CustomerDtos {

    private CustomerDtos() {
    }

    /** Which field the Customers search box searches. */
    public enum SearchField {
        REFERENCE,
        NAME,
        PHONE,
        EMAIL
    }

    /**
     * Create or edit a customer. {@code version} is required when editing: it is the version the
     * cashier loaded, so a concurrent edit by another terminal is reported instead of overwritten.
     */
    public record CustomerRequest(
            Long version,
            @Size(max = 40) String referenceId,
            @NotBlank @Size(max = 60) String firstName,
            @NotBlank @Size(max = 60) String lastName,
            @Size(max = 120) String companyName,
            // Not in the future: checked in CustomerService, for a message the form can show.
            LocalDate dateOfBirth,
            Gender gender,
            @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 30) String phone,
            // Nullable so clients may omit them (Jackson 3 rejects missing primitives); null = no consent.
            Boolean marketingEmail,
            Boolean marketingSms,
            Boolean marketingCall,
            @Size(max = 200) String streetAddress,
            @Size(max = 60) String aptSuite,
            @Size(max = 80) String city,
            @Size(max = 20) String postalCode,
            @Size(max = 60) String country,
            @Size(max = 1000) String notes) {
    }

    public record CustomerResponse(
            Long id,
            long version,
            String referenceId,
            String firstName,
            String lastName,
            String fullName,
            String companyName,
            LocalDate dateOfBirth,
            Gender gender,
            String email,
            String phone,
            boolean marketingEmail,
            boolean marketingSms,
            boolean marketingCall,
            String streetAddress,
            String aptSuite,
            String city,
            String postalCode,
            String country,
            String notes,
            Instant createdAt,
            Instant updatedAt,
            /* False once a manager has deactivated the member. */
            boolean active) {

        static CustomerResponse from(Customer c) {
            return new CustomerResponse(c.getId(), c.getVersion(), c.getReferenceId(), c.getFirstName(),
                    c.getLastName(), c.getFullName(), c.getCompanyName(), c.getDateOfBirth(), c.getGender(),
                    c.getEmail(), c.getPhone(), c.isMarketingEmail(), c.isMarketingSms(), c.isMarketingCall(),
                    c.getStreetAddress(), c.getAptSuite(), c.getCity(), c.getPostalCode(), c.getCountry(),
                    c.getNotes(), c.getCreatedAt(), c.getUpdatedAt(), c.isActive());
        }
    }

    /** A row in the customer list or picker. */
    public record CustomerSummary(Long id, String fullName, String phone, String email, String referenceId, boolean active) {

        static CustomerSummary from(Customer c) {
            return new CustomerSummary(c.getId(), c.getFullName(), c.getPhone(), c.getEmail(), c.getReferenceId(), c.isActive());
        }
    }
}
