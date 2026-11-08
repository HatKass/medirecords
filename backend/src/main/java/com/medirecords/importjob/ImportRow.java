package com.medirecords.importjob;

import com.medirecords.patient.PatientDetails;

/**
 * Result of validating one CSV row. Invalid rows are ordinary <em>values</em>, not exceptions: in a
 * messy file they are expected, and throwing would make Spring Batch roll back and re-process the
 * whole chunk for every bad row. The writer routes each kind to its table in one transaction.
 */
sealed interface ImportRow {

    int rowNumber();

    String rawLine();

    record Valid(int rowNumber, String rawLine, PatientDetails patient) implements ImportRow {}

    record Invalid(int rowNumber, String rawLine, String reason) implements ImportRow {}
}
