package com.medirecords.importjob;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** A rejected CSV row. Written in bulk with JDBC by {@link ImportProgressRecorder}; read here. */
@Entity
@Immutable
@Table(name = "patient_import_error")
public class ImportRowError {

    @Id
    private Long id;

    @Column(name = "import_id", nullable = false)
    private long importId;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "raw_line", nullable = false)
    private String rawLine;

    protected ImportRowError() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public long getImportId() {
        return importId;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public String getReason() {
        return reason;
    }

    public String getRawLine() {
        return rawLine;
    }
}
