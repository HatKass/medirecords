package com.medirecords.importjob;

import com.medirecords.patient.BloodType;
import com.medirecords.patient.Gender;
import com.medirecords.patient.PatientDetails;
import com.medirecords.patient.PatientDtos.PatientRequest;
import com.medirecords.patient.PatientMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.lang.annotation.Annotation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.batch.item.ItemProcessor;

/**
 * Validates and cleans one row. The row is converted into the same {@link PatientRequest} the REST
 * API accepts and checked with the same Bean Validation rules, then normalized by the same mapper,
 * so an imported patient is indistinguishable from one created in the UI.
 *
 * <p>Duplicate emails <em>within the file</em> are caught here (first occurrence wins); duplicates of
 * patients already in the database are caught by {@link ImportChunkWriter} with one query per chunk.
 * Holds per-job state, so it is step-scoped (one instance per job execution).
 */
class ImportRowProcessor implements ItemProcessor<CsvRow, ImportRow> {

    /** Oldest plausible birth date; anything earlier is a data-entry error (e.g. "1880" for "1980"). */
    static final LocalDate EARLIEST_BIRTH_DATE = LocalDate.of(1900, 1, 1);

    private static final Map<String, String> LABELS = Map.of(
            "firstName", "First name", "lastName", "Last name", "dateOfBirth", "Date of birth",
            "gender", "Gender", "email", "Email", "phone", "Phone", "city", "City", "bloodType", "Blood type");

    /** Report problems in column order, not in Bean Validation's random order. */
    private static final List<String> FIELD_ORDER = List.of(
            "firstName", "lastName", "dateOfBirth", "gender", "email", "phone", "city", "bloodType");

    private final Validator validator;
    private final PatientMapper mapper;
    private final Map<String, Integer> firstRowByEmail = new HashMap<>();

    ImportRowProcessor(Validator validator, PatientMapper mapper) {
        this.validator = validator;
        this.mapper = mapper;
    }

    @Override
    public ImportRow process(CsvRow row) {
        if (row.isBlank()) {
            return null; // filtered: empty lines are not rows
        }
        List<String> problems = new ArrayList<>();
        LocalDate dateOfBirth = parse(row, CsvHeader.DATE_OF_BIRTH, CsvValues::date, problems,
                "is not a valid date (use e.g. 1980-12-31 or 31/12/1980)");
        Gender gender = parse(row, CsvHeader.GENDER, CsvValues::gender, problems, "is not recognized (use F, M or O)");
        BloodType bloodType = parse(row, CsvHeader.BLOOD_TYPE, CsvValues::bloodType, problems,
                "is not a valid blood type (e.g. A+, O-)");
        if (dateOfBirth != null && dateOfBirth.isBefore(EARLIEST_BIRTH_DATE)) {
            problems.add("Date of birth " + dateOfBirth + " is before " + EARLIEST_BIRTH_DATE.getYear());
        }

        PatientRequest request = new PatientRequest(row.get(CsvHeader.FIRST_NAME), row.get(CsvHeader.LAST_NAME),
                dateOfBirth, gender, row.get(CsvHeader.EMAIL), row.get(CsvHeader.PHONE), row.get(CsvHeader.CITY),
                bloodType, null);
        validator.validate(request).stream()
                .filter(violation -> !alreadyReported(violation, problems))
                .sorted(Comparator.comparingInt(violation -> FIELD_ORDER.indexOf(field(violation))))
                .map(ImportRowProcessor::describe)
                .forEach(problems::add);

        if (!problems.isEmpty()) {
            return new ImportRow.Invalid(row.rowNumber(), row.rawLine(), String.join("; ", problems));
        }
        PatientDetails patient = mapper.toDetails(request);
        Integer firstRow = firstRowByEmail.putIfAbsent(patient.email(), row.rowNumber());
        if (firstRow != null) {
            return new ImportRow.Invalid(row.rowNumber(), row.rawLine(),
                    "Duplicate email " + patient.email() + " (already used on row " + firstRow + ")");
        }
        return new ImportRow.Valid(row.rowNumber(), row.rawLine(), patient);
    }

    /**
     * Parses an optional-format column. Blank stays null (Bean Validation then reports "is missing" if
     * the field is required); unparseable text is reported here with the offending value.
     */
    private static <T> T parse(CsvRow row, String column, Function<String, Optional<T>> parser,
            List<String> problems, String complaint) {
        String text = row.get(column);
        if (text == null || text.isBlank()) {
            return null;
        }
        Optional<T> value = parser.apply(text);
        if (value.isEmpty()) {
            problems.add(LABELS.get(column) + " '" + text.strip() + "' " + complaint);
        }
        return value.orElse(null);
    }

    /** A field we could not parse is null now, so @NotNull would report it a second time as "missing". */
    private static boolean alreadyReported(ConstraintViolation<?> violation, List<String> problems) {
        String label = LABELS.get(field(violation));
        return isMissingCheck(violation.getConstraintDescriptor().getAnnotation())
                && problems.stream().anyMatch(problem -> problem.startsWith(label + " "));
    }

    private static String describe(ConstraintViolation<?> violation) {
        String label = LABELS.getOrDefault(field(violation), field(violation));
        if (isMissingCheck(violation.getConstraintDescriptor().getAnnotation())) {
            return label + " is missing";
        }
        Object value = violation.getInvalidValue();
        return value == null ? label + " " + violation.getMessage()
                : label + " '" + value + "' " + violation.getMessage();
    }

    private static boolean isMissingCheck(Annotation annotation) {
        return annotation instanceof NotBlank || annotation instanceof NotNull;
    }

    private static String field(ConstraintViolation<?> violation) {
        return violation.getPropertyPath().toString();
    }
}
