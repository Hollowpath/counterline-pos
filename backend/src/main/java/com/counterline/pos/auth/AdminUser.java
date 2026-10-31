package com.counterline.pos.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A head-office account for the back-office dashboard: every outlet, and the catalogue. */
@Entity
@Table(name = "admin_user")
public class AdminUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    private String fullName;

    private String passwordHash;

    private boolean active;

    private Instant createdAt;

    /** Raised whenever how this account authenticates changes (a new password, a deactivation); every token carries it. */
    private int authSessionVersion;

    protected AdminUser() {
    }

    public AdminUser(String email, String fullName, String passwordHash, Instant createdAt) {
        this.email = email;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    public boolean isActive() {
        return active;
    }

    public int getAuthSessionVersion() {
        return authSessionVersion;
    }

    /** A new password (already hashed): every session issued before it ends and cannot be revived. */
    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.authSessionVersion++;
    }

    /** Ends the account's sessions for good, also if it is reactivated later. */
    public void deactivate() {
        this.active = false;
        this.authSessionVersion++;
    }

    public void reactivate() {
        this.active = true;
    }
}
