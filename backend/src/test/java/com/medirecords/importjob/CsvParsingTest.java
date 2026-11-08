package com.medirecords.importjob;

import static org.assertj.core.api.Assertions.assertThat;

import com.medirecords.patient.BloodType;
import com.medirecords.patient.Gender;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Plain unit tests for the lenient value parsing and header matching (no Spring context). */
class CsvParsingTest {

    @ParameterizedTest
    @ValueSource(strings = {"1980-12-31", "1980/12/31", "19801231", "31/12/1980", "31.12.1980", "31-12-1980",
            "31 Dec 1980", "31 december 1980", "Dec 31, 1980", "December 31, 1980", "1980-12-31T00:00:00", " 31/12/1980 "})
    void acceptsCommonDateFormats(String text) {
        assertThat(CsvValues.date(text)).contains(LocalDate.of(1980, 12, 31));
    }

    @Test
    void readsSlashDatesDayFirst() {
        assertThat(CsvValues.date("4/7/1990")).contains(LocalDate.of(1990, 7, 4));
    }

    @ParameterizedTest
    @ValueSource(strings = {"31/02/1990", "29/02/2023", "12/31/1985", "1990-13-01", "unknown", "", "32.01.1975"})
    void rejectsImpossibleDates(String text) {
        assertThat(CsvValues.date(text)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"F,FEMALE", "female,FEMALE", "W,FEMALE", "m,MALE", "Male,MALE", "o,OTHER", "X,OTHER", "non-binary,OTHER"})
    void parsesGenders(String text, Gender expected) {
        assertThat(CsvValues.gender(text)).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({"A+,A_POSITIVE", "a pos,A_POSITIVE", "AB negative,AB_NEGATIVE", "0-,O_NEGATIVE", "O_POSITIVE,O_POSITIVE",
            "b−,B_NEGATIVE", "ab neg,AB_NEGATIVE"})
    void parsesBloodTypes(String text, BloodType expected) {
        assertThat(CsvValues.bloodType(text)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"C+", "AB", "O++", "A positive?", "Rh+"})
    void rejectsUnknownBloodTypes(String text) {
        assertThat(CsvValues.bloodType(text)).isEmpty();
    }

    @Test
    void matchesHeaderAliasesInAnyOrderAndIgnoresUnknownColumns() {
        CsvHeader header = CsvHeader.parse("﻿row,error,Email Address,First Name,SURNAME,D.O.B,Sex,Town,Mobile,Notes");

        assertThat(header.isValid()).isTrue();
        assertThat(header.line()).startsWith("row,");
        assertThat(header.columns()).containsExactly("email", "firstName", "lastName", "dateOfBirth", "gender", "city", "phone");
    }

    @Test
    void reportsMissingRequiredColumns() {
        CsvHeader header = CsvHeader.parse("first_name,email");

        assertThat(header.isValid()).isFalse();
        assertThat(header.missingColumns()).containsExactly("lastName", "dateOfBirth", "gender", "city");
    }
}
