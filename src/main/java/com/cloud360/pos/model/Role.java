package com.cloud360.pos.model;

public enum Role {
    SUPER_ADMIN("Super Admin / Owner"),
    MANAGER("Store Manager"),
    CASHIER("Cashier"),
    ACCOUNTANT("Accountant");

    private final String label;

    Role(String label) { this.label = label; }

    public String getLabel() { return label; }
}