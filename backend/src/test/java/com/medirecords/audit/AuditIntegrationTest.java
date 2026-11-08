package com.medirecords.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.medirecords.TestcontainersConfiguration;
import com.medirecords.support.TestBrowser;
import com.medirecords.support.TestData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/** The audit trail of patient changes and imports, its filters and its access rules, against real Postgres. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuditIntegrationTest {

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
        token = TestData.randomLetters(6) + "zq";
    }

    @Test
    void recordsCreateUpdateAndDeleteWithChangedFields() {
        JsonNode created = admin.post("/api/patients", patient("Leeds"), JsonNode.class).getBody();
        long id = created.get("id").asLong();

        Map<String, Object> edit = patient("York");
        edit.put("version", created.get("version").asLong());
        JsonNode updated = admin.put("/api/patients/" + id, edit, JsonNode.class).getBody();
        // Saving again without edits is not a change and must not add an entry.
        edit.put("version", updated.get("version").asLong());
        admin.put("/api/patients/" + id, edit, JsonNode.class);
        admin.delete("/api/patients/" + id, Void.class);

        List<JsonNode> entries = entriesFor("Patient", id);
        assertThat(entries).extracting(entry -> entry.get("action").asText())
                .containsExactly("DELETE", "UPDATE", "CREATE"); // newest first
        entries.forEach(entry -> assertThat(entry.get("actor").asText()).isEqualTo("admin"));

        JsonNode create = entries.get(2);
        assertThat(create.get("summary").asText()).isEqualTo("Created patient Audit Test" + token);
        assertThat(changes(create)).containsEntry("city", new String[] {null, "Leeds"})
                .containsEntry("gender", new String[] {null, "FEMALE"})
                .containsEntry("dateOfBirth", new String[] {null, "1985-04-12"})
                .doesNotContainKey("phone"); // null before and after

        JsonNode update = entries.get(1);
        assertThat(update.get("summary").asText()).isEqualTo("Updated patient Audit Test" + token + " (city)");
        assertThat(changes(update)).containsOnlyKeys("city").containsEntry("city", new String[] {"Leeds", "York"});

        JsonNode delete = entries.get(0);
        assertThat(changes(delete)).containsEntry("city", new String[] {"York", null})
                .containsEntry("email", new String[] {"audit." + token + "@example.com", null});
    }

    @Test
    void anImportIsOneEntryWithCountsNotOneEntryPerRow() {
        // (Counts every entry: no sign-in happens between "before" and the end of the import.)
        StringBuilder csv = new StringBuilder("firstName,lastName,dateOfBirth,gender,email,city\n");
        for (int row = 0; row < 120; row++) {
            csv.append("Bulk,Row").append(token).append(",1990-01-01,F,bulk").append(row).append('.').append(token)
                    .append("@example.com,York\n");
        }
        csv.append("Bad,Row").append(token).append(",not a date,F,bad.").append(token).append("@example.com,York\n");
        long before = totalEntries();

        long importId = admin.upload("/api/imports", "bulk.csv", csv.toString().getBytes(StandardCharsets.UTF_8),
                JsonNode.class).getBody().get("id").asLong();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() ->
                admin.get("/api/imports/" + importId, JsonNode.class).getBody().get("status").asText()
                        .equals("COMPLETED"));

        assertThat(totalEntries()).as("one entry for 121 rows").isEqualTo(before + 1);
        List<JsonNode> entries = entriesFor("Import", importId);
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.getFirst();
        assertThat(entry.get("action").asText()).isEqualTo("IMPORT");
        assertThat(entry.get("actor").asText()).isEqualTo("admin");
        assertThat(entry.get("summary").asText()).isEqualTo("Imported bulk.csv (120 valid, 1 invalid of 121 rows)");
        assertThat(entry.get("changes")).isEmpty();
        JsonNode details = entry.get("details");
        assertThat(details.get("fileName").asText()).isEqualTo("bulk.csv");
        assertThat(details.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(details.get("totalRows").asInt()).isEqualTo(121);
        assertThat(details.get("validRows").asInt()).isEqualTo(120);
        assertThat(details.get("invalidRows").asInt()).isEqualTo(1);
    }

    @Test
    void filtersByUserActionAndDateRange() {
        Instant start = Instant.now().minusSeconds(1);
        long id = admin.post("/api/patients", patient("Leeds"), JsonNode.class).getBody().get("id").asLong();

        assertThat(ids("/api/audit-log?size=100&actor=admin&action=CREATE&from=" + start)).contains(id);
        assertThat(ids("/api/audit-log?size=100&action=DELETE&from=" + start)).doesNotContain(id);
        assertThat(ids("/api/audit-log?size=100&actor=viewer")).doesNotContain(id);
        assertThat(ids("/api/audit-log?size=100&to=" + start)).doesNotContain(id);
        Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS);
        assertThat(ids("/api/audit-log?size=100&from=" + tomorrow)).isEmpty();
        assertThat(admin.get("/api/audit-log/actors", JsonNode.class).getBody().toString()).contains("\"admin\"");

        ResponseEntity<JsonNode> backwards = admin.get("/api/audit-log?from=" + tomorrow + "&to=" + start, JsonNode.class);
        assertThat(backwards.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(admin.get("/api/audit-log?action=LOGIN", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void recordsSignInsFailedSignInsAndSignOutsWithUsernameAndTimeOnly() {
        Instant start = Instant.now().minusSeconds(1);
        String wrongPassword = "wrong-" + token;
        String unknownUser = "nobody-" + token;

        TestBrowser viewer = new TestBrowser(restTemplate);
        viewer.login("viewer");
        viewer.post("/api/auth/logout", null, Void.class);
        assertThat(failedLogin("viewer", wrongPassword).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(failedLogin("  " + unknownUser + " ", wrongPassword).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        List<JsonNode> viewerEvents = entries("/api/audit-log?size=100&actor=viewer&from=" + start);
        assertThat(viewerEvents).extracting(entry -> entry.get("action").asText())
                .containsExactly("LOGIN_FAILED", "LOGOUT", "LOGIN_SUCCESS"); // newest first
        assertThat(viewerEvents).extracting(entry -> entry.get("summary").asText())
                .containsExactly("Failed sign-in attempt", "Signed out", "Signed in");
        viewerEvents.forEach(entry -> {
            assertThat(entry.get("entityType").asText()).isEqualTo("Session");
            assertThat(entry.get("entityId").isNull()).isTrue();
            assertThat(entry.get("changes")).isEmpty();
            assertThat(entry.get("details")).isEmpty();
            assertThat(entry.get("occurredAt").isTextual()).isTrue();
        });

        // A failed attempt for a username that does not exist is recorded under the (trimmed) typed name.
        List<JsonNode> unknown = entries("/api/audit-log?size=100&actor=" + unknownUser);
        assertThat(unknown).singleElement().satisfies(entry ->
                assertThat(entry.get("action").asText()).isEqualTo("LOGIN_FAILED"));
        assertThat(entries("/api/audit-log?size=100&action=LOGIN_FAILED&from=" + start)).hasSize(2);

        // The password is stored nowhere in the audit log, in any column.
        Integer withPassword = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE concat_ws('|', actor, "
                + "summary, entity_type, changes::text, details::text) LIKE ?", Integer.class, "%" + wrongPassword + "%");
        assertThat(withPassword).isZero();
    }

    @Test
    void signingOutWithoutASessionRecordsNothing() {
        TestBrowser anonymous = new TestBrowser(restTemplate);
        anonymous.fetchCsrfCookie();
        long before = totalEntries();

        assertThat(anonymous.post("/api/auth/logout", null, Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(totalEntries()).isEqualTo(before);
    }

    @Test
    void onlyAdminsCanReadTheAuditLog() {
        TestBrowser viewer = new TestBrowser(restTemplate);
        viewer.login("viewer");

        assertThat(viewer.get("/api/audit-log", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(viewer.get("/api/audit-log/actors", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(new TestBrowser(restTemplate).get("/api/audit-log", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theDatabaseRefusesToChangeOrDeleteEntries() {
        admin.post("/api/patients", patient("Leeds"), JsonNode.class);

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET actor = 'someone-else'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log")).hasMessageContaining("append-only");
    }

    private Map<String, Object> patient(String city) {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Audit");
        body.put("lastName", "Test" + token);
        body.put("dateOfBirth", "1985-04-12");
        body.put("gender", "FEMALE");
        body.put("email", "audit." + token + "@example.com");
        body.put("city", city);
        return body;
    }

    private ResponseEntity<JsonNode> failedLogin(String username, String password) {
        TestBrowser browser = new TestBrowser(restTemplate);
        browser.fetchCsrfCookie();
        return browser.post("/api/auth/login", Map.of("username", username, "password", password), JsonNode.class);
    }

    private List<JsonNode> entries(String path) {
        ResponseEntity<JsonNode> response = admin.get(path, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<JsonNode> entries = new ArrayList<>();
        response.getBody().get("content").forEach(entries::add);
        return entries;
    }

    private long totalEntries() {
        return admin.get("/api/audit-log?size=1", JsonNode.class).getBody().get("totalElements").asLong();
    }

    private List<JsonNode> entriesFor(String entityType, long entityId) {
        List<JsonNode> entries = new ArrayList<>();
        admin.get("/api/audit-log?size=100", JsonNode.class).getBody().get("content").forEach(entry -> {
            if (entry.get("entityType").asText().equals(entityType) && entry.get("entityId").asLong() == entityId) {
                entries.add(entry);
            }
        });
        return entries;
    }

    private List<Long> ids(String path) {
        ResponseEntity<JsonNode> response = admin.get(path, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Long> ids = new ArrayList<>();
        response.getBody().get("content").forEach(entry -> ids.add(entry.get("entityId").asLong()));
        return ids;
    }

    /** field → [oldValue, newValue] */
    private static Map<String, String[]> changes(JsonNode entry) {
        Map<String, String[]> changes = new HashMap<>();
        entry.get("changes").forEach(change -> changes.put(change.get("field").asText(), new String[] {
                change.get("oldValue").isNull() ? null : change.get("oldValue").asText(),
                change.get("newValue").isNull() ? null : change.get("newValue").asText()}));
        return changes;
    }
}
