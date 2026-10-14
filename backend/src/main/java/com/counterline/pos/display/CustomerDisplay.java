package com.counterline.pos.display;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A customer-facing iPad paired with one POS terminal. The POS publishes its cart here; a customer
 * who logs in or signs up on the display waits here until the POS adds them to its sale.
 * {@code version} goes up with every change the display should see.
 */
@Entity
@Table(name = "customer_display")
class CustomerDisplay {

    enum MemberResult {
        CONFIRMED,
        /** No longer given (members are added without the cashier); kept for rows written before. */
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long outletId;

    private String accessCode;

    private Long createdBy;

    private Instant createdAt;

    private Instant connectedAt;

    private Instant displaySeenAt;

    private Instant revokedAt;

    private String cart;

    private Long pendingCustomerId;

    private boolean pendingNewMember;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private MemberResult memberResult;

    private Long resultCustomerId;

    private long version;

    protected CustomerDisplay() {
    }

    CustomerDisplay(Long outletId, String accessCode, Long createdBy, Instant createdAt) {
        this.outletId = outletId;
        this.accessCode = accessCode;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    void connect(Instant at) {
        connectedAt = at;
        displaySeenAt = at;
        changed();
    }

    void revoke(Instant at) {
        revokedAt = at;
    }

    void publishCart(String cartJson, boolean idle) {
        cart = cartJson;
        if (idle) {
            // The sale ended: the previous customer's login no longer applies.
            pendingCustomerId = null;
            pendingNewMember = false;
            memberResult = null;
            resultCustomerId = null;
        }
        changed();
    }

    /** A member logged in or signed up on the display; replaces anyone still waiting. */
    void memberWaiting(Long customerId, boolean newMember) {
        pendingCustomerId = customerId;
        pendingNewMember = newMember;
        memberResult = null;
        resultCustomerId = null;
        changed();
    }

    void cancelWaiting() {
        pendingCustomerId = null;
        pendingNewMember = false;
        changed();
    }

    /** The POS's answer for the member waiting. */
    void answer(MemberResult result) {
        resultCustomerId = pendingCustomerId;
        memberResult = result;
        pendingCustomerId = null;
        pendingNewMember = false;
        changed();
    }

    private void changed() {
        version++;
    }

    Long getId() {
        return id;
    }

    Long getOutletId() {
        return outletId;
    }

    String getAccessCode() {
        return accessCode;
    }

    Instant getConnectedAt() {
        return connectedAt;
    }

    Instant getDisplaySeenAt() {
        return displaySeenAt;
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    String getCart() {
        return cart;
    }

    Long getPendingCustomerId() {
        return pendingCustomerId;
    }

    boolean isPendingNewMember() {
        return pendingNewMember;
    }

    MemberResult getMemberResult() {
        return memberResult;
    }

    Long getResultCustomerId() {
        return resultCustomerId;
    }

    long getVersion() {
        return version;
    }
}
