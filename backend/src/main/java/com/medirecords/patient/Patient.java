package com.medirecords.patient;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "patient")
public class Patient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Gender gender;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(nullable = false, length = 100)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "blood_type", length = 12)
    private BloodType bloodType;

    /** Optimistic locking: concurrent edits of the same patient cannot silently overwrite each other. */
    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Patient() {
        // for JPA
    }

    /** Creates a patient from already validated and normalized values (see {@link PatientMapper}). */
    Patient(PatientDetails details) {
        updateDetails(details);
    }

    void updateDetails(PatientDetails details) {
        this.firstName = details.firstName();
        this.lastName = details.lastName();
        this.dateOfBirth = details.dateOfBirth();
        this.gender = details.gender();
        this.email = details.email();
        this.phone = details.phone();
        this.city = details.city();
        this.bloodType = details.bloodType();
    }

    /** Snapshot of the editable fields, e.g. to audit what an update changed. */
    PatientDetails details() {
        return new PatientDetails(firstName, lastName, dateOfBirth, gender, email, phone, city, bloodType);
    }

    /** "Jane Doe", for audit summaries. */
    String displayName() {
        return firstName + " " + lastName;
    }

    public Long getId() {
        return id;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public Gender getGender() {
        return gender;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getCity() {
        return city;
    }

    public BloodType getBloodType() {
        return bloodType;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
