package com.medirecords.patient;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;

/** API shapes for patients. Entities never leave the service layer. */
public final class PatientDtos {

    private PatientDtos() {
    }

    /** Validation group for PUT: on top of the default rules, the client must send the version it edited. */
    public interface OnUpdate {}

    /** Body of POST /api/patients and PUT /api/patients/{id}. */
    public record PatientRequest(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotNull @Past LocalDate dateOfBirth,
            @NotNull Gender gender,
            @NotBlank @Size(max = 254) @Email(regexp = ".+@.+\\..+", message = "must be a valid email address")
            String email,
            @Pattern(regexp = "^$|^\\+?[0-9 ().-]{7,20}$", message = "must be a valid phone number") String phone,
            @NotBlank @Size(max = 100) String city,
            BloodType bloodType,
            /** The version returned by the last read; required on update, ignored on create. */
            @NotNull(groups = OnUpdate.class) Long version) {

        /** Whitespace is cleaned before validation, so " jane@example.com " is accepted, not rejected. */
        public PatientRequest {
            firstName = PatientNormalizer.text(firstName);
            lastName = PatientNormalizer.text(lastName);
            email = PatientNormalizer.text(email);
            phone = PatientNormalizer.text(phone);
            city = PatientNormalizer.text(city);
        }
    }

    public record PatientResponse(
            long id,
            String firstName,
            String lastName,
            LocalDate dateOfBirth,
            Gender gender,
            String email,
            String phone,
            String city,
            BloodType bloodType,
            Instant createdAt,
            Instant updatedAt,
            long version) {}

    /** Optional list filters; null means "do not filter on this". */
    public record PatientFilter(String name, String city, BloodType bloodType) {}
}
