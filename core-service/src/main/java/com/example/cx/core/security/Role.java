package com.example.cx.core.security;

public enum Role {
    CUSTOMER, AGENT, MANAGER, ADMIN;

    public boolean isStaff() { return this != CUSTOMER; }
}
