package com.counterline.pos.outlet;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A shop of the chain. Each POS terminal is bound to one; sales, registers and reports are per outlet. */
@Entity
@Table(name = "outlet")
public class Outlet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;

    private String name;

    private String address;

    private boolean active;

    protected Outlet() {
    }

    public Outlet(String code, String name, String address) {
        this.code = code;
        this.name = name;
        this.address = address;
        this.active = true;
    }

    /** The code is an operational identifier and is deliberately not changeable. */
    void edit(String name, String address) {
        this.name = name;
        this.address = address;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public boolean isActive() {
        return active;
    }
}
