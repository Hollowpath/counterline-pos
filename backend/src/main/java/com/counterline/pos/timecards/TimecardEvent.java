package com.counterline.pos.timecards;

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

import java.time.Instant;

@Entity
@Table(name = "timecard_event")
public class TimecardEvent {

    public enum Type {
        IN,
        OUT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id")
    private Staff staff;

    @Enumerated(EnumType.STRING)
    private Type type;

    private Instant occurredAt;

    /** Where they clocked: always their own outlet. */
    private Long outletId;

    protected TimecardEvent() {
    }

    TimecardEvent(Long outletId, Staff staff, Type type, Instant occurredAt) {
        this.outletId = outletId;
        this.staff = staff;
        this.type = type;
        this.occurredAt = occurredAt;
    }

    public Type getType() {
        return type;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
