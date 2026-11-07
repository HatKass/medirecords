package com.medirecords;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class HealthEndpointIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record Health(String status, Map<String, Object> components) {}

    @Test
    void healthEndpointReportsUpIncludingDatabase() {
        ResponseEntity<Health> response = restTemplate.getForEntity("/actuator/health", Health.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("UP");
        assertThat(response.getBody().components()).containsKey("db");
    }

    @Test
    void flywayAppliedBaselineMigration() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class);
        Integer trgm = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(trgm).isEqualTo(1);
    }

    @Test
    void openApiDocumentIsServed() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("MediRecords API");
    }
}
