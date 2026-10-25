package com.example.cx.core.tickets;

public enum Priority {
    LOW, MEDIUM, HIGH, URGENT;
    public int rank() { return ordinal(); }
}
