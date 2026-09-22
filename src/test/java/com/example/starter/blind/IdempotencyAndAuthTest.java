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
 * 幂等与鉴权边界：
 * 同键同参重放原结果；异参/异操作者 409；失败不占键可换键成功；
 * 权限校验先于幂等回放；缺头 401。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyAndAuthTest extends AbstractBlindIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpHeaders headers(String actor, String role, String requestId) {
        HttpHeaders h = new HttpHeaders();
        if (actor != null) {
            h.set("X-Actor-Id", actor);
        }
        if (role != null) {
            h.set("X-Role", role);
        }
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

    @Test
    void replaySameKeySameParams_returnsOriginalResult_andNoDuplicateBusinessRow() throws Exception {
        ResponseEntity<String> first = post("/api/experiments/IDEM-1",
                headers("c1", "COORDINATOR", "idem-key-1"), "{\"blockCount\":3}");
        assertEquals(201, first.getStatusCode().value());
        JsonNode firstBody = json(first);
        assertEquals(3, firstBody.path("blockCount").asInt());

        // 同键同参同操作者重放：原结果（201 与相同响应体），不产生第二个实验
        ResponseEntity<String> replay = post("/api/experiments/IDEM-1",
                headers("c1", "COORDINATOR", "idem-key-1"), "{\"blockCount\":3}");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());

        // 不带 requestId 的同业务请求会命中“实验已存在”409，证明重放不是重新执行业务
        ResponseEntity<String> noIdem = post("/api/experiments/IDEM-1",
                headers("c1", "COORDINATOR", "idem-key-other"), "{\"blockCount\":3}");
        assertEquals(409, noIdem.getStatusCode().value());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM experiment WHERE id = 'IDEM-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'idem-key-1'",
                Integer.class));
    }

    @Test
    void sameKeyDifferentParamsOrActor_conflicts() {
        assertEquals(201, post("/api/experiments/IDEM-2",
                headers("c1", "COORDINATOR", "idem-key-2"), "{\"blockCount\":2}")
                .getStatusCode().value());

        // 异参（不同 blockCount）：409
        assertEquals(409, post("/api/experiments/IDEM-2",
                headers("c1", "COORDINATOR", "idem-key-2"), "{\"blockCount\":4}")
                .getStatusCode().value());

        // 异操作者：409
        assertEquals(409, post("/api/experiments/IDEM-2",
                headers("c2", "COORDINATOR", "idem-key-2"), "{\"blockCount\":2}")
                .getStatusCode().value());

        // 异角色（同一 actorId 换 REVIEWER）：权限校验先于幂等回放，直接 403
        assertEquals(403, post("/api/experiments/IDEM-2",
                headers("c1", "REVIEWER", "idem-key-2"), "{\"blockCount\":2}")
                .getStatusCode().value());
    }

    @Test
    void failedRequest_doesNotConsumeKey() {
        // 非法 blockCount：400，失败不占键
        ResponseEntity<String> failed = post("/api/experiments/IDEM-3",
                headers("c1", "COORDINATOR", "idem-key-3"), "{\"blockCount\":1}");
        assertEquals(400, failed.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'idem-key-3'",
                Integer.class));

        // 同一 requestId 换成合法参数：成功，证明失败未占键
        ResponseEntity<String> success = post("/api/experiments/IDEM-3",
                headers("c1", "COORDINATOR", "idem-key-3"), "{\"blockCount\":2}");
        assertEquals(201, success.getStatusCode().value());

        // 业务冲突失败（重复创建）也不占新键：先制造已存在实验，再用新键冲突，
        // 再用该键创建另一个实验应成功。
        ResponseEntity<String> conflict = post("/api/experiments/IDEM-3",
                headers("c1", "COORDINATOR", "idem-key-4"), "{\"blockCount\":2}");
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'idem-key-4'",
                Integer.class));
        assertEquals(201, post("/api/experiments/IDEM-4",
                headers("c1", "COORDINATOR", "idem-key-4"), "{\"blockCount\":2}")
                .getStatusCode().value());
    }

    @Test
    void missingRequestId_isBadRequest() {
        ResponseEntity<String> resp = post("/api/experiments/IDEM-5",
                headers("c1", "COORDINATOR", null), "{\"blockCount\":2}");
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void overlongIdentifiers_areBadRequest() {
        String longRequestId = "x".repeat(65);
        assertEquals(400, post("/api/experiments/IDEM-5",
                headers("c1", "COORDINATOR", longRequestId), "{\"blockCount\":2}")
                .getStatusCode().value());
        // 超长 experimentId（路径段）返回 400，不触达数据库
        String longExperimentId = "E".repeat(65);
        assertEquals(400, post("/api/experiments/" + longExperimentId,
                headers("c1", "COORDINATOR", "req-long-exp"), "{\"blockCount\":2}")
                .getStatusCode().value());
    }

    @Test
    void authHeaders_missingOrBadRole_unauthorized_andPrecedesIdempotency() {
        // 无任何身份头：401，不触达幂等表
        ResponseEntity<String> noHeaders = post("/api/experiments/IDEM-6",
                headers(null, null, "idem-key-6"), "{\"blockCount\":2}");
        assertEquals(401, noHeaders.getStatusCode().value());

        // 非法角色：401
        ResponseEntity<String> badRole = post("/api/experiments/IDEM-6",
                headers("c1", "ADMIN", "idem-key-6"), "{\"blockCount\":2}");
        assertEquals(401, badRole.getStatusCode().value());

        // 先用合法协调员成功占用 requestId
        assertEquals(201, post("/api/experiments/IDEM-6",
                headers("c1", "COORDINATOR", "idem-key-6"), "{\"blockCount\":2}")
                .getStatusCode().value());

        // 权限不足（REVIEWER 做协调员操作）必须 403，而非按幂等回放/409——权限先于幂等
        ResponseEntity<String> reviewerReplay = post("/api/experiments/IDEM-6",
                headers("c1", "REVIEWER", "idem-key-6"), "{\"blockCount\":2}");
        assertEquals(403, reviewerReplay.getStatusCode().value());

        // 缺身份头同样 401，而不是回放
        assertEquals(401, post("/api/experiments/IDEM-6",
                headers(null, null, "idem-key-6"), "{\"blockCount\":2}")
                .getStatusCode().value());
    }

    @Test
    void allocationWithdrawCloseWrites_areIdempotent_replayOriginalResult() {
        assertEquals(201, post("/api/experiments/IDEM-10",
                headers("c1", "COORDINATOR", "idem-10-create"), "{\"blockCount\":2}")
                .getStatusCode().value());

        // 登记：同键重放原 201（而非重复登记 409）
        ResponseEntity<String> alloc1 = post(
                "/api/experiments/IDEM-10/participants/P1/allocations",
                headers("c1", "COORDINATOR", "idem-10-alloc"), null);
        assertEquals(201, alloc1.getStatusCode().value());
        ResponseEntity<String> allocReplay = post(
                "/api/experiments/IDEM-10/participants/P1/allocations",
                headers("c1", "COORDINATOR", "idem-10-alloc"), null);
        assertEquals(201, allocReplay.getStatusCode().value());
        assertEquals(alloc1.getBody(), allocReplay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'IDEM-10'", Integer.class));

        // 退组：同键重放原 200 与 WITHDRAWN（而非重复退组 409）
        ResponseEntity<String> withdraw1 = post(
                "/api/experiments/IDEM-10/participants/P1/withdrawal",
                headers("c1", "COORDINATOR", "idem-10-withdraw"), null);
        assertEquals(200, withdraw1.getStatusCode().value());
        ResponseEntity<String> withdrawReplay = post(
                "/api/experiments/IDEM-10/participants/P1/withdrawal",
                headers("c1", "COORDINATOR", "idem-10-withdraw"), null);
        assertEquals(200, withdrawReplay.getStatusCode().value());
        assertEquals(withdraw1.getBody(), withdrawReplay.getBody());

        // 关闭：同键重放原 200 与 CLOSED（而非重复关闭 409）
        ResponseEntity<String> close1 = post("/api/experiments/IDEM-10/close",
                headers("c1", "COORDINATOR", "idem-10-close"), null);
        assertEquals(200, close1.getStatusCode().value());
        ResponseEntity<String> closeReplay = post("/api/experiments/IDEM-10/close",
                headers("c1", "COORDINATOR", "idem-10-close"), null);
        assertEquals(200, closeReplay.getStatusCode().value());
        assertEquals(close1.getBody(), closeReplay.getBody());
    }

    @Test
    void reviewerCannotCreateOrAllocate_coordinatorCannotApprove() {
        assertEquals(201, post("/api/experiments/IDEM-7",
                headers("c1", "COORDINATOR", "idem-key-7"), "{\"blockCount\":2}")
                .getStatusCode().value());

        assertEquals(403, post("/api/experiments/IDEM-8",
                headers("r1", "REVIEWER", "idem-key-8"), "{\"blockCount\":2}")
                .getStatusCode().value());
        assertEquals(403, post(
                "/api/experiments/IDEM-7/participants/P1/allocations",
                headers("r1", "REVIEWER", "idem-key-9"), null)
                .getStatusCode().value());
    }
}
