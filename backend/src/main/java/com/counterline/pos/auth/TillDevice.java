package com.counterline.pos.auth;

import com.counterline.pos.staff.Staff;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An iPad set up as a till at one outlet. It keeps a secret key (only its hash is stored here) and
 * sends it whenever staff log in; once reset from the dashboard, the key no longer works and the
 * iPad has to be set up again by a manager.
 */
@Entity
@Table(name = "till_device")
public class TillDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long outletId;

    private String label;

    private String keyHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "set_up_by")
    private Staff setUpBy;

    private Instant setUpAt;

    private Instant lastLoginAt;

    private Instant resetAt;

    private String resetBy;

    protected TillDevice() {
    }

    TillDevice(Long outletId, String label, String keyHash, Staff setUpBy, Instant setUpAt) {
        this.outletId = outletId;
        this.label = label;
        this.keyHash = keyHash;
        this.setUpBy = setUpBy;
        this.setUpAt = setUpAt;
    }

    void recordLogin(Instant at) {
        this.lastLoginAt = at;
    }

    /** {@code by}: who reset it from the dashboard. */
    void reset(String by, Instant at) {
        this.resetBy = by;
        this.resetAt = at;
    }

    public boolean isActive() {
        return resetAt == null;
    }

    public Long getId() {
        return id;
    }

    public Long getOutletId() {
        return outletId;
    }

    public String getLabel() {
        return label;
    }

    public Staff getSetUpBy() {
        return setUpBy;
    }

    public Instant getSetUpAt() {
        return setUpAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
