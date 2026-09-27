package com.example.starter.blind;

import com.example.starter.blind.service.SequenceDigest;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随机表封存/扩容主流程、失败分支、幂等与只读查询（真实 H2 内存库）：
 * 封存只存摘要不暴露序列；封存后按版本剩余序列分配；扩容关闭余量并新建后继版本；
 * 已分配/已揭盲/摘要不匹配分别 409/409/422；守恒失败整次回滚不留半成品；
 * 揭盲按分配固化版本定位；明细/历史/诊断只读不改状态。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TableSealFlowTest extends AbstractBlindIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final List<String> V1_SEQUENCE = List.of("A", "A", "B", "B");

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

    private void createExperiment(String expId, int blockCount, String requestId) {
        ResponseEntity<String> resp = exchange("/api/experiments/" + expId, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId),
                "{\"blockCount\":" + blockCount + "}");
        assertEquals(201, resp.getStatusCode().value());
    }

    private void allocate(String expId, String pid, String requestId) {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", requestId), null);
        assertEquals(201, resp.getStatusCode().value(), "分配 " + pid + " 应成功");
    }

    private String v1Digest(String sealKey, String expId, int blockNo) {
        return SequenceDigest.compute(sealKey, expId, blockNo, 1, 4, "A,B", V1_SEQUENCE);
    }

    private JsonNode seal(String expId, int blockNo, String sealKey, String digest,
                          String requestId) {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + expId + "/blocks/" + blockNo + "/seals/" + sealKey,
                HttpMethod.PUT, headers("c1", "COORDINATOR", requestId),
                "{\"sequenceDigest\":\"" + digest + "\"}");
        assertEquals(201, resp.getStatusCode().value(), "封存应成功: " + resp.getBody());
        return parse(resp.getBody());
    }

    private long versionId(String expId, int blockNo, int versionNo) {
        return jdbc.queryForObject(
                "SELECT id FROM random_table_version WHERE experiment_id = ? AND block_no = ? "
                        + "AND version_no = ?", Long.class, expId, blockNo, versionNo);
    }

    // ---------------- 主流程 ----------------

    @Test
    void seal_beforeFirstAllocation_succeeds_andStoresOnlyDigestNotSequence() throws Exception {
        createExperiment("SEAL-1", 2, "seal1-create");
        String digest = v1Digest("key-block1", "SEAL-1", 1);

        ResponseEntity<String> resp = exchange(
                "/api/experiments/SEAL-1/blocks/1/seals/key-block1", HttpMethod.PUT,
                headers("c1", "COORDINATOR", "seal1-put"),
                "{\"sequenceDigest\":\"" + digest + "\"}");
        assertEquals(201, resp.getStatusCode().value());
        JsonNode body = json(resp);
        assertEquals("SEAL-1", body.path("experimentId").asText());
        assertEquals(1, body.path("blockNo").asInt());
        assertEquals(1, body.path("versionNo").asInt());
        assertEquals("key-block1", body.path("sealKey").asText());
        assertEquals(digest, body.path("sequenceDigest").asText());
        assertEquals(4, body.path("blockCapacity").asInt());
        assertEquals("A,B", body.path("treatmentCodes").asText());
        assertEquals(1_700_000_000_000L, body.path("sealedAt").asLong());
        // 封存视图不得暴露具体序列或序号
        assertFalse(body.has("sequence"));
        assertFalse(body.has("seatNo"));
        assertFalse(body.has("treatment"));

        // 封存记录只保存摘要、容量、代码集合、时刻，不保存序列
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM table_seal WHERE experiment_id = 'SEAL-1' AND block_no = 1",
                Integer.class));
        List<String> stored = jdbc.queryForList(
                "SELECT sequence_digest FROM table_seal WHERE seal_key = 'key-block1'",
                String.class);
        assertEquals(List.of(digest), stored);
        Integer capacity = jdbc.queryForObject(
                "SELECT block_capacity FROM table_seal WHERE seal_key = 'key-block1'",
                Integer.class);
        assertEquals(4, capacity);
        // 封存后版本仍 ACTIVE，可继续按剩余序列分配
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM random_table_version WHERE experiment_id = 'SEAL-1' "
                        + "AND block_no = 1 AND version_no = 1", String.class));
    }

    @Test
    void allocationsAfterSeal_followSealedVersion_andFreezeVersionOnEachAllocation() {
        createExperiment("SEAL-2", 2, "seal2-create");
        seal("SEAL-2", 1, "key-b1", v1Digest("key-b1", "SEAL-2", 1), "seal2-put");

        for (int i = 1; i <= 4; i++) {
            allocate("SEAL-2", "P" + i, "seal2-alloc-" + i);
        }
        // 4 个分配全部落在 block1 的封存版本 v1，序号 1..4 被完整占用
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation a JOIN random_table_version v "
                        + "ON v.id = a.table_version_id "
                        + "WHERE a.experiment_id = 'SEAL-2' AND a.block_no = 1 AND v.version_no = 1",
                Integer.class));
        List<Integer> seqs = jdbc.queryForList(
                "SELECT seat_no FROM allocation WHERE experiment_id = 'SEAL-2' AND block_no = 1 "
                        + "ORDER BY participant_id", Integer.class);
        assertEquals(List.of(1, 2, 3, 4), seqs);

        // 第五个分配：block1 的 4 个序号已满，落到未封存的 block2
        allocate("SEAL-2", "P5", "seal2-alloc-5");
        assertEquals(2, jdbc.queryForObject(
                "SELECT block_no FROM allocation WHERE experiment_id = 'SEAL-2' "
                        + "AND participant_id = 'P5'", Integer.class));
    }

    @Test
    void expand_closesOldRemaining_createsSuccessor_andNewAllocationsUseNewVersion() {
        createExperiment("SEAL-3", 2, "seal3-create");
        seal("SEAL-3", 1, "key-b1", v1Digest("key-b1", "SEAL-3", 1), "seal3-put");
        allocate("SEAL-3", "P1", "seal3-a1");
        allocate("SEAL-3", "P2", "seal3-a2");
        long v1Id = versionId("SEAL-3", 1, 1);

        clock.advance(5_000L);
        ResponseEntity<String> resp = exchange(
                "/api/experiments/SEAL-3/blocks/1/table-versions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "seal3-expand"),
                "{\"sealKey\":\"key-b1\",\"expectedRemaining\":2,\"newCapacity\":6}");
        assertEquals(201, resp.getStatusCode().value());
        JsonNode body = parse(resp.getBody());
        assertEquals(2, body.path("versionNo").asInt());
        assertEquals(1, body.path("parentVersionNo").asInt());
        assertEquals(6, body.path("capacity").asInt());
        assertEquals("ACTIVE", body.path("status").asText());
        assertEquals("A,B", body.path("treatmentCodes").asText());
        assertEquals(0, body.path("assignedCount").asInt());
        assertEquals(6, body.path("remaining").asInt());
        assertEquals(0, body.path("closedRemaining").asInt());
        assertEquals(1_700_000_005_000L, body.path("createdAt").asLong());

        // 旧版本关闭，未分配余量 2 成为不可再分配的死名额，关闭时刻为 UTC 毫秒
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM random_table_version WHERE id = ?", String.class, v1Id));
        assertEquals(1_700_000_005_000L, jdbc.queryForObject(
                "SELECT closed_at FROM random_table_version WHERE id = ?", Long.class, v1Id));

        // 历史分配固化 v1 与原序号，不被改写
        assertEquals(v1Id, jdbc.queryForObject(
                "SELECT table_version_id FROM allocation WHERE experiment_id = 'SEAL-3' "
                        + "AND participant_id = 'P1'", Long.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT seat_no FROM allocation WHERE experiment_id = 'SEAL-3' "
                        + "AND participant_id = 'P1'", Integer.class));

        // 新分配不再使用 v1 剩余序号，全部进入 v2
        allocate("SEAL-3", "P3", "seal3-a3");
        long v2Id = versionId("SEAL-3", 1, 2);
        assertEquals(v2Id, jdbc.queryForObject(
                "SELECT table_version_id FROM allocation WHERE experiment_id = 'SEAL-3' "
                        + "AND participant_id = 'P3'", Long.class));
        // v2 序列 A/B 各半（处理集合不变、容量守恒的库内核验）
        assertEquals(3L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE table_version_id = ? AND treatment = 'A'",
                Long.class, v2Id));
        assertEquals(3L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE table_version_id = ? AND treatment = 'B'",
                Long.class, v2Id));
    }

    @Test
    void expand_twice_reusesAnchorSealKey_andChainsVersions() {
        createExperiment("SEAL-4", 2, "seal4-create");
        seal("SEAL-4", 1, "anchor-key", v1Digest("anchor-key", "SEAL-4", 1), "seal4-put");
        // 第一次扩容：v1 余量 4 全部关闭，v2 容量 2
        assertEquals(201, exchange("/api/experiments/SEAL-4/blocks/1/table-versions",
                HttpMethod.POST, headers("c1", "COORDINATOR", "seal4-expand1"),
                "{\"sealKey\":\"anchor-key\",\"expectedRemaining\":4,\"newCapacity\":2}")
                .getStatusCode().value());
        allocate("SEAL-4", "P1", "seal4-a1");
        // 第二次扩容仍显式引用同一已封存锚点；v2 实际余量 1 必须守恒
        ResponseEntity<String> resp = exchange(
                "/api/experiments/SEAL-4/blocks/1/table-versions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "seal4-expand2"),
                "{\"sealKey\":\"anchor-key\",\"expectedRemaining\":1,\"newCapacity\":4}");
        assertEquals(201, resp.getStatusCode().value());
        JsonNode body = parse(resp.getBody());
        assertEquals(3, body.path("versionNo").asInt());
        assertEquals(2, body.path("parentVersionNo").asInt());
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM random_table_version WHERE experiment_id = 'SEAL-4' "
                        + "AND block_no = 1 AND version_no = 2", String.class));
        // v1 封存记录仍在，封存不可撤销
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM table_seal WHERE experiment_id = 'SEAL-4' AND block_no = 1",
                Integer.class));
    }

    @Test
    void unblindAfterExpand_resolvesTreatmentFromFrozenVersion_notRewritten() {
        createExperiment("SEAL-5", 2, "seal5-create");
        seal("SEAL-5", 1, "key-b1", v1Digest("key-b1", "SEAL-5", 1), "seal5-put");
        allocate("SEAL-5", "P1", "seal5-a1"); // v1 seq1 = A

        // 扩容关闭 v1 剩余 3 个名额，新建 v2 容量 2
        assertEquals(201, exchange("/api/experiments/SEAL-5/blocks/1/table-versions",
                HttpMethod.POST, headers("c1", "COORDINATOR", "seal5-expand"),
                "{\"sealKey\":\"key-b1\",\"expectedRemaining\":3,\"newCapacity\":2}")
                .getStatusCode().value());

        // 旧参与者揭盲：仍按 v1 seq1 解码为 A
        ResponseEntity<String> apply = exchange(
                "/api/experiments/SEAL-5/participants/P1/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "seal5-apply"), "{\"reason\":\"核对旧版本\"}");
        String ubId = parse(apply.getBody()).path("requestId").asText();
        assertEquals(200, exchange("/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("r1", "REVIEWER", "seal5-approve"), null)
                .getStatusCode().value());
        ResponseEntity<String> result = exchange(
                "/api/unblind-requests/" + ubId + "/result", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null);
        assertEquals(200, result.getStatusCode().value());
        assertEquals("A", parse(result.getBody()).path("treatment").asText(),
                "扩容不得改写旧分配按固化版本解出的处理代码");
    }

    // ---------------- 失败分支 ----------------

    @Test
    void seal_afterAnyAllocation_conflicts_andIsIdempotentlyRejected() {
        createExperiment("SEAL-6", 2, "seal6-create");
        allocate("SEAL-6", "P1", "seal6-a1");
        ResponseEntity<String> resp = exchange(
                "/api/experiments/SEAL-6/blocks/1/seals/key-late", HttpMethod.PUT,
                headers("c1", "COORDINATOR", "seal6-late"),
                "{\"sequenceDigest\":\"" + v1Digest("key-late", "SEAL-6", 1) + "\"}");
        assertEquals(409, resp.getStatusCode().value());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM table_seal WHERE experiment_id = 'SEAL-6'", Integer.class));
    }

    @Test
    void seal_afterUnblindRequestEvenPending_conflicts() {
        createExperiment("SEAL-7", 2, "seal7-create");
        allocate("SEAL-7", "P1", "seal7-a1");
        assertEquals(201, exchange(
                "/api/experiments/SEAL-7/participants/P1/unblind-requests", HttpMethod.POST,
                headers("c1", "COORDINATOR", "seal7-apply"), "{\"reason\":\"待审揭盲\"}")
                .getStatusCode().value());
        // 即使揭盲尚在 PENDING，区组也不可再封存
        ResponseEntity<String> resp = exchange(
                "/api/experiments/SEAL-7/blocks/1/seals/key-pending", HttpMethod.PUT,
                headers("c1", "COORDINATOR", "seal7-put"),
                "{\"sequenceDigest\":\"" + v1Digest("key-pending", "SEAL-7", 1) + "\"}");
        assertEquals(409, resp.getStatusCode().value());
    }

    @Test
    void seal_digestMismatch_422_leavesNoHalfProduct_andCanStillSealCorrectly() {
        createExperiment("SEAL-8", 2, "seal8-create");
        ResponseEntity<String> bad = exchange(
                "/api/experiments/SEAL-8/blocks/1/seals/key-b1", HttpMethod.PUT,
                headers("c1", "COORDINATOR", "seal8-bad"),
                "{\"sequenceDigest\":\"" + "0".repeat(64) + "\"}");
        assertEquals(422, bad.getStatusCode().value());
        // 无半成品：无封存记录、版本仍 ACTIVE 且仍可分配
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM table_seal WHERE experiment_id = 'SEAL-8'", Integer.class));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM random_table_version WHERE experiment_id = 'SEAL-8' "
                        + "AND block_no = 1 AND version_no = 1", String.class));
        // 用正确摘要仍可封存
        ResponseEntity<String> good = exchange(
                "/api/experiments/SEAL-8/blocks/1/seals/key-b1", HttpMethod.PUT,
                headers("c1", "COORDINATOR", "seal8-good"),
                "{\"sequenceDigest\":\"" + v1Digest("key-b1", "SEAL-8", 1) + "\"}");
        assertEquals(201, good.getStatusCode().value());
    }

    @Test
    void seal_twiceSameBlock_orSameKeyAcrossBlocks_conflicts() {
        createExperiment("SEAL-9", 2, "seal9-create");
        seal("SEAL-9", 1, "key-1", v1Digest("key-1", "SEAL-9", 1), "seal9-put1");

        // 同区组重复封存：409，封存不可撤销
        ResponseEntity<String> twice = exchange(
                "/api/experiments/SEAL-9/blocks/1/seals/key-1", HttpMethod.PUT,
                headers("c1", "COORDINATOR", "seal9-put1-again"),
                "{\"sequenceDigest\":\"" + v1Digest("key-1", "SEAL-9", 1) + "\"}");
        assertEquals(409, twice.getStatusCode().value());

        // sealKey 全局唯一：另一区组使用同一密钥 409
        ResponseEntity<String> otherBlock = exchange(
                "/api/experiments/SEAL-9/blocks/2/seals/key-1", HttpMethod.PUT,
                headers("c1", "COORDINATOR", "seal9-put2"),
                "{\"sequenceDigest\":\"" + v1Digest("key-1", "SEAL-9", 2) + "\"}");
        assertEquals(409, otherBlock.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM table_seal WHERE experiment_id = 'SEAL-9'", Integer.class));
    }

    @Test
    void seal_unknownExperimentOrBlock_404_andBlankDigest_400() {
        createExperiment("SEAL-10", 2, "seal10-create");
        assertEquals(404, exchange(
                "/api/experiments/NOPE/blocks/1/seals/key-x", HttpMethod.PUT,
                headers("c1", "COORDINATOR", "seal10-noexp"),
                "{\"sequenceDigest\":\"" + "a".repeat(64) + "\"}").getStatusCode().value());
        assertEquals(404, exchange(
                "/api/experiments/SEAL-10/blocks/3/seals/key-x", HttpMethod.PUT,
                headers("c1", "COORDINATOR", "seal10-noblock"),
                "{\"sequenceDigest\":\"" + "a".repeat(64) + "\"}").getStatusCode().value());
        assertEquals(400, exchange(
                "/api/experiments/SEAL-10/blocks/1/seals/key-x", HttpMethod.PUT,
                headers("c1", "COORDINATOR", "seal10-blank"),
                "{\"sequenceDigest\":\"\"}").getStatusCode().value());
    }

    @Test
    void expand_failures_areDistinguishable_andConservationFailureRollsBackWholeTx() {
        createExperiment("SEAL-11", 2, "seal11-create");

        // 未封存直接扩容：422
        assertEquals(422, exchange(
                "/api/experiments/SEAL-11/blocks/1/table-versions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "seal11-expand-noseal"),
                "{\"sealKey\":\"ghost\",\"expectedRemaining\":4,\"newCapacity\":4}")
                .getStatusCode().value());

        seal("SEAL-11", 1, "key-b1", v1Digest("key-b1", "SEAL-11", 1), "seal11-put");
        allocate("SEAL-11", "P1", "seal11-a1");

        // 错误密钥：422
        assertEquals(422, exchange(
                "/api/experiments/SEAL-11/blocks/1/table-versions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "seal11-expand-wrongkey"),
                "{\"sealKey\":\"other-key\",\"expectedRemaining\":3,\"newCapacity\":4}")
                .getStatusCode().value());

        // 密钥属于其他区组：422
        seal("SEAL-11", 2, "key-b2", v1Digest("key-b2", "SEAL-11", 2), "seal11-put2");
        ResponseEntity<String> crossBlock = exchange(
                "/api/experiments/SEAL-11/blocks/1/table-versions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "seal11-expand-cross"),
                "{\"sealKey\":\"key-b2\",\"expectedRemaining\":3,\"newCapacity\":4}");
        assertEquals(422, crossBlock.getStatusCode().value());

        // 守恒失败：实际余量 3，要求值 2；422 且消息给出实际值、要求值与差额
        ResponseEntity<String> nonConserve = exchange(
                "/api/experiments/SEAL-11/blocks/1/table-versions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "seal11-expand-badbalance"),
                "{\"sealKey\":\"key-b1\",\"expectedRemaining\":2,\"newCapacity\":4}");
        assertEquals(422, nonConserve.getStatusCode().value());
        String message = parse(nonConserve.getBody()).path("message").asText();
        assertTrue(message.contains("实际未分配名额=3"), message);
        assertTrue(message.contains("要求值=2"), message);
        assertTrue(message.contains("差额=+1"), message);

        // 整次回滚无半成品：无 v2、v1 仍 ACTIVE、余量仍为 3、席位无新增
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_version WHERE experiment_id = 'SEAL-11' "
                        + "AND block_no = 1", Integer.class));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM random_table_version WHERE experiment_id = 'SEAL-11' "
                        + "AND block_no = 1 AND version_no = 1", String.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat s JOIN random_table_version v ON v.id = s.table_version_id "
                        + "WHERE v.experiment_id = 'SEAL-11' AND v.block_no = 1", Integer.class));

        // 奇数容量与越界容量：400
        assertEquals(400, exchange(
                "/api/experiments/SEAL-11/blocks/1/table-versions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "seal11-expand-odd"),
                "{\"sealKey\":\"key-b1\",\"expectedRemaining\":3,\"newCapacity\":5}")
                .getStatusCode().value());
        assertEquals(400, exchange(
                "/api/experiments/SEAL-11/blocks/1/table-versions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "seal11-expand-big"),
                "{\"sealKey\":\"key-b1\",\"expectedRemaining\":3,\"newCapacity\":66}")
                .getStatusCode().value());
    }

    // ---------------- 幂等与权限 ----------------

    @Test
    void seal_andExpand_areIdempotent_replayOriginalResult_andDifferentParamsConflict()
            throws Exception {
        createExperiment("SEAL-12", 2, "seal12-create");
        String digest = v1Digest("idem-key", "SEAL-12", 1);

        ResponseEntity<String> first = exchange(
                "/api/experiments/SEAL-12/blocks/1/seals/idem-key", HttpMethod.PUT,
                headers("c1", "COORDINATOR", "idem-seal"),
                "{\"sequenceDigest\":\"" + digest + "\"}");
        assertEquals(201, first.getStatusCode().value());
        ResponseEntity<String> replay = exchange(
                "/api/experiments/SEAL-12/blocks/1/seals/idem-key", HttpMethod.PUT,
                headers("c1", "COORDINATOR", "idem-seal"),
                "{\"sequenceDigest\":\"" + digest + "\"}");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM table_seal WHERE seal_key = 'idem-key'", Integer.class));

        // 同键换摘要：异参 409
        ResponseEntity<String> different = exchange(
                "/api/experiments/SEAL-12/blocks/1/seals/idem-key", HttpMethod.PUT,
                headers("c1", "COORDINATOR", "idem-seal"),
                "{\"sequenceDigest\":\"" + "1".repeat(64) + "\"}");
        assertEquals(409, different.getStatusCode().value());

        // 扩容同键重放原 201，不产生 v3
        String expandBody =
                "{\"sealKey\":\"idem-key\",\"expectedRemaining\":4,\"newCapacity\":6}";
        ResponseEntity<String> expand1 = exchange(
                "/api/experiments/SEAL-12/blocks/1/table-versions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "idem-expand"), expandBody);
        assertEquals(201, expand1.getStatusCode().value());
        ResponseEntity<String> expandReplay = exchange(
                "/api/experiments/SEAL-12/blocks/1/table-versions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "idem-expand"), expandBody);
        assertEquals(201, expandReplay.getStatusCode().value());
        assertEquals(expand1.getBody(), expandReplay.getBody());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_version WHERE experiment_id = 'SEAL-12' "
                        + "AND block_no = 1", Integer.class));
    }

    @Test
    void failedSealAndExpand_doNotConsumeKey() {
        createExperiment("SEAL-13", 2, "seal13-create");
        // 摘要错误失败不占键：同 requestId 改正确摘要后成功
        assertEquals(422, exchange(
                "/api/experiments/SEAL-13/blocks/1/seals/retry-key", HttpMethod.PUT,
                headers("c1", "COORDINATOR", "retry-seal"),
                "{\"sequenceDigest\":\"" + "9".repeat(64) + "\"}").getStatusCode().value());
        assertEquals(201, exchange(
                "/api/experiments/SEAL-13/blocks/1/seals/retry-key", HttpMethod.PUT,
                headers("c1", "COORDINATOR", "retry-seal"),
                "{\"sequenceDigest\":\""
                        + v1Digest("retry-key", "SEAL-13", 1) + "\"}")
                .getStatusCode().value());

        // 扩容守恒失败不占键：同 requestId 改对 expectedRemaining 后成功
        allocate("SEAL-13", "P1", "seal13-a1");
        assertEquals(422, exchange(
                "/api/experiments/SEAL-13/blocks/1/table-versions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "retry-expand"),
                "{\"sealKey\":\"retry-key\",\"expectedRemaining\":0,\"newCapacity\":4}")
                .getStatusCode().value());
        assertEquals(201, exchange(
                "/api/experiments/SEAL-13/blocks/1/table-versions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "retry-expand"),
                "{\"sealKey\":\"retry-key\",\"expectedRemaining\":3,\"newCapacity\":4}")
                .getStatusCode().value());
    }

    @Test
    void reviewerCannotSealOrExpand_andAuthPrecedesIdempotency() {
        createExperiment("SEAL-14", 2, "seal14-create");
        // REVIEWER 封存：403
        assertEquals(403, exchange(
                "/api/experiments/SEAL-14/blocks/1/seals/auth-key", HttpMethod.PUT,
                headers("r1", "REVIEWER", "auth-seal"),
                "{\"sequenceDigest\":\"" + v1Digest("auth-key", "SEAL-14", 1) + "\"}")
                .getStatusCode().value());
        // 协调员先成功占用该 requestId
        seal("SEAL-14", 1, "auth-key", v1Digest("auth-key", "SEAL-14", 1), "auth-seal");
        // REVIEWER 用同 requestId：权限先于幂等回放，仍 403 而非回放 201
        assertEquals(403, exchange(
                "/api/experiments/SEAL-14/blocks/1/seals/auth-key", HttpMethod.PUT,
                headers("r1", "REVIEWER", "auth-seal"),
                "{\"sequenceDigest\":\"" + v1Digest("auth-key", "SEAL-14", 1) + "\"}")
                .getStatusCode().value());
        // REVIEWER 扩容：403
        assertEquals(403, exchange(
                "/api/experiments/SEAL-14/blocks/1/table-versions", HttpMethod.POST,
                headers("r1", "REVIEWER", "auth-expand"),
                "{\"sealKey\":\"auth-key\",\"expectedRemaining\":4,\"newCapacity\":4}")
                .getStatusCode().value());
    }

    // ---------------- 明细 / 历史 / 诊断：只读不改状态 ----------------

    @Test
    void detailHistoryAndDiagnostics_areReadOnly_andReflectActualValues() throws Exception {
        createExperiment("SEAL-15", 2, "seal15-create");
        seal("SEAL-15", 1, "hist-key", v1Digest("hist-key", "SEAL-15", 1), "seal15-put");
        allocate("SEAL-15", "P1", "seal15-a1");
        allocate("SEAL-15", "P2", "seal15-a2");
        clock.advance(1_000L);
        assertEquals(201, exchange(
                "/api/experiments/SEAL-15/blocks/1/table-versions", HttpMethod.POST,
                headers("c1", "COORDINATOR", "seal15-expand"),
                "{\"sealKey\":\"hist-key\",\"expectedRemaining\":2,\"newCapacity\":6}")
                .getStatusCode().value());
        allocate("SEAL-15", "P3", "seal15-a3");

        String before = snapshot();

        // 三个只读查询 + 明细查询，两种角色均可
        ResponseEntity<String> versions = exchange(
                "/api/experiments/SEAL-15/blocks/1/table-versions", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        assertEquals(200, versions.getStatusCode().value());
        JsonNode versionArray = json(versions);
        assertEquals(2, versionArray.size());
        JsonNode v1 = versionArray.get(0);
        assertEquals(1, v1.path("versionNo").asInt());
        assertTrue(v1.path("parentVersionNo").isNull());
        assertEquals("CLOSED", v1.path("status").asText());
        assertEquals(2, v1.path("assignedCount").asInt());
        assertEquals(0, v1.path("remaining").asInt());
        assertEquals(2, v1.path("closedRemaining").asInt(), "v1 被关闭的未分配余量应为 2");
        JsonNode v2 = versionArray.get(1);
        assertEquals(2, v2.path("versionNo").asInt());
        assertEquals(1, v2.path("parentVersionNo").asInt());
        assertEquals(6, v2.path("capacity").asInt());
        assertEquals(1, v2.path("assignedCount").asInt());
        assertEquals(5, v2.path("remaining").asInt());
        assertEquals(0, v2.path("closedRemaining").asInt());
        assertFalse(v2.has("sequence"));

        ResponseEntity<String> history = exchange(
                "/api/experiments/SEAL-15/blocks/1/table-history", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null);
        JsonNode events = json(history);
        // 事件：v1 created, v1 sealed, v1 closed, v2 created（v2 未封存）共 4 个
        assertEquals(4, events.size());
        assertEquals("VERSION_CREATED", events.get(0).path("eventType").asText());
        assertEquals(1, events.get(0).path("versionNo").asInt());
        assertEquals("VERSION_SEALED", events.get(1).path("eventType").asText());
        assertEquals("hist-key", events.get(1).path("sealKey").asText());
        assertEquals("VERSION_CLOSED", events.get(2).path("eventType").asText());
        assertEquals("VERSION_CREATED", events.get(3).path("eventType").asText());
        assertEquals(2, events.get(3).path("versionNo").asInt());
        assertEquals(1, events.get(3).path("parentVersionNo").asInt());
        // 事件时间单调不降（左闭右开提交顺序），封存/关闭事件的容量与代码集合快照稳定
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).path("eventTime").asLong()
                    >= events.get(i - 1).path("eventTime").asLong());
        }
        assertEquals(4, events.get(1).path("blockCapacity").asInt());
        assertEquals("A,B", events.get(1).path("treatmentCodes").asText());

        ResponseEntity<String> diagnostics = exchange(
                "/api/experiments/SEAL-15/table-diagnostics", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        JsonNode diag = json(diagnostics);
        // block1: 容量 4+6=10；block2: 容量 4；合计 14
        assertEquals(14, diag.path("totalCapacity").asInt());
        // 已分配 3（P1/P2 在 v1，P3 在 v2）
        assertEquals(3, diag.path("totalAssigned").asInt());
        // 可分配余量：v2 剩 5 + block2 剩 4 = 9；v1 关闭死名额 2
        assertEquals(9, diag.path("totalRemaining").asInt());
        assertEquals(2, diag.path("totalClosedRemaining").asInt());
        JsonNode block1 = diag.path("blocks").get(0);
        assertEquals(1, block1.path("blockNo").asInt());
        assertTrue(block1.path("sealed").asBoolean(), "v1 已封存应在诊断中可区分");
        assertEquals(2, block1.path("currentVersionNo").asInt());
        assertEquals("ACTIVE", block1.path("currentVersionStatus").asText());

        // 分配明细：含固化版本号，不含版本内序号与处理代码
        ResponseEntity<String> details = exchange(
                "/api/experiments/SEAL-15/allocations", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null);
        JsonNode allocations = json(details);
        assertEquals(3, allocations.size());
        assertEquals(1, allocations.get(0).path("versionNo").asInt());
        assertEquals(1, allocations.get(1).path("versionNo").asInt());
        assertEquals(2, allocations.get(2).path("versionNo").asInt());
        for (JsonNode allocation : allocations) {
            assertFalse(allocation.has("seatNo"), "明细不得暴露版本内序号");
            assertFalse(allocation.has("treatment"), "明细不得暴露处理代码");
        }

        // 读取不改变状态：四次只读调用前后所有业务表数据快照一致
        exchange("/api/experiments/SEAL-15/blocks/1/table-versions", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null);
        exchange("/api/experiments/SEAL-15/blocks/1/table-history", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        exchange("/api/experiments/SEAL-15/table-diagnostics", HttpMethod.GET,
                headers("c1", "COORDINATOR", null), null);
        exchange("/api/experiments/SEAL-15/allocations", HttpMethod.GET,
                headers("r1", "REVIEWER", null), null);
        assertEquals(before, snapshot(), "只读查询不得改变任何业务数据");
    }

    /** 全部业务表行数与关键内容的稳定快照，用于断言只读查询不改状态。 */
    private String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (String table : List.of("experiment", "random_table_version", "table_seal", "seat",
                "allocation", "unblind_request", "idempotent_request")) {
            sb.append(table).append('=')
                    .append(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class))
                    .append(';');
        }
        sb.append(jdbc.queryForList(
                "SELECT experiment_id, block_no, version_no, parent_id, capacity, status, "
                        + "closed_at FROM random_table_version ORDER BY id"));
        sb.append(jdbc.queryForList(
                "SELECT table_version_id, seal_key, block_capacity, sealed_at FROM table_seal "
                        + "ORDER BY id"));
        sb.append(jdbc.queryForList(
                "SELECT participant_id, block_no, seat_no, table_version_id, status, "
                        + "withdrawn_at FROM allocation ORDER BY id"));
        return sb.toString();
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
