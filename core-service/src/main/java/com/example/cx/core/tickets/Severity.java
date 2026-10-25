package com.example.cx.core.tickets;

public enum Severity {
    MINOR, MAJOR, CRITICAL;
    public int rank() { return ordinal(); }
}
