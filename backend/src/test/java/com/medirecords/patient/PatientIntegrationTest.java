package com.medirecords.patient;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.medirecords.TestcontainersConfiguration;
import com.medirecords.support.TestBrowser;
import com.medirecords.support.TestData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Patient CRUD, validation and filtering against real Postgres (with the 200 seeded patients present).
 * Each test uses a random token in names and emails so tests never see each other's rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class PatientIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private TestBrowser admin;
    private String token;

    @BeforeEach
    void setUp() {
        admin = new TestBrowser(restTemplate);
        admin.login("admin");
        // Letters only, so it also works as a name filter.
        token = TestData.randomLetters(6) + "zq";
    }

    @Test
    void createNormalizesInputAndReturnsLocation() {
        Map<String, Object> body = patient("  Jane  ", "Doe" + token, "Testville" + token);
        body.put("email", "  Jane." + token + "@Example.COM ");
        body.put("phone", "+44 7700 900-123");

        ResponseEntity<JsonNode> created = admin.post("/api/patients", body, JsonNode.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode patient = created.getBody();
        assertThat(patient).isNotNull();
        assertThat(patient.get("firstName").asText()).isEqualTo("Jane");
        assertThat(patient.get("email").asText()).isEqualTo("jane." + token + "@example.com");
        assertThat(patient.get("phone").asText()).isEqualTo("+447700900123");
        assertThat(patient.get("version").asLong()).isZero();
        assertThat(patient.get("createdAt").asText()).isNotBlank();
        String location = created.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(location).endsWith("/api/patients/" + patient.get("id").asLong());

        JsonNode fetched = admin.get("/api/patients/" + patient.get("id").asLong(), JsonNode.class).getBody();
        assertThat(fetched).isEqualTo(patient);
    }

    @Test
    void invalidBodyListsEveryFieldError() {
        Map<String, Object> body = patient("", "Doe", "City");
        body.put("email", "not-an-email");
        body.put("dateOfBirth", "2999-01-01");
        body.put("phone", "call me");

        ResponseEntity<JsonNode> response = admin.post("/api/patients", body, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(fieldsWithErrors(response.getBody())).contains("firstName", "email", "dateOfBirth", "phone");
    }

    @Test
    void emptyBodyReportsEveryRequiredFieldWithAReadableMessage() {
        ResponseEntity<JsonNode> response = admin.post("/api/patients", Map.of(), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode problem = response.getBody();
        assertThat(problem.get("title").asText()).isEqualTo("Validation failed");
        assertThat(fieldsWithErrors(problem))
                .containsExactlyInAnyOrder("firstName", "lastName", "dateOfBirth", "gender", "email", "city");
        problem.get("errors").forEach(error -> assertThat(error.get("message").asText()).isNotBlank());
    }

    @Test
    void overlongValuesAreRejectedPerField() {
        Map<String, Object> body = patient("a".repeat(101), "Doe", "c".repeat(101));
        body.put("email", "a".repeat(250) + "@example.com");

        ResponseEntity<JsonNode> response = admin.post("/api/patients", body, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldsWithErrors(response.getBody())).contains("firstName", "city", "email")
                .doesNotContain("lastName");
    }

    /** Framework errors use the same ProblemDetail shape and never echo parser or class internals. */
    @Test
    void malformedRequestsAreProblemDetailsWithoutInternals() {
        ResponseEntity<String> badJson = admin.post("/api/patients", "{\"firstName\": ", String.class);
        assertThat(badJson.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badJson.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(badJson.getBody()).doesNotContain("Exception").doesNotContain("jackson").doesNotContain("at [Source");

        ResponseEntity<String> badDate = admin.post("/api/patients",
                Map.of("firstName", "A", "lastName", "B", "dateOfBirth", "31/12/1980"), String.class);
        assertThat(badDate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badDate.getBody()).doesNotContain("Exception");

        ResponseEntity<String> badId = admin.get("/api/patients/abc", String.class);
        assertThat(badId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badId.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(badId.getBody()).doesNotContain("NumberFormatException");

        ResponseEntity<String> wrongMethod = admin.sendWithCsrfHeader(HttpMethod.PATCH, "/api/patients/1", Map.of(),
                String.class, admin.cookie("XSRF-TOKEN"));
        assertThat(wrongMethod.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(wrongMethod.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void updatingAMissingPatientIsNotFound() {
        Map<String, Object> body = patient("Ghost", "Patient", "Leeds");
        body.put("version", 0);

        ResponseEntity<JsonNode> response = admin.put("/api/patients/999999999", body, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void changingTheEmailToAnotherPatientsEmailIsConflict() {
        JsonNode first = create(patient("Eve", "One" + token, "Leeds"));
        JsonNode second = create(patient("Eve", "Two" + token, "Leeds"));
        Map<String, Object> edit = patient("Eve", "Two" + token, "Leeds");
        edit.put("email", first.get("email").asText().toUpperCase());
        edit.put("version", second.get("version").asLong());

        ResponseEntity<JsonNode> response = admin.put("/api/patients/" + second.get("id").asLong(), edit, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void unknownEnumValueIsBadRequest() {
        Map<String, Object> body = patient("Jane", "Doe", "City");
        body.put("bloodType", "Z_POSITIVE");

        assertThat(admin.post("/api/patients", body, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void duplicateEmailIsConflictRegardlessOfCase() {
        Map<String, Object> first = patient("Ann", "Lee" + token, "Leeds");
        assertThat(admin.post("/api/patients", first, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> second = patient("Ann", "Lee" + token, "Leeds");
        second.put("email", first.get("email").toString().toUpperCase());
        ResponseEntity<JsonNode> response = admin.post("/api/patients", second, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("detail").asText()).contains("already exists");
    }

    @Test
    void updateRequiresTheCurrentVersion() {
        JsonNode created = create(patient("Bob", "Stone" + token, "Leeds"));
        long id = created.get("id").asLong();

        Map<String, Object> changes = patient("Robert", "Stone" + token, "York");
        changes.put("email", created.get("email").asText());

        ResponseEntity<JsonNode> missingVersion = admin.put("/api/patients/" + id, changes, JsonNode.class);
        assertThat(missingVersion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldsWithErrors(missingVersion.getBody())).contains("version");

        changes.put("version", 0);
        ResponseEntity<JsonNode> updated = admin.put("/api/patients/" + id, changes, JsonNode.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().get("firstName").asText()).isEqualTo("Robert");
        assertThat(updated.getBody().get("city").asText()).isEqualTo("York");
        assertThat(updated.getBody().get("version").asLong()).isEqualTo(1);

        // Someone still holding version 0 must not overwrite the newer data.
        ResponseEntity<JsonNode> stale = admin.put("/api/patients/" + id, changes, JsonNode.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void deleteRemovesThePatient() {
        long id = create(patient("Cara", "Gone" + token, "Leeds")).get("id").asLong();

        assertThat(admin.delete("/api/patients/" + id, Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> missing = admin.get("/api/patients/" + id, JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).isNotNull();
        assertThat(missing.getBody().get("title").asText()).isEqualTo("Patient not found");
        assertThat(admin.delete("/api/patients/" + id, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void viewerCanReadButNotWrite() {
        long id = create(patient("Dee", "Read" + token, "Leeds")).get("id").asLong();
        TestBrowser viewer = new TestBrowser(restTemplate);
        viewer.login("viewer");

        assertThat(viewer.get("/api/patients/" + id, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(viewer.get("/api/patients", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(viewer.post("/api/patients", patient("X", "Y", "Z"), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(viewer.put("/api/patients/" + id, patient("X", "Y", "Z"), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(viewer.delete("/api/patients/" + id, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listIsPagedAndSortedOverTheSeedData() {
        JsonNode page = admin.get("/api/patients?size=5&page=1&sort=dateOfBirth,desc", JsonNode.class).getBody();

        assertThat(page).isNotNull();
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("size").asInt()).isEqualTo(5);
        assertThat(page.get("totalElements").asLong()).isGreaterThanOrEqualTo(200);
        List<String> birthDates = new ArrayList<>();
        page.get("content").forEach(patient -> birthDates.add(patient.get("dateOfBirth").asText()));
        assertThat(birthDates).hasSize(5).isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    void filtersByNameCityAndBloodType() {
        create(withBloodType(patient("Mia", "Quill" + token, "Oxford"), "AB_NEGATIVE"));
        create(withBloodType(patient("Mia", "Quill" + token, "Bath"), "O_POSITIVE"));
        create(withBloodType(patient("Noah", "Other" + token, "Oxford"), "AB_NEGATIVE"));

        // Name matches across "first last", case-insensitively.
        assertThat(total("/api/patients?name=MIA QUILL" + token)).isEqualTo(2);
        assertThat(total("/api/patients?name=" + token)).isEqualTo(3);
        assertThat(total("/api/patients?name=" + token + "&city=OXFORD")).isEqualTo(2);
        assertThat(total("/api/patients?name=" + token + "&city=oxford&bloodType=AB_NEGATIVE")).isEqualTo(2);
        assertThat(total("/api/patients?name=quill" + token + "&bloodType=O_POSITIVE")).isEqualTo(1);
        // LIKE wildcards in user input are taken literally: unescaped, "_" would match every patient.
        assertThat(total("/api/patients?name=_")).isZero();
    }

    @Test
    void badListParametersAreRejectedOrCapped() {
        ResponseEntity<JsonNode> badSort = admin.get("/api/patients?sort=password", JsonNode.class);
        assertThat(badSort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badSort.getBody()).isNotNull();
        assertThat(badSort.getBody().get("detail").asText()).contains("Cannot sort by 'password'");

        assertThat(admin.get("/api/patients?bloodType=Z", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode capped = admin.get("/api/patients?size=500", JsonNode.class).getBody();
        assertThat(capped).isNotNull();
        assertThat(capped.get("size").asInt()).isEqualTo(100);
    }

    @Test
    void citiesListsDistinctValues() {
        JsonNode cities = admin.get("/api/patients/cities", JsonNode.class).getBody();

        assertThat(cities).isNotNull();
        List<String> names = new ArrayList<>();
        cities.forEach(city -> names.add(city.asText()));
        assertThat(names).contains("London", "Manchester").doesNotHaveDuplicates();
    }

    private JsonNode create(Map<String, Object> body) {
        ResponseEntity<JsonNode> response = admin.post("/api/patients", body, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private long total(String path) {
        ResponseEntity<JsonNode> response = admin.get(path, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("totalElements").asLong();
    }

    private Map<String, Object> patient(String firstName, String lastName, String city) {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("dateOfBirth", "1985-06-15");
        body.put("gender", "FEMALE");
        body.put("email", UUID.randomUUID() + "@example.com");
        body.put("city", city);
        body.put("bloodType", "A_POSITIVE");
        return body;
    }

    private static Map<String, Object> withBloodType(Map<String, Object> body, String bloodType) {
        body.put("bloodType", bloodType);
        return body;
    }

    private static List<String> fieldsWithErrors(JsonNode problem) {
        assertThat(problem).isNotNull();
        List<String> fields = new ArrayList<>();
        problem.get("errors").forEach(error -> fields.add(error.get("field").asText()));
        return fields;
    }
}
