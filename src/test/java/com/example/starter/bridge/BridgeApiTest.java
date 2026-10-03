package com.example.starter.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 全链路 HTTP 场景测试：合成 34 吨车 09:00 获许可，09:05 车 409，
 * 首车 09:12 出桥前不得放行，出桥后新申请获准。独立命名库 bridge_api_test，结束后释放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:bridge_api_test;MODE=MySQL;"
                + "DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BridgeApiTest {

    private static final Instant T09 = Instant.parse("2026-01-01T09:00:00Z");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @TestConfiguration
    static class TestClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T09);
        }
    }

    @BeforeEach
    void cleanData() {
        jdbcTemplate.update("DELETE FROM bridge_rejection");
        jdbcTemplate.update("DELETE FROM bridge_permit");
        clock.set(T09);
    }

    @AfterAll
    void shutdownDatabase() {
        jdbcTemplate.execute("SHUTDOWN");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<JsonNode> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(url(path), new HttpEntity<>(body, headers),
                String.class);
        JsonNode json = read(response.getBody());
        return ResponseEntity.status(response.getStatusCode()).body(json);
    }

    private JsonNode read(String body) {
        try {
            return body == null ? objectMapper.nullNode() : objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<String, Object> applyBody(String requestId, String plateNo, long massKg, String plannedEntryAt) {
        return Map.of("requestId", requestId, "plateNo", plateNo, "massKg", massKg,
                "plannedEntryAt", plannedEntryAt);
    }
    @Test
    void syntheticScenario34TonAtNine() {
        ResponseEntity<JsonNode> first = post("/api/bridge/permits",
                applyBody("req-a", "PLATE-34T", 34_000, "2026-01-01T09:00:00Z"));
        assertEquals(HttpStatus.CREATED, first.getStatusCode());
        long permitId = first.getBody().get("id").asLong();
        assertEquals("2026-01-01T09:10:00Z", first.getBody().get("windowEnd").asText());

        ResponseEntity<JsonNode> overlap = post("/api/bridge/permits",
                applyBody("req-b", "PLATE-B", 20_000, "2026-01-01T09:05:00Z"));
        assertEquals(HttpStatus.CONFLICT, overlap.getStatusCode());
        assertEquals("WINDOW_OVERLAP", overlap.getBody().get("code").asText());

        ResponseEntity<JsonNode> entered = post("/api/bridge/permits/" + permitId + "/enter",
                Map.of("requestId", "enter-a", "at", "2026-01-01T09:00:00Z"));
        assertEquals(HttpStatus.OK, entered.getStatusCode());
        assertEquals("ENTERED", entered.getBody().get("status").asText());

        clock.set(Instant.parse("2026-01-01T09:10:00Z"));
        ResponseEntity<JsonNode> stillBlocked = post("/api/bridge/permits",
                applyBody("req-c", "PLATE-C", 20_000, "2026-01-01T09:10:00Z"));
        assertEquals(HttpStatus.CONFLICT, stillBlocked.getStatusCode());
        assertEquals("WINDOW_OVERLAP", stillBlocked.getBody().get("code").asText());

        ResponseEntity<JsonNode> exited = post("/api/bridge/permits/" + permitId + "/exit",
                Map.of("requestId", "exit-a", "at", "2026-01-01T09:12:00Z"));
        assertEquals(HttpStatus.OK, exited.getStatusCode());
        assertEquals("EXITED", exited.getBody().get("status").asText());
        assertEquals("2026-01-01T09:12:00Z", exited.getBody().get("exitedAt").asText());

        ResponseEntity<JsonNode> afterExit = post("/api/bridge/permits",
                applyBody("req-c", "PLATE-C", 20_000, "2026-01-01T09:12:00Z"));
        assertEquals(HttpStatus.CREATED, afterExit.getStatusCode());

        JsonNode timeline = rest.getForObject(url("/api/bridge/timeline"), JsonNode.class);
        assertEquals(35_000, timeline.get("maxMassKg").asLong());
        assertEquals(10, timeline.get("windowMinutes").asLong());
        assertEquals(2, timeline.get("entries").size());
        JsonNode firstEntry = timeline.get("entries").get(0);
        assertEquals("EXITED", firstEntry.get("status").asText());
        assertEquals("2026-01-01T09:00:00Z", firstEntry.get("enteredAt").asText());
        assertEquals("2026-01-01T09:12:00Z", firstEntry.get("exitedAt").asText());

        JsonNode rejections = rest.getForObject(url("/api/bridge/rejections"), JsonNode.class);
        assertEquals(2, rejections.size());
        assertEquals("req-b", rejections.get(0).get("requestId").asText());
        assertEquals("WINDOW_OVERLAP", rejections.get(0).get("reason").asText());
        assertEquals("req-c", rejections.get(1).get("requestId").asText());

        JsonNode permits = rest.getForObject(url("/api/bridge/permits"), JsonNode.class);
        assertEquals(2, permits.size());
        assertEquals("EXITED", permits.get(0).get("status").asText());
        assertEquals("APPROVED", permits.get(1).get("status").asText());
    }

    @Test
    void validationFailureReturns400() {
        ResponseEntity<JsonNode> response = post("/api/bridge/permits",
                Map.of("requestId", "", "plateNo", "PLATE-A", "massKg", 0,
                        "plannedEntryAt", "2026-01-01T09:00:00Z"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_FAILED", response.getBody().get("code").asText());
    }

    @Test
    void missingPermitReturns404() {
        ResponseEntity<JsonNode> response = post("/api/bridge/permits/999/enter",
                Map.of("requestId", "enter-x", "at", "2026-01-01T09:00:00Z"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("PERMIT_NOT_FOUND", response.getBody().get("code").asText());
    }

    @Test
    void replayedApplyReturns200WithSamePermit() {
        ResponseEntity<JsonNode> first = post("/api/bridge/permits",
                applyBody("req-replay", "PLATE-A", 10_000, "2026-01-01T09:00:00Z"));
        assertEquals(HttpStatus.CREATED, first.getStatusCode());
        ResponseEntity<JsonNode> replay = post("/api/bridge/permits",
                applyBody("req-replay", "PLATE-A", 10_000, "2026-01-01T09:00:00Z"));
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertEquals(first.getBody().get("id").asLong(), replay.getBody().get("id").asLong());

        ResponseEntity<JsonNode> conflict = post("/api/bridge/permits",
                applyBody("req-replay", "PLATE-B", 10_000, "2026-01-01T09:00:00Z"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("REQUEST_ID_CONFLICT", conflict.getBody().get("code").asText());
        assertTrue(rest.getForObject(url("/api/bridge/permits"), JsonNode.class).size() == 1);
    }
}