package com.medirecords.dashboard;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The dashboard's figures against real Postgres. Other test classes share the database, so the
 * assertions compare against the database itself or before/after deltas, never fixed totals.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DashboardIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private TestBrowser admin;
    private String token;

    @BeforeEach
    void setUp() {
        admin = new TestBrowser(restTemplate);
        admin.login("admin");
        token = TestData.randomLetters(6) + "dq";
    }

    @Test
    void patientFiguresMatchTheDatabase() {
        JsonNode patients = dashboard(admin).get("patients");

        long total = jdbc.queryForObject("SELECT count(*) FROM patient", Long.class);
        assertThat(patients.get("total").asLong()).isEqualTo(total);

        // Every blood type appears, in a fixed order, and the groups add up to the total.
        List<String> types = new ArrayList<>();
        long sum = 0;
        for (JsonNode group : patients.get("byBloodType")) {
            types.add(group.get("bloodType").isNull() ? null : group.get("bloodType").asText());
            sum += group.get("count").asLong();
        }
        assertThat(types.subList(0, 8)).containsExactly("A_POSITIVE", "A_NEGATIVE", "B_POSITIVE", "B_NEGATIVE",
                "AB_POSITIVE", "AB_NEGATIVE", "O_POSITIVE", "O_NEGATIVE");
        assertThat(sum).isEqualTo(total);
        long oNegative = jdbc.queryForObject("SELECT count(*) FROM patient WHERE blood_type = 'O_NEGATIVE'", Long.class);
        assertThat(patients.get("byBloodType").get(7).get("count").asLong()).isEqualTo(oNegative);
    }

    @Test
    void creatingAPatientMovesTotalsAndTheRightBloodTypeBar() {
        JsonNode before = dashboard(admin).get("patients");

        admin.post("/api/patients", patient("dash.a." + token, "AB_NEGATIVE"), JsonNode.class);
        admin.post("/api/patients", patient("dash.b." + token, null), JsonNode.class);

        JsonNode after = dashboard(admin).get("patients");
        assertThat(after.get("total").asLong()).isEqualTo(before.get("total").asLong() + 2);
        assertThat(after.get("addedSince").asLong()).isEqualTo(before.get("addedSince").asLong() + 2);
        assertThat(countFor(after, "AB_NEGATIVE")).isEqualTo(countFor(before, "AB_NEGATIVE") + 1);
        assertThat(countFor(after, null)).as("unknown blood type").isEqualTo(countFor(before, null) + 1);
    }

    @Test
    void anImportCountsTowardsTheWeekWithItsRejectedRows() {
        JsonNode before = dashboard(admin).get("imports");

        String csv = "firstName,lastName,dateOfBirth,gender,email,city\n"
                + "Ok,Row" + token + ",1990-01-01,F,ok1." + token + "@example.com,York\n"
                + "Ok,Row" + token + ",1990-01-02,M,ok2." + token + "@example.com,York\n"
                + "Bad,Row" + token + ",not a date,F,bad." + token + "@example.com,York\n";
        long importId = admin.upload("/api/imports", "dash-" + token + ".csv", csv.getBytes(StandardCharsets.UTF_8),
                JsonNode.class).getBody().get("id").asLong();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() ->
                admin.get("/api/imports/" + importId, JsonNode.class).getBody().get("status").asText()
                        .equals("COMPLETED"));

        JsonNode body = dashboard(admin);
        JsonNode after = body.get("imports");
        assertThat(after.get("count").asLong()).isEqualTo(before.get("count").asLong() + 1);
        assertThat(after.get("validRows").asLong()).isEqualTo(before.get("validRows").asLong() + 2);
        assertThat(after.get("invalidRows").asLong()).isEqualTo(before.get("invalidRows").asLong() + 1);
        assertThat(after.get("failedImports").asLong()).isEqualTo(before.get("failedImports").asLong());

        JsonNode newest = body.get("recentImports").get(0);
        assertThat(newest.get("id").asLong()).isEqualTo(importId);
        assertThat(newest.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(newest.get("validRows").asInt()).isEqualTo(2);
        assertThat(newest.get("invalidRows").asInt()).isEqualTo(1);
        assertThat(body.get("recentImports").size()).isLessThanOrEqualTo(DashboardService.RECENT_IMPORTS);
    }

    @Test
    void importsOlderThanSevenDaysAreNotCounted() {
        JsonNode before = dashboard(admin).get("imports");
        jdbc.update("INSERT INTO patient_import (file_name, source_header, status, imported_by, invalid_rows, created_at) "
                + "VALUES ('old.csv', 'h', 'FAILED', 'admin', 40, now() - interval '8 days')");

        JsonNode after = dashboard(admin).get("imports");
        assertThat(after.get("count").asLong()).isEqualTo(before.get("count").asLong());
        assertThat(after.get("invalidRows").asLong()).isEqualTo(before.get("invalidRows").asLong());
        assertThat(after.get("failedImports").asLong()).isEqualTo(before.get("failedImports").asLong());
    }

    @Test
    void adminsSeeRecentDataChangesWithoutSignInsOrOldAndNewValues() {
        long id = admin.post("/api/patients", patient("dash.c." + token, "O_POSITIVE"), JsonNode.class)
                .getBody().get("id").asLong();
        admin.login("admin"); // a sign-in after the change must not push it out of the list

        JsonNode activity = dashboard(admin).get("recentActivity");
        assertThat(activity.isArray()).isTrue();
        assertThat(activity.size()).isBetween(1, DashboardService.RECENT_ACTIVITY);
        JsonNode newest = activity.get(0);
        assertThat(newest.get("action").asText()).isEqualTo("CREATE");
        assertThat(newest.get("entityType").asText()).isEqualTo("Patient");
        assertThat(newest.get("entityId").asLong()).isEqualTo(id);
        assertThat(newest.get("actor").asText()).isEqualTo("admin");
        assertThat(newest.has("changes")).as("old/new values stay in the audit log").isFalse();
        activity.forEach(entry -> assertThat(entry.get("action").asText())
                .isIn("CREATE", "UPDATE", "DELETE", "IMPORT"));
    }

    @Test
    void viewersGetTheFiguresButNoAuditActivity() {
        TestBrowser viewer = new TestBrowser(restTemplate);
        viewer.login("viewer");

        JsonNode body = dashboard(viewer);
        assertThat(body.has("recentActivity")).as("field absent, not null").isFalse();
        assertThat(body.get("patients").get("total").asLong()).isPositive();
        assertThat(body.has("imports")).isTrue();
        assertThat(body.has("recentImports")).isTrue();
    }

    @Test
    void requiresSignIn() {
        assertThat(new TestBrowser(restTemplate).get("/api/dashboard", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static JsonNode dashboard(TestBrowser browser) {
        ResponseEntity<JsonNode> response = browser.get("/api/dashboard", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static long countFor(JsonNode patients, String bloodType) {
        for (JsonNode group : patients.get("byBloodType")) {
            String type = group.get("bloodType").isNull() ? null : group.get("bloodType").asText();
            if (bloodType == null ? type == null : bloodType.equals(type)) {
                return group.get("count").asLong();
            }
        }
        return 0;
    }

    private Map<String, Object> patient(String emailLocalPart, String bloodType) {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Dash");
        body.put("lastName", "Board" + token);
        body.put("dateOfBirth", "1980-02-03");
        body.put("gender", "MALE");
        body.put("email", emailLocalPart + "@example.com");
        body.put("city", "Bristol");
        body.put("bloodType", bloodType);
        return body;
    }
}
