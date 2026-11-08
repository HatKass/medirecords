package com.medirecords.patient;

import java.time.LocalDate;

/**
 * The editable, normalized fields of a patient. Both the REST API and the CSV import
 * (Phase 4) produce this, so the entity has a single way of being filled.
 */
public record PatientDetails(
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        Gender gender,
        String email,
        String phone,
        String city,
        BloodType bloodType) {}
