package com.medirecords.patient;

/**
 * ABO/Rh blood groups. The API uses these URL-safe names ({@code A_POSITIVE}) rather than labels
 * like {@code A+}, because a raw "+" in a query string decodes to a space. Clients show {@link #label()}.
 */
public enum BloodType {
    A_POSITIVE("A+"),
    A_NEGATIVE("A-"),
    B_POSITIVE("B+"),
    B_NEGATIVE("B-"),
    AB_POSITIVE("AB+"),
    AB_NEGATIVE("AB-"),
    O_POSITIVE("O+"),
    O_NEGATIVE("O-");

    private final String label;

    BloodType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
