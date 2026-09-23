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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 泄露与隔离写操作的幂等边界（真实 H2 数据库）：
 * 同 requestId 同参集合换序重放原成功结果；异参 409；业务失败不占键；
 * exposureKey 全局唯一且与 requestId 相互独立；同键跨操作复用 409。
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
        assertNotNull(response.getBody());
        return mapper.readTree(response.getBody());
    }

    private void seedApproved(String expId, String pid) {
        assertEquals(201, post("/api/experiments/" + expId,
                headers("coord-1", "COORDINATOR", expId.toLowerCase() + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
        assertEquals(201, post(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                headers("coord-1", "COORDINATOR", expId.toLowerCase() + "-alloc"), null)
                .getStatusCode().value());
        ResponseEntity<String> apply = post(
                "/api/experiments/" + expId + "/participants/" + pid + "/unblind-requests",
                headers("coord-1", "COORDINATOR", expId.toLowerCase() + "-apply"),
                "{\"reason\":\"x\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId;
        try {
            ubId = json(apply).path("requestId").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals(200, post("/api/unblind-requests/" + ubId + "/approval",
                headers("rev-2", "REVIEWER", expId.toLowerCase() + "-approve"), null)
                .getStatusCode().value());
    }

    @Test
    void disclosureReplay_sameSetDifferentOrder_returnsOriginal_andNoExtraEdges() throws Exception {
        seedApproved("ID-1", "PA");

        String body1 = "{\"exposureKey\":\"EK-ID-1\",\"receiverActors\":[\"op-4\",\"op-3\"],"
                + "\"participantIds\":[\"PA\"]}";
        ResponseEntity<String> first = post("/api/experiments/ID-1/disclosures",
                headers("coord-1", "COORDINATOR", "idem-disc-1"), body1);
        assertEquals(201, first.getStatusCode().value());
        assertEquals(2, json(first).path("newEdges").asInt());

        // 同键同参集合换序（接收人顺序 + 数组写法）：重放原结果，不重复执行、不新增边
        String bodyReordered = "{\"exposureKey\":\"EK-ID-1\",\"receiverActors\":[\"op-3\",\"op-4\",\"op-3\"],"
                + "\"participantIds\":[\"PA\"]}";
        ResponseEntity<String> replay = post("/api/experiments/ID-1/disclosures",
                headers("coord-1", "COORDINATOR", "idem-disc-1"), bodyReordered);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody(), "换序重放必须返回原始响应体");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM contamination_edge WHERE experiment_id='ID-1' "
                        + "AND participant_id='PA' AND actor_id IN ('op-3','op-4')",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_event WHERE exposure_key='EK-ID-1'",
                Integer.class));

        // 同键异参（换 exposureKey）：409
        ResponseEntity<String> differentKey = post("/api/experiments/ID-1/disclosures",
                headers("coord-1", "COORDINATOR", "idem-disc-1"),
                "{\"exposureKey\":\"EK-OTHER\",\"receiverActors\":[\"op-3\"],"
                        + "\"participantIds\":[\"PA\"]}");
        assertEquals(409, differentKey.getStatusCode().value());

        // 同键异参（换接收人集合）：409
        ResponseEntity<String> differentReceivers = post("/api/experiments/ID-1/disclosures",
                headers("coord-1", "COORDINATOR", "idem-disc-1"),
                "{\"exposureKey\":\"EK-ID-1\",\"receiverActors\":[\"op-5\"],"
                        + "\"participantIds\":[\"PA\"]}");
        assertEquals(409, differentReceivers.getStatusCode().value());

        // 同键被其他操作者重放：409（幂等参数含操作者）
        assertEquals(409, post("/api/experiments/ID-1/disclosures",
                headers("coord-2", "COORDINATOR", "idem-disc-1"), body1)
                .getStatusCode().value());
    }

    @Test
    void failedDisclosure_doesNotConsumeKey_orExposureKey() {
        seedApproved("ID-2", "PA");

        // 登记人未获知 PA？coord-1 已通过揭盲获知；改用未获知的参与者制造业务失败
        assertEquals(201, post("/api/experiments/ID-2/participants/PB/allocations",
                headers("coord-1", "COORDINATOR", "id2-alloc-pb"), null)
                .getStatusCode().value());
        ResponseEntity<String> failed = post("/api/experiments/ID-2/disclosures",
                headers("coord-1", "COORDINATOR", "idem-disc-fail"),
                "{\"exposureKey\":\"EK-FAIL\",\"receiverActors\":[\"op-3\"],"
                        + "\"participantIds\":[\"PB\"]}");
        assertEquals(403, failed.getStatusCode().value());
        // 失败不占 requestId
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id='idem-disc-fail'",
                Integer.class));
        // 失败不占 exposureKey（事件未写入）
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_event WHERE exposure_key='EK-FAIL'",
                Integer.class));

        // 同一 requestId 换合法参数后成功
        ResponseEntity<String> success = post("/api/experiments/ID-2/disclosures",
                headers("coord-1", "COORDINATOR", "idem-disc-fail"),
                "{\"exposureKey\":\"EK-FAIL\",\"receiverActors\":[\"op-3\"],"
                        + "\"participantIds\":[\"PA\"]}");
        assertEquals(201, success.getStatusCode().value());

        // 全新 requestId 复用 exposureKey：仍因 exposureKey 唯一而 409
        ResponseEntity<String> reusedExposure = post("/api/experiments/ID-2/disclosures",
                headers("coord-1", "COORDINATOR", "idem-disc-newkey"),
                "{\"exposureKey\":\"EK-FAIL\",\"receiverActors\":[\"op-4\"],"
                        + "\"participantIds\":[\"PA\"]}");
        assertEquals(409, reusedExposure.getStatusCode().value());
    }

    @Test
    void quarantineCreateAndConfirm_areIdempotent() throws Exception {
        seedApproved("ID-3", "PA");
        assertEquals(201, post("/api/experiments/ID-3/disclosures",
                headers("coord-1", "COORDINATOR", "id3-disc"),
                "{\"exposureKey\":\"EK-ID-3\",\"receiverActors\":[\"op-3\"],"
                        + "\"participantIds\":[\"PA\"]}").getStatusCode().value());

        // 发起：同键同参集合换序重放，返回同一隔离单，不产生第二条
        String createBody = "{\"version\":2,\"actors\":[\"op-3\",\"coord-1\"]}";
        ResponseEntity<String> created = post(
                "/api/experiments/ID-3/participants/PA/quarantine-orders",
                headers("comp-1", "COMPLIANCE", "idem-qo-create"), createBody);
        assertEquals(201, created.getStatusCode().value());
        ResponseEntity<String> replay = post(
                "/api/experiments/ID-3/participants/PA/quarantine-orders",
                headers("comp-1", "COMPLIANCE", "idem-qo-create"),
                "{\"version\":2,\"actors\":[\"coord-1\",\"op-3\"]}");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(created.getBody(), replay.getBody());
        String orderId = json(created).path("orderId").asText();
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM quarantine_order WHERE experiment_id='ID-3' "
                        + "AND participant_id='PA'", Integer.class));

        // 同键异参（版本号）：409
        assertEquals(409, post(
                "/api/experiments/ID-3/participants/PA/quarantine-orders",
                headers("comp-1", "COMPLIANCE", "idem-qo-create"),
                "{\"version\":1,\"actors\":[\"coord-1\",\"op-3\"]}")
                .getStatusCode().value());

        // 确认：同键重放原 200 CLOSED，而非重复确认 409
        ResponseEntity<String> confirmed = post(
                "/api/quarantine-orders/" + orderId + "/confirmation",
                headers("comp-2", "COMPLIANCE", "idem-qo-confirm"), null);
        assertEquals(200, confirmed.getStatusCode().value());
        ResponseEntity<String> confirmReplay = post(
                "/api/quarantine-orders/" + orderId + "/confirmation",
                headers("comp-2", "COMPLIANCE", "idem-qo-confirm"), null);
        assertEquals(200, confirmReplay.getStatusCode().value());
        assertEquals(confirmed.getBody(), confirmReplay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM quarantine_order WHERE id=? AND status='CLOSED' "
                        + "AND confirmer_actor='comp-2'", Integer.class, orderId));
    }

    @Test
    void disclosureRequestId_cannotBeReusedForAnotherOperation() {
        seedApproved("ID-4", "PA");
        // 先用该 requestId 做披露
        assertEquals(201, post("/api/experiments/ID-4/disclosures",
                headers("coord-1", "COORDINATOR", "shared-key"),
                "{\"exposureKey\":\"EK-ID-4\",\"receiverActors\":[\"op-3\"],"
                        + "\"participantIds\":[\"PA\"]}").getStatusCode().value());
        // 同一 requestId 用于关闭实验（不同 operation）：409
        assertEquals(409, post("/api/experiments/ID-4/close",
                headers("coord-1", "COORDINATOR", "shared-key"), null)
                .getStatusCode().value());
    }
}
