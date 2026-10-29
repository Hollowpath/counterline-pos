package com.counterline.pos.staff;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "staff")
public class Staff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fullName;

    private String pinHmac;

    @Enumerated(EnumType.STRING)
    private StaffRole role;

    private boolean active;

    /** The outlet they work at; they can log in only there. */
    private Long outletId;

    /** A manager's back-office dashboard login (null for staff without one). */
    private String email;

    /** BCrypt; set together with {@code email}. */
    private String passwordHash;

    /** Raised whenever how this person authenticates changes; every token issued to them carries it. */
    private int authSessionVersion;

    protected Staff() {
    }

    public Staff(String fullName, String pinHmac, StaffRole role, Long outletId) {
        this.fullName = fullName;
        this.pinHmac = pinHmac;
        this.role = role;
        this.outletId = outletId;
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public StaffRole getRole() {
        return role;
    }

    public Long getOutletId() {
        return outletId;
    }

    public boolean worksAt(Long outlet) {
        return outletId.equals(outlet);
    }

    public boolean isActive() {
        return active;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    /** Gives a manager a dashboard login ({@code email} lower case, {@code passwordHash} BCrypt). */
    public void setDashboardLogin(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public void deactivate() {
        this.active = false;
    }

    public int getAuthSessionVersion() {
        return authSessionVersion;
    }

    /** Ends every session (till, dashboard, approval) issued so far; none of them can become valid again. */
    public void revokeAuthSessions() {
        this.authSessionVersion++;
    }

    public void reactivate() {
        this.active = true;
    }

    public void edit(String fullName, StaffRole role, Long outletId) {
        this.fullName = fullName;
        this.role = role;
        this.outletId = outletId;
    }

    public void setPinHmac(String pinHmac) {
        this.pinHmac = pinHmac;
    }

    /** Cashiers have no dashboard login. */
    public void clearDashboardLogin() {
        this.email = null;
        this.passwordHash = null;
    }
}
