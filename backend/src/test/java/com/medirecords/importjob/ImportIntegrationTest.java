package com.medirecords.importjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.medirecords.TestcontainersConfiguration;
import com.medirecords.support.TestBrowser;
import com.medirecords.support.TestData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The whole import pipeline against real Postgres: upload, asynchronous Spring Batch job, polling,
 * imported patients, rejected rows and the error report. Emails carry a random token per test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ImportIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private TestBrowser admin;
    private String token;

    @BeforeEach
    void setUp() {
        admin = new TestBrowser(restTemplate);
        admin.login("admin");
        token = TestData.randomLetters(6) + "zq";
    }

    @Test
    void importsValidRowsAndReportsEachInvalidRowWithItsReason() {
        // Header in a different order with "friendly" names, an Excel BOM, CRLF endings and a blank line.
        String header = "Email Address,First Name,Surname,DOB,Sex,City,Phone,Blood Group";
        String csv = "﻿" + header + "\r\n"
                + line("  Jane." + token + "@Example.COM ", "  Jane ", "Doe" + token, "31/12/1980", "female", " London ", "+44 7700 900-123", "a pos") // row 2: valid, messy
                + line("omar." + token + "@example.org", "Omar", "Haddad" + token, "1975-03-04", "M", "Leeds", "", "")                               // row 3: valid, no optionals
                + line("kai." + token + "@example.net", "Kai", "Lee" + token, "4 Jul 1990", "x", "Bristol", "07700 900456", "0-")                     // row 4: valid
                + "\r\n"                                                                                                                              // row 5: blank, ignored
                + line("not-an-email", "Bad", "Email" + token, "1980-01-01", "F", "Leeds", "", "")                                                    // row 6
                + line("ann." + token + "@example.com", "Ann", "Date" + token, "31/02/1990", "F", "Leeds", "", "")                                   // row 7
                + line("nofirst." + token + "@example.com", "", "Name" + token, "1980-01-01", "F", "Leeds", "", "")                                  // row 8
                + line("JANE." + token + "@example.com", "Jane", "Again" + token, "1980-01-01", "F", "Leeds", "", "")                                // row 9: dup in file
                + line("aisha.rossi@example.net", "Aisha", "Seeded" + token, "1980-01-01", "F", "Leeds", "", "")                                     // row 10: dup of seed
                + line("g." + token + "@example.com", "Gee", "Gender" + token, "1980-01-01", "unknown", "Leeds", "", "C+")                           // row 11: two problems
                + "too,few,columns\r\n";                                                                                                             // row 12

        ResponseEntity<JsonNode> upload = admin.upload("/api/imports", "patients.csv", bytes(csv), JsonNode.class);

        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long id = upload.getBody().get("id").asLong();
        assertThat(upload.getHeaders().getFirst(HttpHeaders.LOCATION)).endsWith("/api/imports/" + id);
        assertThat(upload.getBody().get("expectedRows").asInt()).isEqualTo(10);

        JsonNode finished = awaitFinished(id);
        assertThat(finished.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(finished.get("validRows").asInt()).isEqualTo(3);
        assertThat(finished.get("invalidRows").asInt()).isEqualTo(7);
        assertThat(finished.get("totalRows").asInt()).isEqualTo(10);
        assertThat(finished.get("progressPercent").asInt()).isEqualTo(100);
        assertThat(finished.get("importedBy").asText()).isEqualTo("admin");
        assertThat(finished.get("jobExecutionId").isNumber()).isTrue();
        assertThat(finished.get("startedAt").isTextual()).isTrue();
        assertThat(finished.get("finishedAt").isTextual()).isTrue();

        // Imported rows are cleaned exactly like API input.
        JsonNode jane = admin.get("/api/patients?name=jane doe" + token, JsonNode.class).getBody().get("content").get(0);
        assertThat(jane.get("firstName").asText()).isEqualTo("Jane");
        assertThat(jane.get("email").asText()).isEqualTo("jane." + token + "@example.com");
        assertThat(jane.get("dateOfBirth").asText()).isEqualTo("1980-12-31");
        assertThat(jane.get("gender").asText()).isEqualTo("FEMALE");
        assertThat(jane.get("phone").asText()).isEqualTo("+447700900123");
        assertThat(jane.get("city").asText()).isEqualTo("London");
        assertThat(jane.get("bloodType").asText()).isEqualTo("A_POSITIVE");
        JsonNode kai = admin.get("/api/patients?name=kai lee" + token, JsonNode.class).getBody().get("content").get(0);
        assertThat(kai.get("gender").asText()).isEqualTo("OTHER");
        assertThat(kai.get("bloodType").asText()).isEqualTo("O_NEGATIVE");
        assertThat(kai.get("dateOfBirth").asText()).isEqualTo("1990-07-04");

        Map<Integer, String> reasons = new HashMap<>();
        admin.get("/api/imports/" + id + "/errors?size=50", JsonNode.class).getBody().get("content")
                .forEach(error -> reasons.put(error.get("rowNumber").asInt(), error.get("reason").asText()));
        assertThat(reasons).containsOnlyKeys(6, 7, 8, 9, 10, 11, 12);
        assertThat(reasons.get(6)).isEqualTo("Email 'not-an-email' must be a valid email address");
        assertThat(reasons.get(7)).startsWith("Date of birth '31/02/1990' is not a valid date");
        assertThat(reasons.get(8)).isEqualTo("First name is missing");
        assertThat(reasons.get(9)).isEqualTo("Duplicate email jane." + token + "@example.com (already used on row 2)");
        assertThat(reasons.get(10)).isEqualTo("Duplicate email aisha.rossi@example.net (a patient with this email already exists)");
        assertThat(reasons.get(11)).contains("Gender 'unknown' is not recognized").contains("Blood type 'C+'")
                .doesNotContain("Gender is missing");
        assertThat(reasons.get(12)).isEqualTo("Wrong number of columns: expected 8, found 3");
    }

    @Test
    void errorReportKeepsOriginalLinesAndCanBeImportedAgain() {
        String header = "firstName,lastName,dateOfBirth,gender,email,city";
        String badLine = "\"Smith, Jr\",Report" + token + ",not a date,M,report." + token + "@example.com,York";
        String csv = header + "\n" + badLine + "\n"
                + "Good,Report" + token + ",1990-01-01,F,good." + token + "@example.com,York\n";
        long id = admin.upload("/api/imports", "clinic-export.csv", bytes(csv), JsonNode.class).getBody().get("id").asLong();
        awaitFinished(id);

        ResponseEntity<byte[]> report = admin.get("/api/imports/" + id + "/errors.csv", byte[].class);

        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(report.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(report.getHeaders().getContentDisposition().getFilename()).isEqualTo("clinic-export-errors.csv");
        String body = new String(report.getBody(), StandardCharsets.UTF_8);
        List<String> lines = List.of(body.split("\r\n"));
        assertThat(lines.getFirst()).isEqualTo("﻿row,error," + header);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).startsWith("2,\"Date of birth 'not a date' is not a valid date").endsWith("," + badLine);

        // The report is a valid import file: "row" and "error" are ignored, the original columns are read.
        long again = admin.upload("/api/imports", "clinic-export-errors.csv", report.getBody(), JsonNode.class)
                .getBody().get("id").asLong();
        JsonNode reimport = awaitFinished(again);
        assertThat(reimport.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(reimport.get("invalidRows").asInt()).isEqualTo(1);
        assertThat(reimport.get("validRows").asInt()).isZero();
    }

    @Test
    void historyListsNewestFirst() {
        String csv = "firstName,lastName,dateOfBirth,gender,email,city\nA,B" + token + ",1990-01-01,F,h." + token + "@example.com,York\n";
        long first = admin.upload("/api/imports", "first.csv", bytes(csv), JsonNode.class).getBody().get("id").asLong();
        long second = admin.upload("/api/imports", "second.csv", bytes(csv), JsonNode.class).getBody().get("id").asLong();
        awaitFinished(first);
        awaitFinished(second);

        JsonNode page = admin.get("/api/imports?size=100", JsonNode.class).getBody();
        List<Long> ids = new ArrayList<>();
        page.get("content").forEach(item -> ids.add(item.get("id").asLong()));
        assertThat(ids.indexOf(second)).isLessThan(ids.indexOf(first));
        assertThat(admin.get("/api/imports/" + second, JsonNode.class).getBody().get("invalidRows").asInt())
                .as("same email as the first import").isEqualTo(1);
    }

    @Test
    void rejectsFilesThatCannotBeImportedBeforeStartingAJob() {
        ResponseEntity<JsonNode> missingColumns = admin.upload("/api/imports", "p.csv",
                bytes("name,email\nJane,jane@example.com\n"), JsonNode.class);
        assertThat(missingColumns.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missingColumns.getBody().get("detail").asText())
                .contains("missing these columns: firstName, lastName, dateOfBirth, gender, city");

        ResponseEntity<JsonNode> notCsv = admin.upload("/api/imports", "patients.xlsx", bytes("whatever"), JsonNode.class);
        assertThat(notCsv.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(notCsv.getBody().get("detail").asText()).isEqualTo("Only .csv files can be imported.");

        ResponseEntity<JsonNode> headerOnly = admin.upload("/api/imports", "p.csv",
                bytes("firstName,lastName,dateOfBirth,gender,email,city\n\n"), JsonNode.class);
        assertThat(headerOnly.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(headerOnly.getBody().get("detail").asText()).isEqualTo("The file has a header row but no data rows.");

        assertThat(admin.get("/api/imports/999999", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void viewersCanReadHistoryButNotUpload() {
        TestBrowser viewer = new TestBrowser(restTemplate);
        viewer.login("viewer");

        ResponseEntity<JsonNode> upload = viewer.upload("/api/imports", "p.csv",
                bytes("firstName,lastName,dateOfBirth,gender,email,city\nA,B,1990-01-01,F,v@example.com,York\n"),
                JsonNode.class);

        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(viewer.get("/api/imports", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private JsonNode awaitFinished(long id) {
        JsonNode[] latest = new JsonNode[1];
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() -> {
            latest[0] = admin.get("/api/imports/" + id, JsonNode.class).getBody();
            String status = latest[0].get("status").asText();
            return status.equals("COMPLETED") || status.equals("FAILED");
        });
        return latest[0];
    }

    /** Column order: email, first, last, dob, gender, city, phone, blood (matches the first test's header). */
    private static String line(String email, String first, String last, String dob, String gender, String city,
            String phone, String blood) {
        return String.join(",", email, first, last, dob, gender, city, phone, blood) + "\r\n";
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
