package com.medirecords.importjob;

import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores rejected rows and bumps the import's counters. It must run inside the chunk transaction
 * ({@link Propagation#MANDATORY}), so counters, errors and inserted patients commit or roll back together
 * and the progress the UI polls is always consistent with the data.
 */
@Component
class ImportProgressRecorder {

    private static final String INSERT_ERROR = """
            INSERT INTO patient_import_error (import_id, row_number, reason, raw_line)
            VALUES (:importId, :rowNumber, :reason, :rawLine)
            """;
    private static final int MAX_REASON = 1000;

    private final NamedParameterJdbcTemplate jdbc;
    private final PatientImportRepository imports;

    ImportProgressRecorder(NamedParameterJdbcTemplate jdbc, PatientImportRepository imports) {
        this.jdbc = jdbc;
        this.imports = imports;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long importId, int validRows, List<ImportRow.Invalid> invalidRows) {
        if (!invalidRows.isEmpty()) {
            SqlParameterSource[] batch = invalidRows.stream()
                    .map(row -> new MapSqlParameterSource()
                            .addValue("importId", importId)
                            .addValue("rowNumber", row.rowNumber())
                            .addValue("reason", truncate(row.reason()))
                            .addValue("rawLine", row.rawLine()))
                    .toArray(SqlParameterSource[]::new);
            jdbc.batchUpdate(INSERT_ERROR, batch);
        }
        if (validRows > 0 || !invalidRows.isEmpty()) {
            imports.incrementCounts(importId, validRows, invalidRows.size());
        }
    }

    private static String truncate(String reason) {
        return reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON - 1) + "…" : reason;
    }
}
