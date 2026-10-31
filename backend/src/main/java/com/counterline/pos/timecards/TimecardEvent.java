package com.counterline.pos.timecards;

import com.counterline.pos.auth.Actor;
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

    /** Set on an OUT that a head-office or manager correction made for the staff member. */
    /** The name to show for whoever made the correction, as it was then. */
    private String correctedBy;

    /** ADMIN or MANAGER with {@link #correctedById}; LEGACY (no id) for a correction made before the actor was recorded. */
    private String correctedByType;

    private Long correctedById;

    private String correctedReason;

    private Instant correctedAt;

    protected TimecardEvent() {
    }

    TimecardEvent(Long outletId, Staff staff, Type type, Instant occurredAt) {
        this.outletId = outletId;
        this.staff = staff;
        this.type = type;
        this.occurredAt = occurredAt;
    }

    /** The OUT a correction adds to close an open shift; the IN is left alone. */
    static TimecardEvent correctedOut(Long outletId, Staff staff, Instant occurredAt, Actor actor, String reason,
                                      Instant correctedAt) {
        TimecardEvent event = new TimecardEvent(outletId, staff, Type.OUT, occurredAt);
        event.correctedBy = actor.name();
        event.correctedByType = actor.type().name();
        event.correctedById = actor.id();
        event.correctedReason = reason;
        event.correctedAt = correctedAt;
        return event;
    }

    public Long getId() {
        return id;
    }

    public Long getOutletId() {
        return outletId;
    }

    public Type getType() {
        return type;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
