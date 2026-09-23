package com.example.starter.blind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 泄露与隔离写操作的幂等边界（真实 H2）：
 * requestId 同参集合换序重放原结果；异参 409；失败不占键；
 * 隔离确认重放原 200 而非重复确认 409。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContaminationIdempotencyTest extends AbstractBlindIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpHeaders headers(String actor, String role, String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Actor-Id", actor);
        h.set("X-Role", role);
        if (requestId != null) {
            h.set("X-Request-Id", requestId);
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<String> post(String path, HttpHeaders headers, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertTrue(response.getBody() != null && !response.getBody().isBlank());
        return mapper.readTree(response.getBody());
    }

    private void approvedParticipant(String expId, String prefix) throws Exception {
        assertEquals(201, post("/api/experiments/" + expId,
                headers("coord-1", "COORDINATOR", prefix + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
        assertEquals(201, post("/api/experiments/" + expId + "/participants/PA/allocations",
                headers("coord-1", "COORDINATOR", prefix + "-alloc"), null)
                .getStatusCode().value());
        ResponseEntity<String> apply = post(
                "/api/experiments/" + expId + "/participants/PA/unblind-requests",
                headers("coord-1", "COORDINATOR", prefix + "-apply"), "{\"reason\":\"紧急\"}");
        String ubId = json(apply).path("requestId").asText();
        assertEquals(200, post("/api/unblind-requests/" + ubId + "/approval",
                headers("rev-2", "REVIEWER", prefix + "-approve"), null)
                .getStatusCode().value());
    }

    @Test
    void disclosure_sameKeyReorderedSet_replaysOriginal_andNoNewEdgesOrVersion() throws Exception {
        approvedParticipant("CI-1", "ci1");

        ResponseEntity<String> first = post(
                "/api/experiments/CI-1/participants/PA/disclosures",
                headers("coord-1", "COORDINATOR", "ci1-key"),
                "{\"exposureKey\":\"CI1-EX\",\"targetActorIds\":[\"op-a\",\"op-b\",\"op-c\"]}");
        assertEquals(201, first.getStatusCode().value());

        // 同键、同 exposureKey、集合换序 + 重复元素：视为同参，原样回放。
        ResponseEntity<String> replay = post(
                "/api/experiments/CI-1/participants/PA/disclosures",
                headers("coord-1", "COORDINATOR", "ci1-key"),
                "{\"exposureKey\":\"CI1-EX\",\"targetActorIds\":[\"op-c\",\"op-a\",\"op-b\",\"op-a\"]}");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());

        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_edge WHERE experiment_id = 'CI-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_event WHERE experiment_id = 'CI-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM closure_version WHERE experiment_id = 'CI-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'ci1-key'",
                Integer.class));
    }

    @Test
    void disclosure_sameKeyDifferentParams_conflicts_andKeyStillReplaysOriginal() throws Exception {
        approvedParticipant("CI-2", "ci2");

        ResponseEntity<String> first = post(
                "/api/experiments/CI-2/participants/PA/disclosures",
                headers("coord-1", "COORDINATOR", "ci2-key"),
                "{\"exposureKey\":\"CI2-EX\",\"targetActorIds\":[\"op-a\"]}");
        assertEquals(201, first.getStatusCode().value());

        // 异参：换 exposureKey → 409
        assertEquals(409, post(
                "/api/experiments/CI-2/participants/PA/disclosures",
                headers("coord-1", "COORDINATOR", "ci2-key"),
                "{\"exposureKey\":\"CI2-EX-OTHER\",\"targetActorIds\":[\"op-a\"]}")
                .getStatusCode().value());
        // 异参：换接收人集合 → 409
        assertEquals(409, post(
                "/api/experiments/CI-2/participants/PA/disclosures",
                headers("coord-1", "COORDINATOR", "ci2-key"),
                "{\"exposureKey\":\"CI2-EX\",\"targetActorIds\":[\"op-b\"]}")
                .getStatusCode().value());
        // 异操作者 → 409
        assertEquals(409, post(
                "/api/experiments/CI-2/participants/PA/disclosures",
                headers("coord-9", "COORDINATOR", "ci2-key"),
                "{\"exposureKey\":\"CI2-EX\",\"targetActorIds\":[\"op-a\"]}")
                .getStatusCode().value());

        // 原键原参仍可回放，且只产生一条边。
        ResponseEntity<String> replay = post(
                "/api/experiments/CI-2/participants/PA/disclosures",
                headers("coord-1", "COORDINATOR", "ci2-key"),
                "{\"exposureKey\":\"CI2-EX\",\"targetActorIds\":[\"op-a\"]}");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_edge WHERE experiment_id = 'CI-2'", Integer.class));
    }

    @Test
    void failedDisclosure_doesNotConsumeKey_andExposureKeyAlsoReleased() {
        // 未揭盲先登记：403，失败不占 requestId 也不占 exposureKey。
        assertEquals(201, post("/api/experiments/CI-3",
                headers("coord-1", "COORDINATOR", "ci3-create"), "{\"blockCount\":2}")
                .getStatusCode().value());
        assertEquals(201, post("/api/experiments/CI-3/participants/PA/allocations",
                headers("coord-1", "COORDINATOR", "ci3-alloc"), null)
                .getStatusCode().value());
        assertEquals(403, post(
                "/api/experiments/CI-3/participants/PA/disclosures",
                headers("coord-1", "COORDINATOR", "ci3-fail"),
                "{\"exposureKey\":\"CI3-EX\",\"targetActorIds\":[\"op-a\"]}")
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'ci3-fail'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_event WHERE exposure_key = 'CI3-EX'",
                Integer.class));
    }

    @Test
    void quarantineWrites_areIdempotent_reorderedActorsReplay_andConfirmReplays200() throws Exception {
        approvedParticipant("CI-4", "ci4");
        assertEquals(201, post(
                "/api/experiments/CI-4/participants/PA/disclosures",
                headers("coord-1", "COORDINATOR", "ci4-disc"),
                "{\"exposureKey\":\"CI4-EX\",\"targetActorIds\":[\"op-a\"]}")
                .getStatusCode().value());

        // 发起隔离：同键换序重放原 201。
        ResponseEntity<String> init1 = post(
                "/api/experiments/CI-4/participants/PA/quarantine-orders",
                headers("comp-1", "COMPLIANCE", "ci4-init"),
                "{\"versionNo\":1,\"actors\":[\"coord-1\",\"op-a\"]}");
        assertEquals(201, init1.getStatusCode().value());
        ResponseEntity<String> initReplay = post(
                "/api/experiments/CI-4/participants/PA/quarantine-orders",
                headers("comp-1", "COMPLIANCE", "ci4-init"),
                "{\"actors\":[\"op-a\",\"coord-1\"],\"versionNo\":1}");
        assertEquals(201, initReplay.getStatusCode().value());
        assertEquals(init1.getBody(), initReplay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM quarantine_order WHERE experiment_id = 'CI-4'",
                Integer.class));

        // 异参（不同版本号）：409。
        assertEquals(409, post(
                "/api/experiments/CI-4/participants/PA/quarantine-orders",
                headers("comp-1", "COMPLIANCE", "ci4-init"),
                "{\"versionNo\":2,\"actors\":[\"coord-1\",\"op-a\"]}")
                .getStatusCode().value());

        String orderId = json(init1).path("orderId").asText();

        // 确认：首次 200；同键重放仍返回原 200 CONFIRMED（而非重复确认 409）。
        ResponseEntity<String> confirm1 = post(
                "/api/quarantine-orders/" + orderId + "/confirmation",
                headers("comp-2", "COMPLIANCE", "ci4-confirm"), null);
        assertEquals(200, confirm1.getStatusCode().value());
        ResponseEntity<String> confirmReplay = post(
                "/api/quarantine-orders/" + orderId + "/confirmation",
                headers("comp-2", "COMPLIANCE", "ci4-confirm"), null);
        assertEquals(200, confirmReplay.getStatusCode().value());
        assertEquals(confirm1.getBody(), confirmReplay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM quarantine_order WHERE id = ? AND status = 'CONFIRMED' "
                        + "AND confirmer_actor = 'comp-2'", Integer.class, orderId));
    }
}
