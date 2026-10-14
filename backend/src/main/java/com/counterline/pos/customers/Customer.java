package com.counterline.pos.customers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "customer")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    private String referenceId;
    private String firstName;
    private String lastName;

    /** Computed by PostgreSQL (generated column) and indexed for name search. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(insertable = false, updatable = false)
    private String fullName;

    private String companyName;
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private String email;
    private String phone;
    private boolean marketingEmail;
    private boolean marketingSms;
    private boolean marketingCall;
    private String streetAddress;
    private String aptSuite;
    private String city;
    private String postalCode;
    private String country;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    protected Customer() {
    }

    public Customer(CustomerDetails details, Instant now) {
        apply(details);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(CustomerDetails details, Instant now) {
        apply(details);
        this.updatedAt = now;
    }

    /** Soft delete: the row stays so past orders still show who they were sold to. */
    public void delete(Instant now) {
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    private void apply(CustomerDetails details) {
        this.referenceId = details.referenceId();
        this.firstName = details.firstName();
        this.lastName = details.lastName();
        this.companyName = details.companyName();
        this.dateOfBirth = details.dateOfBirth();
        this.gender = details.gender();
        this.email = details.email();
        this.phone = details.phone();
        this.marketingEmail = details.marketingEmail();
        this.marketingSms = details.marketingSms();
        this.marketingCall = details.marketingCall();
        this.streetAddress = details.streetAddress();
        this.aptSuite = details.aptSuite();
        this.city = details.city();
        this.postalCode = details.postalCode();
        this.country = details.country();
        this.notes = details.notes();
    }

    /** Normalised, validated customer fields (see CustomerService). */
    public record CustomerDetails(String referenceId, String firstName, String lastName, String companyName,
                                  LocalDate dateOfBirth, Gender gender, String email, String phone,
                                  boolean marketingEmail, boolean marketingSms, boolean marketingCall,
                                  String streetAddress, String aptSuite, String city, String postalCode,
                                  String country, String notes) {
    }

    public Long getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getFullName() {
        return fullName;
    }

    public String getCompanyName() {
        return companyName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public Gender getGender() {
        return gender;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public boolean isMarketingEmail() {
        return marketingEmail;
    }

    public boolean isMarketingSms() {
        return marketingSms;
    }

    public boolean isMarketingCall() {
        return marketingCall;
    }

    public String getStreetAddress() {
        return streetAddress;
    }

    public String getAptSuite() {
        return aptSuite;
    }

    public String getCity() {
        return city;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountry() {
        return country;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
