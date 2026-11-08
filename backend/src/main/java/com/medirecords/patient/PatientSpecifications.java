package com.medirecords.patient;

import com.medirecords.patient.PatientDtos.PatientFilter;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Builds the WHERE clause for the patient list from optional filters. */
final class PatientSpecifications {

    private static final char ESCAPE = '\\';

    private PatientSpecifications() {
    }

    static Specification<Patient> matching(PatientFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.name() != null) {
                // Same expression as idx_patient_full_name_trgm, so Postgres can use the trigram index.
                Expression<String> fullName = cb.lower(
                        cb.concat(cb.concat(root.get("firstName"), " "), root.get("lastName")));
                predicates.add(cb.like(fullName, "%" + escapeLike(filter.name().toLowerCase(Locale.ROOT)) + "%", ESCAPE));
            }
            if (filter.city() != null) {
                predicates.add(cb.equal(cb.lower(root.get("city")), filter.city().toLowerCase(Locale.ROOT)));
            }
            if (filter.bloodType() != null) {
                predicates.add(cb.equal(root.get("bloodType"), filter.bloodType()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** User input is matched literally: "%" and "_" are not wildcards. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
