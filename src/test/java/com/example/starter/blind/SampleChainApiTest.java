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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盲态样本链主流程与拒绝分支：登记/更正/锁定/分析视图/权限边界。
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
        return mapper.readTree(response.getBody());
    }

    private void createExperiment(String exp, String reqId) {
        assertEquals(201, exchange("/api/experiments/" + exp, HttpMethod.POST,
                headers("c1", "COORDINATOR", reqId), "{\"blockCount\":2}").getStatusCode().value());
    }

    private void register(String exp, String pid, String reqId) {
        assertEquals(201, exchange(
                "/api/experiments/" + exp + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", reqId), null)
                .getStatusCode().value());
    }

    private ResponseEntity<String> registerSample(String exp, String pid, String actor,
                                                  String reqId, String sampleNo, String phase,
                                                  String digest, long collectedAt) {
        String body = "{\"sampleNo\":\"" + sampleNo + "\",\"phase\":\"" + phase
                + "\",\"digest\":\"" + digest + "\",\"collectedAt\":" + collectedAt + "}";
        return exchange("/api/experiments/" + exp + "/participants/" + pid + "/samples",
                HttpMethod.POST, headers(actor, "COORDINATOR", reqId), body);
    }

    private ResponseEntity<String> lock(String exp, String pid, String actor,
                                        String reqId, int expectedVersion) {
        return exchange("/api/experiments/" + exp + "/participants/" + pid + "/sample-lock",
                HttpMethod.POST, headers(actor, "COORDINATOR", reqId),
                "{\"expectedVersion\":" + expectedVersion + "}");
    }

    @Test
    void mainFlow_registerPrePost_lockThenAnalystSeesOnlyPairCodeAndDigests() throws Exception {
        createExperiment("EXP-S1", "s1-create");
        register("EXP-S1", "P1", "s1-alloc");

        assertEquals(201, registerSample("EXP-S1", "P1", "c1", "s1-pre",
                "SMP-PRE-1", "PRE", "digest-pre-1", 1_000L).getStatusCode().value());
        assertEquals(201, registerSample("EXP-S1", "P1", "c1", "s1-post",
                "SMP-POST-1", "POST", "digest-post-1", 2_000L).getStatusCode().value());

        ResponseEntity<String> lockResp = lock("EXP-S1", "P1", "c1", "s1-lock", 2);
        assertEquals(201, lockResp.getStatusCode().value());
        JsonNode lockBody = json(lockResp);
        String pairCode = lockBody.path("pairCode").asText();
        assertEquals(12, pairCode.length());
        assertEquals("digest-pre-1", lockBody.path("preDigest").asText());
        assertEquals("digest-post-1", lockBody.path("postDigest").asText());
        assertEquals(2, lockBody.path("version").asInt());
        assertEquals(1_700_000_000_000L, lockBody.path("lockedAt").asLong());

        // 分析人视图：只有配对码与两份冻结摘要
        ResponseEntity<String> analysis = exchange("/api/experiments/EXP-S1/analysis/pairs",
                HttpMethod.GET, headers("a1", "ANALYST", null), null);
        assertEquals(200, analysis.getStatusCode().value());
        JsonNode pairs = json(analysis);
        assertEquals(1, pairs.size());
        JsonNode pair = pairs.get(0);
        assertEquals(pairCode, pair.path("pairCode").asText());
        assertEquals("digest-pre-1", pair.path("preDigest").asText());
        assertEquals("digest-post-1", pair.path("postDigest").asText());
        assertFalse(pair.has("participantId"));
        assertFalse(pair.has("blindCode"));
        assertFalse(pair.has("treatment"));
        assertFalse(pair.has("collectedAt"));
        assertEquals(3, pair.size());
    }

    @Test
    void postUploadedFirst_pendingPairThenLockSucceeds() throws Exception {
        createExperiment("EXP-S2", "s2-create");
        register("EXP-S2", "P1", "s2-alloc");
        // 先上传 POST 形成待配对，再补 PRE（PRE 采集时刻更早）
        assertEquals(201, registerSample("EXP-S2", "P1", "c1", "s2-post",
                "SMP-POST-1", "POST", "digest-post", 2_000L).getStatusCode().value());
        assertEquals(422, lock("EXP-S2", "P1", "c1", "s2-lock-early", 1)
                .getStatusCode().value(), "只有一份样本时锁定 422");
        assertEquals(201, registerSample("EXP-S2", "P1", "c1", "s2-pre",
                "SMP-PRE-1", "PRE", "digest-pre", 1_000L).getStatusCode().value());
        assertEquals(201, lock("EXP-S2", "P1", "c1", "s2-lock", 2).getStatusCode().value());
    }

    @Test
    void postNotLaterThanPre_lockRejected422() throws Exception {
        createExperiment("EXP-S3", "s3-create");
        register("EXP-S3", "P1", "s3-alloc");
        registerSample("EXP-S3", "P1", "c1", "s3-pre", "SMP-PRE-1", "PRE", "d-pre", 2_000L);
        registerSample("EXP-S3", "P1", "c1", "s3-post", "SMP-POST-1", "POST", "d-post", 1_000L);
        assertEquals(422, lock("EXP-S3", "P1", "c1", "s3-lock", 2).getStatusCode().value(),
                "POST 早于 PRE 锁定 422");
        // 同时刻也不合法：必须严格晚于
        createExperiment("EXP-S3B", "s3b-create");
        register("EXP-S3B", "P1", "s3b-alloc");
        registerSample("EXP-S3B", "P1", "c1", "s3b-pre", "SMP-PRE-1", "PRE", "d-pre", 1_000L);
        registerSample("EXP-S3B", "P1", "c1", "s3b-post", "SMP-POST-1", "POST", "d-post", 1_000L);
        assertEquals(422, lock("EXP-S3B", "P1", "c1", "s3b-lock", 2).getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_pair_lock", Integer.class), "失败不产生锁定对");
    }
    @Test
    void correction_appendsReplacement_andLockFreezesCorrectedDigest() throws Exception {
        createExperiment("EXP-S4", "s4-create");
        register("EXP-S4", "P1", "s4-alloc");
        registerSample("EXP-S4", "P1", "c1", "s4-pre1", "SMP-PRE-1", "PRE", "d-pre-old", 1_000L);
        // 更正：追加替代记录并引用最近有效样本
        ResponseEntity<String> fix = registerSample("EXP-S4", "P1", "c1", "s4-pre2",
                "SMP-PRE-2", "PRE", "d-pre-new", 1_500L);
        assertEquals(201, fix.getStatusCode().value());
        assertEquals("SMP-PRE-1", json(fix).path("supersedesSampleNo").asText());
        registerSample("EXP-S4", "P1", "c1", "s4-post", "SMP-POST-1", "POST", "d-post", 2_000L);

        // 链视图：旧记录 SUPERSEDED，新记录 ACTIVE，版本为 3
        ResponseEntity<String> chain = exchange(
                "/api/experiments/EXP-S4/participants/P1/samples",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null);
        assertEquals(200, chain.getStatusCode().value());
        JsonNode chainBody = json(chain);
        assertEquals(3, chainBody.path("version").asInt());
        assertFalse(chainBody.path("locked").asBoolean());
        JsonNode records = chainBody.path("records");
        assertEquals(3, records.size());
        assertEquals("SUPERSEDED", records.get(0).path("status").asText());
        assertEquals("ACTIVE", records.get(1).path("status").asText());
        assertEquals("SMP-PRE-1", records.get(1).path("supersedesSampleNo").asText());

        // 锁定冻结更正后的摘要
        ResponseEntity<String> lockResp = lock("EXP-S4", "P1", "c1", "s4-lock", 3);
        assertEquals(201, lockResp.getStatusCode().value());
        assertEquals("d-pre-new", json(lockResp).path("preDigest").asText());

        // 锁定后禁止更正
        assertEquals(409, registerSample("EXP-S4", "P1", "c1", "s4-pre3",
                "SMP-PRE-3", "PRE", "d-pre-x", 1_600L).getStatusCode().value());
        // 分析视图仍是锁定时冻结的摘要
        JsonNode pairs = json(exchange("/api/experiments/EXP-S4/analysis/pairs",
                HttpMethod.GET, headers("a1", "ANALYST", null), null));
        assertEquals("d-pre-new", pairs.get(0).path("preDigest").asText());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE experiment_id = 'EXP-S4'",
                Integer.class), "锁定后更正被回滚，不新增记录");
    }

    @Test
    void lockRejects_duplicateLock_versionMismatch_withdrawn() throws Exception {
        createExperiment("EXP-S5", "s5-create");
        register("EXP-S5", "P1", "s5-alloc1");
        register("EXP-S5", "P2", "s5-alloc2");
        registerSample("EXP-S5", "P1", "c1", "s5-pre1", "SMP-PRE-1", "PRE", "d-pre", 1_000L);
        registerSample("EXP-S5", "P1", "c1", "s5-post1", "SMP-POST-1", "POST", "d-post", 2_000L);

        // 期望版本不匹配 409
        assertEquals(409, lock("EXP-S5", "P1", "c1", "s5-lock-v1", 1).getStatusCode().value());
        assertEquals(201, lock("EXP-S5", "P1", "c1", "s5-lock", 2).getStatusCode().value());
        // 重复锁定 409
        assertEquals(409, lock("EXP-S5", "P1", "c1", "s5-lock-again", 2).getStatusCode().value());

        // 退组后不能新锁定
        registerSample("EXP-S5", "P2", "c1", "s5-pre2", "SMP-PRE-2", "PRE", "d-pre2", 1_000L);
        registerSample("EXP-S5", "P2", "c1", "s5-post2", "SMP-POST-2", "POST", "d-post2", 2_000L);
        assertEquals(200, exchange("/api/experiments/EXP-S5/participants/P2/withdrawal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s5-wd"), null)
                .getStatusCode().value());
        assertEquals(409, lock("EXP-S5", "P2", "c1", "s5-lock-p2", 2).getStatusCode().value(),
                "退组参与者不能新锁定");
    }

    @Test
    void sampleNoGloballyUnique_reuseAcrossParticipantOrPhase409() throws Exception {
        createExperiment("EXP-S6", "s6-create");
        register("EXP-S6", "P1", "s6-alloc1");
        register("EXP-S6", "P2", "s6-alloc2");
        registerSample("EXP-S6", "P1", "c1", "s6-pre", "SMP-1", "PRE", "d1", 1_000L);
        // 同编号用于不同参与者 409
        assertEquals(409, registerSample("EXP-S6", "P2", "c1", "s6-p2",
                "SMP-1", "PRE", "d2", 1_000L).getStatusCode().value());
        // 同编号用于不同阶段 409
        assertEquals(409, registerSample("EXP-S6", "P1", "c1", "s6-post",
                "SMP-1", "POST", "d3", 2_000L).getStatusCode().value());
        // 失败不占编号对应记录：仍只有 1 条样本记录
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE experiment_id = 'EXP-S6'",
                Integer.class));
    }

    @Test
    void unregisteredParticipantAndMissingExperiment_rejected() {
        createExperiment("EXP-S7", "s7-create");
        assertEquals(404, registerSample("EXP-S7", "GHOST", "c1", "s7-pre",
                "SMP-1", "PRE", "d1", 1_000L).getStatusCode().value());
        assertEquals(404, lock("EXP-S7", "GHOST", "c1", "s7-lock", 0)
                .getStatusCode().value());
        assertEquals(404, registerSample("EXP-NONE", "P1", "c1", "s7-pre2",
                "SMP-1", "PRE", "d1", 1_000L).getStatusCode().value());
    }
    @Test
    void roleBoundaries_analystAndCoordinatorAccess() throws Exception {
        createExperiment("EXP-S8", "s8-create");
        register("EXP-S8", "P1", "s8-alloc");
        registerSample("EXP-S8", "P1", "c1", "s8-pre", "SMP-PRE-1", "PRE", "d-pre", 1_000L);
        registerSample("EXP-S8", "P1", "c1", "s8-post", "SMP-POST-1", "POST", "d-post", 2_000L);
        lock("EXP-S8", "P1", "c1", "s8-lock", 2);

        // ANALYST 不能登记、锁定、查样本链
        assertEquals(403, exchange("/api/experiments/EXP-S8/participants/P1/samples",
                HttpMethod.POST, headers("a1", "ANALYST", "s8-a-pre"),
                "{\"sampleNo\":\"SMP-X\",\"phase\":\"PRE\",\"digest\":\"dx\","
                        + "\"collectedAt\":100}").getStatusCode().value());
        assertEquals(403, exchange("/api/experiments/EXP-S8/participants/P1/sample-lock",
                HttpMethod.POST, headers("a1", "ANALYST", "s8-a-lock"),
                "{\"expectedVersion\":2}").getStatusCode().value());
        assertEquals(403, exchange("/api/experiments/EXP-S8/participants/P1/samples",
                HttpMethod.GET, headers("a1", "ANALYST", null), null).getStatusCode().value());
        // COORDINATOR 不能查分析视图
        assertEquals(403, exchange("/api/experiments/EXP-S8/analysis/pairs",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null)
                .getStatusCode().value());
        // REVIEWER 同样不能查分析视图
        assertEquals(403, exchange("/api/experiments/EXP-S8/analysis/pairs",
                HttpMethod.GET, headers("r1", "REVIEWER", null), null)
                .getStatusCode().value());
        // 未锁定的实验分析视图为空列表
        createExperiment("EXP-S8B", "s8b-create");
        JsonNode empty = json(exchange("/api/experiments/EXP-S8B/analysis/pairs",
                HttpMethod.GET, headers("a1", "ANALYST", null), null));
        assertEquals(0, empty.size());
    }

    @Test
    void coordinatorSeesOnlyOwnSubmissions_othersGet404() throws Exception {
        createExperiment("EXP-S9", "s9-create");
        register("EXP-S9", "P1", "s9-alloc");
        registerSample("EXP-S9", "P1", "c1", "s9-pre", "SMP-PRE-1", "PRE", "d-pre", 1_000L);
        // 另一名协调员查不到他人提交，错误详情不泄露参与者
        ResponseEntity<String> other = exchange(
                "/api/experiments/EXP-S9/participants/P1/samples",
                HttpMethod.GET, headers("c2", "COORDINATOR", null), null);
        assertEquals(404, other.getStatusCode().value());
        assertFalse(other.getBody().contains("P1"), "错误详情不得包含参与者编号");
        // 提交者本人可查
        assertEquals(200, exchange("/api/experiments/EXP-S9/participants/P1/samples",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null)
                .getStatusCode().value());
    }

    @Test
    void idempotency_replaySameResponse_differentParams409_failureDoesNotConsumeKey()
            throws Exception {
        createExperiment("EXP-S10", "s10-create");
        register("EXP-S10", "P1", "s10-alloc");

        ResponseEntity<String> first = registerSample("EXP-S10", "P1", "c1", "s10-pre",
                "SMP-PRE-1", "PRE", "d-pre", 1_000L);
        assertEquals(201, first.getStatusCode().value());
        // 同键同参重放首次完整响应，不新增记录
        ResponseEntity<String> replay = registerSample("EXP-S10", "P1", "c1", "s10-pre",
                "SMP-PRE-1", "PRE", "d-pre", 1_000L);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE experiment_id = 'EXP-S10'",
                Integer.class));
        // 同键异参 409
        assertEquals(409, registerSample("EXP-S10", "P1", "c1", "s10-pre",
                "SMP-PRE-1", "PRE", "d-pre-changed", 1_000L).getStatusCode().value());

        // 失败的锁定不占键：时序不合法 422 后修正数据，同 requestId 可成功
        registerSample("EXP-S10", "P1", "c1", "s10-post", "SMP-POST-1", "POST", "d-post", 500L);
        assertEquals(422, lock("EXP-S10", "P1", "c1", "s10-lock", 2).getStatusCode().value());
        registerSample("EXP-S10", "P1", "c1", "s10-post2", "SMP-POST-2", "POST", "d-post2", 3_000L);
        assertEquals(201, lock("EXP-S10", "P1", "c1", "s10-lock", 3).getStatusCode().value(),
                "失败不占键，修正后同键按新参数指纹仍属异参");
    }

    @Test
    void experimentClose_doesNotUndoLockedPairs() throws Exception {
        createExperiment("EXP-S11", "s11-create");
        register("EXP-S11", "P1", "s11-alloc");
        registerSample("EXP-S11", "P1", "c1", "s11-pre", "SMP-PRE-1", "PRE", "d-pre", 1_000L);
        registerSample("EXP-S11", "P1", "c1", "s11-post", "SMP-POST-1", "POST", "d-post", 2_000L);
        lock("EXP-S11", "P1", "c1", "s11-lock", 2);
        assertEquals(200, exchange("/api/experiments/EXP-S11/close",
                HttpMethod.POST, headers("c1", "COORDINATOR", "s11-close"), null)
                .getStatusCode().value());
        JsonNode pairs = json(exchange("/api/experiments/EXP-S11/analysis/pairs",
                HttpMethod.GET, headers("a1", "ANALYST", null), null));
        assertEquals(1, pairs.size(), "试验关闭不撤销已锁定对");
        assertEquals("d-pre", pairs.get(0).path("preDigest").asText());
    }
}