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
 * 审核隔离门禁（真实 H2）：
 * 揭盲申请的申请人、审核人一旦在目标参与者污染闭包内，就不能作为该申请的新审核人；
 * 但已是申请人身份不受影响，且已批准结果仍可由原申请人读取。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReviewerGateTest extends AbstractBlindIntegrationTest {

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

    private ResponseEntity<String> exchange(String path, HttpMethod method,
                                            HttpHeaders headers, String body) {
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertTrue(response.getBody() != null && !response.getBody().isBlank());
        return mapper.readTree(response.getBody());
    }

    private void setup(String expId, String prefix) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("coord-1", "COORDINATOR", prefix + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/PA/allocations",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", prefix + "-alloc"), null)
                .getStatusCode().value());
    }

    private String apply(String expId, String applicant, String prefix) throws Exception {
        ResponseEntity<String> apply = exchange(
                "/api/experiments/" + expId + "/participants/PA/unblind-requests",
                HttpMethod.POST, headers(applicant, "COORDINATOR", prefix + "-apply"),
                "{\"reason\":\"合成紧急揭盲\"}");
        assertEquals(201, apply.getStatusCode().value());
        return json(apply).path("requestId").asText();
    }

    private void disclose(String expId, String source, String role, String requestId,
                          String exposureKey, String targets) {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/participants/PA/disclosures",
                HttpMethod.POST, headers(source, role, requestId),
                "{\"exposureKey\":\"" + exposureKey + "\",\"targetActorIds\":" + targets + "}");
        assertEquals(201, resp.getStatusCode().value());
    }

    @Test
    void contaminatedReviewer_cannotApprove_cleanReviewerCan() throws Exception {
        setup("G-1", "g1");
        String ubId = apply("G-1", "coord-1", "g1");
        // 先批准，使 coord-1 获知代码。
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-clean", "REVIEWER", "g1-approve"), null)
                .getStatusCode().value());

        // coord-1 把代码直接披露给 rev-dirty（该人以 REVIEWER 身份接收）。
        disclose("G-1", "coord-1", "COORDINATOR", "g1-disc", "GK-1", "[\"rev-dirty\"]");

        // 第二个协调员对同一参与者提出新揭盲申请。
        String ubId2 = apply("G-1", "coord-2", "g1b");

        // 已在闭包内的 rev-dirty 不能审核新申请：403。
        ResponseEntity<String> blocked = exchange(
                "/api/unblind-requests/" + ubId2 + "/approval", HttpMethod.POST,
                headers("rev-dirty", "REVIEWER", "g1-approve-dirty"), null);
        assertEquals(403, blocked.getStatusCode().value());
        assertTrue(blocked.getBody().contains("污染闭包"));
        // 申请仍为 PENDING。
        assertEquals("PENDING", json(exchange("/api/unblind-requests/" + ubId2,
                HttpMethod.GET, headers("coord-2", "COORDINATOR", null), null))
                .path("status").asText());

        // 不在闭包的 rev-clean2 可以批准。
        assertEquals(200, exchange("/api/unblind-requests/" + ubId2 + "/approval",
                HttpMethod.POST, headers("rev-clean2", "REVIEWER", "g1-approve-clean"), null)
                .getStatusCode().value());

        // 原申请人 coord-1 的已批准结果不受影响，仍可读取处理代码。
        ResponseEntity<String> result = exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, result.getStatusCode().value());
        assertTrue(result.getBody().contains("\"treatment\""));
    }

    @Test
    void applicantWhoIsAlsoContaminated_isNotBlocked_fromTheirOwnApplication() throws Exception {
        // 场景：rev-dirty 先因披露链进入 PA 闭包；coord-1 申请揭盲 PA。
        // coord-1 自身在批准后才进入闭包，门禁只拦截“审核人”，申请人提交申请不受影响。
        setup("G-2", "g2");
        String firstUb = apply("G-2", "coord-root", "g2");
        assertEquals(200, exchange("/api/unblind-requests/" + firstUb + "/approval",
                HttpMethod.POST, headers("rev-a", "REVIEWER", "g2-approve-a"), null)
                .getStatusCode().value());
        disclose("G-2", "coord-root", "COORDINATOR", "g2-disc-1", "GK-2", "[\"op-x\"]");
        disclose("G-2", "op-x", "REVIEWER", "g2-disc-2", "GK-3", "[\"rev-dirty\"]");

        // 新申请：coord-new 尚未污染，可正常提出。
        String ubId = apply("G-2", "coord-new", "g2b");
        // rev-dirty 在闭包内：拒绝。
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-dirty", "REVIEWER", "g2-dirty-approval"), null)
                .getStatusCode().value());
        // rev-a 也在闭包外（审批人不因批准进入闭包——只有申请人才获知代码）。
        // 审核人批准揭盲不等于获知处理代码，因此 rev-a 仍可作为新审核人。
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("rev-a", "REVIEWER", "g2-clean-approval"), null)
                .getStatusCode().value());
    }
}
