package com.counterline.pos.orders;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "payment_method")
public class PaymentMethod {

    /** A sale may be paid in cash, by one cashless method, or by one cashless method plus cash. */
    public enum Kind {
        CASH,
        CASHLESS
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private short sortOrder;

    private boolean active;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    protected PaymentMethod() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }

    public Kind getKind() {
        return kind;
    }

    public boolean isCash() {
        return kind == Kind.CASH;
    }
}
