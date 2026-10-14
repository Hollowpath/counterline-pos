package com.counterline.pos.register;

import com.counterline.pos.staff.Staff;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** "Pay In / Out": money put into or taken out of the register outside of sales. */
@Entity
@Table(name = "register_movement")
public class RegisterMovement {

    public enum Direction {
        IN,
        OUT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private RegisterSession session;

    @Enumerated(EnumType.STRING)
    private Direction direction;

    private BigDecimal amount;

    private String reason;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id")
    private Staff staff;

    private Instant createdAt;

    protected RegisterMovement() {
    }

    RegisterMovement(RegisterSession session, Direction direction, BigDecimal amount, String reason,
                     Staff staff, Instant createdAt) {
        this.session = session;
        this.direction = direction;
        this.amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        this.reason = reason;
        this.staff = staff;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Direction getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public Staff getStaff() {
        return staff;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
