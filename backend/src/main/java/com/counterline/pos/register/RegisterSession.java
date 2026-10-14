package com.counterline.pos.register;

import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.register.RegisterMovement.Direction;
import com.counterline.pos.staff.Staff;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One opening of an outlet's register ("Yes, we're OPEN") until it is closed. At most one per outlet is open. */
@Entity
@Table(name = "register_session")
public class RegisterSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    private Long outletId;

    private Instant openedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "opened_by")
    private Staff openedBy;

    private Instant closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "closed_by")
    private Staff closedBy;

    private String closingNote;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt, id")
    private List<RegisterMovement> movements = new ArrayList<>();

    protected RegisterSession() {
    }

    RegisterSession(Long outletId, Staff openedBy, Instant openedAt) {
        this.outletId = outletId;
        this.openedBy = openedBy;
        this.openedAt = openedAt;
    }

    public Long getOutletId() {
        return outletId;
    }

    void recordMovement(Direction direction, BigDecimal amount, String reason, Staff staff, Instant at) {
        requireOpen();
        movements.add(new RegisterMovement(this, direction, amount, reason, staff, at));
    }

    void close(Staff staff, String note, Instant at) {
        requireOpen();
        this.closedBy = staff;
        this.closingNote = note;
        this.closedAt = at;
    }

    boolean isOpen() {
        return closedAt == null;
    }

    private void requireOpen() {
        if (!isOpen()) {
            throw new BusinessRuleException("The register is already closed");
        }
    }

    BigDecimal total(Direction direction) {
        return movements.stream()
                .filter(movement -> movement.getDirection() == direction)
                .map(RegisterMovement::getAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    public Long getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Staff getOpenedBy() {
        return openedBy;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Staff getClosedBy() {
        return closedBy;
    }

    public String getClosingNote() {
        return closingNote;
    }

    public List<RegisterMovement> getMovements() {
        return Collections.unmodifiableList(movements);
    }
}
