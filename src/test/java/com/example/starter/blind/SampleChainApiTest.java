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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盲态样本链主流程与拒绝分支：
 * 先传 POST 形成待配对、时序非法锁定 422、更正后锁定、锁定后禁止更正、
 * 退组不能新锁定、样本编号全局唯一、分析人只见匿名配对。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SampleChainApiTest extends AbstractBlindIntegrationTest {

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
        assertNotNull(response.getBody());
        return mapper.readTree(response.getBody());
    }

    private void setupParticipant(String expId, String pid, String keyPrefix) {
        assertEquals(201, exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("coord-1", "COORDINATOR", keyPrefix + "-create"),
                "{\"blockCount\":2}").getStatusCode().value());
        assertEquals(201, exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", keyPrefix + "-alloc"), null)
                .getStatusCode().value());
    }

    private ResponseEntity<String> registerSample(String expId, String pid, String phase,
                                                  String code, String digest, long collectedAt,
                                                  String requestId) {
        String body = "{\"phase\":\"" + phase + "\",\"sampleCode\":\"" + code
                + "\",\"digest\":\"" + digest + "\",\"collectedAt\":" + collectedAt + "}";
        return exchange("/api/experiments/" + expId + "/participants/" + pid + "/samples",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", requestId), body);
    }

    private ResponseEntity<String> correctSample(String expId, String pid, String phase,
                                                 String code, String digest, long collectedAt,
                                                 String requestId) {
        String body = "{\"phase\":\"" + phase + "\",\"sampleCode\":\"" + code
                + "\",\"digest\":\"" + digest + "\",\"collectedAt\":" + collectedAt + "}";
        return exchange("/api/experiments/" + expId + "/participants/" + pid
                        + "/samples/corrections",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", requestId), body);
    }

    private ResponseEntity<String> lockPair(String expId, String pid, String body, String requestId) {
        return exchange("/api/experiments/" + expId + "/participants/" + pid + "/sample-pair/lock",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", requestId), body);
    }

    @Test
    void fullFlow_postFirstPendingThenLock_analystSeesOnlyAnonymousPair() throws Exception {
        setupParticipant("EXP-S1", "PA", "s1");

        // 允许先上传 POST 形成待配对状态
        ResponseEntity<String> post = registerSample("EXP-S1", "PA", "POST",
                "SC-POST-1", "digest-post-1", 1_700_000_020_000L, "s1-post");
        assertEquals(201, post.getStatusCode().value());
        JsonNode postNode = json(post);
        assertEquals("POST", postNode.path("phase").asText());
        assertEquals("ACTIVE", postNode.path("status").asText());
        assertEquals(1, postNode.path("chainVersion").asInt());
        assertTrue(postNode.path("supersedesId").isNull());

        ResponseEntity<String> pre = registerSample("EXP-S1", "PA", "PRE",
                "SC-PRE-1", "digest-pre-1", 1_700_000_010_000L, "s1-pre");
        assertEquals(201, pre.getStatusCode().value());

        ResponseEntity<String> locked = lockPair("EXP-S1", "PA",
                "{\"expectedPreVersion\":1,\"expectedPostVersion\":1}", "s1-lock");
        assertEquals(201, locked.getStatusCode().value());
        JsonNode pair = json(locked);
        String pairCode = pair.path("pairCode").asText();
        assertTrue(pairCode.matches("[A-Z2-9]{12}"), "配对码应为12位无含义随机码: " + pairCode);
        assertEquals("digest-pre-1", pair.path("preDigest").asText());
        assertEquals("digest-post-1", pair.path("postDigest").asText());
        assertEquals(1_700_000_000_000L, pair.path("lockedAt").asLong());

        // 分析人查询：只有配对码与两份冻结摘要
        ResponseEntity<String> analystView = exchange("/api/experiments/EXP-S1/sample-pairs",
                HttpMethod.GET, headers("analyst-1", "ANALYST", null), null);
        assertEquals(200, analystView.getStatusCode().value());
        JsonNode pairs = json(analystView);
        assertEquals(1, pairs.size());
        JsonNode first = pairs.get(0);
        assertEquals(3, first.size(), "匿名视图只能含配对码与两份摘要");
        assertEquals(pairCode, first.path("pairCode").asText());
        assertEquals("digest-pre-1", first.path("preDigest").asText());
        assertEquals("digest-post-1", first.path("postDigest").asText());
        assertFalse(first.has("participantId"));
        assertFalse(first.has("blindCode"));
        assertFalse(first.has("treatment"));

        // 协调员查询自己提交的记录：两条，含阶段与摘要
        ResponseEntity<String> own = exchange(
                "/api/experiments/EXP-S1/participants/PA/samples",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null);
        assertEquals(200, own.getStatusCode().value());
        assertEquals(2, json(own).size());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_pair WHERE experiment_id = 'EXP-S1'", Integer.class));
    }
    @Test
    void lockFails422WhenPostNotLaterThanPre_thenCorrectAndLockSucceeds() throws Exception {
        setupParticipant("EXP-S2", "PA", "s2");
        assertEquals(201, registerSample("EXP-S2", "PA", "PRE",
                "SC2-PRE", "d-pre", 1_700_000_050_000L, "s2-pre").getStatusCode().value());
        assertEquals(201, registerSample("EXP-S2", "PA", "POST",
                "SC2-POST", "d-post-old", 1_700_000_010_000L, "s2-post").getStatusCode().value());

        // POST 早于 PRE：锁定 422
        ResponseEntity<String> early = lockPair("EXP-S2", "PA", null, "s2-lock-early");
        assertEquals(422, early.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_pair WHERE experiment_id = 'EXP-S2'", Integer.class));

        // 更正 POST 晚于 PRE：追加替代记录并引用最近有效样本
        long oldId = jdbc.queryForObject(
                "SELECT id FROM sample_record WHERE sample_code = 'SC2-POST'", Long.class);
        ResponseEntity<String> corrected = correctSample("EXP-S2", "PA", "POST",
                "SC2-POST-V2", "d-post-new", 1_700_000_090_000L, "s2-correct");
        assertEquals(201, corrected.getStatusCode().value());
        JsonNode node = json(corrected);
        assertEquals(2, node.path("chainVersion").asInt());
        assertEquals(oldId, node.path("supersedesId").asLong());
        assertEquals("ACTIVE", node.path("status").asText());
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT status FROM sample_record WHERE id = " + oldId, String.class));

        // 更正后锁定成功，冻结更正后的摘要
        ResponseEntity<String> locked = lockPair("EXP-S2", "PA", null, "s2-lock");
        assertEquals(201, locked.getStatusCode().value());
        assertEquals("d-post-new", json(locked).path("postDigest").asText());
        assertEquals("d-post-new", jdbc.queryForObject(
                "SELECT post_digest FROM sample_pair WHERE experiment_id = 'EXP-S2'", String.class));
    }

    @Test
    void lockRequiresBothSamples_andWithdrawnParticipantCannotLock() {
        setupParticipant("EXP-S3", "PA", "s3");
        // 只有 PRE：两份未齐全 409
        assertEquals(201, registerSample("EXP-S3", "PA", "PRE",
                "SC3-PRE", "d-pre", 1_700_000_010_000L, "s3-pre").getStatusCode().value());
        assertEquals(409, lockPair("EXP-S3", "PA", null, "s3-lock-incomplete")
                .getStatusCode().value());

        assertEquals(201, registerSample("EXP-S3", "PA", "POST",
                "SC3-POST", "d-post", 1_700_000_020_000L, "s3-post").getStatusCode().value());

        // 退组后不能新锁定
        assertEquals(200, exchange("/api/experiments/EXP-S3/participants/PA/withdrawal",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "s3-withdraw"), null)
                .getStatusCode().value());
        assertEquals(409, lockPair("EXP-S3", "PA", null, "s3-lock-withdrawn")
                .getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_pair WHERE experiment_id = 'EXP-S3'", Integer.class));
    }

    @Test
    void correctionForbiddenAfterLock_andCloseKeepsLockedPair() throws Exception {
        setupParticipant("EXP-S4", "PA", "s4");
        assertEquals(201, registerSample("EXP-S4", "PA", "PRE",
                "SC4-PRE", "d-pre", 1_700_000_010_000L, "s4-pre").getStatusCode().value());
        assertEquals(201, registerSample("EXP-S4", "PA", "POST",
                "SC4-POST", "d-post", 1_700_000_020_000L, "s4-post").getStatusCode().value());
        assertEquals(201, lockPair("EXP-S4", "PA", null, "s4-lock").getStatusCode().value());

        // 锁定后禁止更正
        assertEquals(409, correctSample("EXP-S4", "PA", "PRE",
                "SC4-PRE-V2", "d-pre-2", 1_700_000_015_000L, "s4-correct-after-lock")
                .getStatusCode().value());
        // 重复锁定 409
        assertEquals(409, lockPair("EXP-S4", "PA", null, "s4-lock-again").getStatusCode().value());

        // 试验关闭不撤销已锁定对
        assertEquals(200, exchange("/api/experiments/EXP-S4/close", HttpMethod.POST,
                headers("coord-1", "COORDINATOR", "s4-close"), null).getStatusCode().value());
        ResponseEntity<String> pairs = exchange("/api/experiments/EXP-S4/sample-pairs",
                HttpMethod.GET, headers("analyst-1", "ANALYST", null), null);
        assertEquals(200, pairs.getStatusCode().value());
        assertEquals(1, json(pairs).size());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_pair WHERE experiment_id = 'EXP-S4'", Integer.class));
    }

    @Test
    void sampleCodeGloballyUnique_andSecondActiveSampleRejected() {
        setupParticipant("EXP-S5", "PA", "s5a");
        assertEquals(201, exchange(
                "/api/experiments/EXP-S5/participants/PB/allocations",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "s5-alloc-pb"), null)
                .getStatusCode().value());
        assertEquals(201, registerSample("EXP-S5", "PA", "PRE",
                "SC5-1", "d1", 1_700_000_010_000L, "s5-pre").getStatusCode().value());

        // 同一编号不得用于不同参与者
        assertEquals(409, registerSample("EXP-S5", "PB", "PRE",
                "SC5-1", "d2", 1_700_000_010_000L, "s5-pb-same-code").getStatusCode().value());
        // 同一编号不得用于不同阶段
        assertEquals(409, registerSample("EXP-S5", "PA", "POST",
                "SC5-1", "d3", 1_700_000_020_000L, "s5-post-same-code").getStatusCode().value());
        // 同阶段已有有效样本：须走更正
        assertEquals(409, registerSample("EXP-S5", "PA", "PRE",
                "SC5-2", "d4", 1_700_000_011_000L, "s5-pre-again").getStatusCode().value());
        // 无有效样本可更正 409
        assertEquals(409, correctSample("EXP-S5", "PA", "POST",
                "SC5-3", "d5", 1_700_000_021_000L, "s5-correct-none").getStatusCode().value());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE experiment_id = 'EXP-S5'", Integer.class));
    }
    @Test
    void analystAndReviewerAccessRules_errorDoesNotLeakParticipants() throws Exception {
        setupParticipant("EXP-S6", "PA", "s6");
        assertEquals(201, registerSample("EXP-S6", "PA", "PRE",
                "SC6-PRE", "d-pre", 1_700_000_010_000L, "s6-pre").getStatusCode().value());

        // 分析人不能写样本、不能锁定、不能查参与者级记录
        String sampleBody = "{\"phase\":\"POST\",\"sampleCode\":\"SC6-POST\","
                + "\"digest\":\"d-post\",\"collectedAt\":1700000020000}";
        assertEquals(403, exchange("/api/experiments/EXP-S6/participants/PA/samples",
                HttpMethod.POST, headers("analyst-1", "ANALYST", "s6-analyst-reg"), sampleBody)
                .getStatusCode().value());
        assertEquals(403, exchange("/api/experiments/EXP-S6/participants/PA/sample-pair/lock",
                HttpMethod.POST, headers("analyst-1", "ANALYST", "s6-analyst-lock"), null)
                .getStatusCode().value());
        assertEquals(403, exchange("/api/experiments/EXP-S6/participants/PA/samples",
                HttpMethod.GET, headers("analyst-1", "ANALYST", null), null)
                .getStatusCode().value());
        // 分析人不能读参与者编号与盲码：普通分配视图同样拒绝
        assertEquals(403, exchange("/api/experiments/EXP-S6/participants/PA",
                HttpMethod.GET, headers("analyst-1", "ANALYST", null), null)
                .getStatusCode().value());
        assertEquals(403, exchange("/api/experiments/EXP-S6",
                HttpMethod.GET, headers("analyst-1", "ANALYST", null), null)
                .getStatusCode().value());

        // 错误详情不泄露参与者是否存在：存在与不存在都是同样的 403
        ResponseEntity<String> existing = exchange(
                "/api/experiments/EXP-S6/participants/PA/samples",
                HttpMethod.GET, headers("analyst-1", "ANALYST", null), null);
        ResponseEntity<String> missing = exchange(
                "/api/experiments/EXP-S6/participants/NOPE/samples",
                HttpMethod.GET, headers("analyst-1", "ANALYST", null), null);
        assertEquals(existing.getStatusCode().value(), missing.getStatusCode().value());
        assertEquals(json(existing).path("message").asText(), json(missing).path("message").asText());

        // REVIEWER 不能查匿名配对；ANALYST 与 COORDINATOR 可以
        assertEquals(403, exchange("/api/experiments/EXP-S6/sample-pairs",
                HttpMethod.GET, headers("rev-1", "REVIEWER", null), null)
                .getStatusCode().value());
        assertEquals(200, exchange("/api/experiments/EXP-S6/sample-pairs",
                HttpMethod.GET, headers("analyst-1", "ANALYST", null), null)
                .getStatusCode().value());
        assertEquals(200, exchange("/api/experiments/EXP-S6/sample-pairs",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value());
    }

    @Test
    void lockDoesNotGrantUnblindPermission() throws Exception {
        setupParticipant("EXP-S7", "PA", "s7");
        assertEquals(201, registerSample("EXP-S7", "PA", "PRE",
                "SC7-PRE", "d-pre", 1_700_000_010_000L, "s7-pre").getStatusCode().value());
        assertEquals(201, registerSample("EXP-S7", "PA", "POST",
                "SC7-POST", "d-post", 1_700_000_020_000L, "s7-post").getStatusCode().value());
        assertEquals(201, lockPair("EXP-S7", "PA", null, "s7-lock").getStatusCode().value());

        // 锁定后揭盲仍走原受控流程：申请后未批准查询结果 409
        ResponseEntity<String> apply = exchange(
                "/api/experiments/EXP-S7/participants/PA/unblind-requests",
                HttpMethod.POST, headers("coord-1", "COORDINATOR", "s7-ub-apply"),
                "{\"reason\":\"AE follow-up\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        assertEquals(409, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("coord-1", "COORDINATOR", null), null)
                .getStatusCode().value());
        // 分析人不能查询揭盲申请与结果
        assertEquals(403, exchange("/api/unblind-requests/" + ubId,
                HttpMethod.GET, headers("analyst-1", "ANALYST", null), null)
                .getStatusCode().value());
        assertEquals(403, exchange("/api/unblind-requests/" + ubId + "/result",
                HttpMethod.GET, headers("analyst-1", "ANALYST", null), null)
                .getStatusCode().value());
        // 匿名配对视图不含处理代码
        ResponseEntity<String> pairs = exchange("/api/experiments/EXP-S7/sample-pairs",
                HttpMethod.GET, headers("analyst-1", "ANALYST", null), null);
        assertFalse(json(pairs).get(0).has("treatment"));
    }
}