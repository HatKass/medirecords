package com.medirecords.patient;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Set-based patient writes for the CSV import. Inserting thousands of rows through JPA one entity at a
 * time is slow (IDENTITY ids disable Hibernate's insert batching), so this uses one JDBC batch per chunk.
 * It lives in the patient package so the patient table keeps a single owner. Callers run it inside
 * their own transaction (the Batch chunk transaction).
 */
@Repository
public class PatientBulkRepository {

    private static final String INSERT = """
            INSERT INTO patient (first_name, last_name, date_of_birth, gender, email, phone, city, blood_type)
            VALUES (:firstName, :lastName, :dateOfBirth, :gender, :email, :phone, :city, :bloodType)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PatientBulkRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Which of the given (already lower-cased) emails belong to an existing patient. One query. */
    public Set<String> findExistingEmails(Collection<String> emails) {
        if (emails.isEmpty()) {
            return Set.of();
        }
        List<String> found = jdbc.queryForList("SELECT lower(email) FROM patient WHERE lower(email) IN (:emails)",
                Map.of("emails", emails), String.class);
        return new HashSet<>(found);
    }

    public void insertAll(List<PatientDetails> patients) {
        if (patients.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = patients.stream().map(PatientBulkRepository::parameters)
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT, batch);
    }

    private static SqlParameterSource parameters(PatientDetails patient) {
        return new MapSqlParameterSource()
                .addValue("firstName", patient.firstName())
                .addValue("lastName", patient.lastName())
                .addValue("dateOfBirth", patient.dateOfBirth())
                .addValue("gender", patient.gender().name())
                .addValue("email", patient.email())
                .addValue("phone", patient.phone())
                .addValue("city", patient.city())
                .addValue("bloodType", patient.bloodType() == null ? null : patient.bloodType().name());
    }
}
