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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随机表封存与区组扩容：主流程、失败分支、幂等与并发边界。
 * 封存记录只暴露摘要/容量/处理代码集合/时刻；扩容保持既有序号与处理代码不变。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RandomTableSealTest extends AbstractBlindIntegrationTest {

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

    private void createExperiment(String id, int blockCount, String requestId) {
        ResponseEntity<String> resp = exchange("/api/experiments/" + id, HttpMethod.POST,
                headers("c1", "COORDINATOR", requestId),
                "{\"blockCount\":" + blockCount + "}");
        assertEquals(201, resp.getStatusCode().value());
    }

    private void allocate(String exp, String pid, String requestId) {
        ResponseEntity<String> resp = exchange(
                "/api/experiments/" + exp + "/participants/" + pid + "/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", requestId), null);
        assertEquals(201, resp.getStatusCode().value());
    }

    private String digest(String exp, int blockNo, int versionNo, int seatTo,
                          String sequence, String sealKey) throws Exception {
        String raw = exp + "|" + blockNo + "|" + versionNo + "|" + seatTo
                + "|" + sequence + "|" + sealKey;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------- 封存主流程 ----------------

    @Test
    void seal_beforeFirstAllocation_storesDigestOnly_andQueriesAreReadOnly() throws Exception {
        createExperiment("SEAL-1", 2, "req-s1-create");
        String expected = digest("SEAL-1", 1, 1, 4, "AABB", "key-alpha");

        ResponseEntity<String> resp = exchange("/api/experiments/SEAL-1/blocks/1/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s1-seal"),
                "{\"sealKey\":\"key-alpha\",\"expectedDigest\":\"" + expected + "\"}");
        assertEquals(201, resp.getStatusCode().value());
        JsonNode body = json(resp);
        assertEquals("SEAL-1", body.path("experimentId").asText());
        assertEquals(1, body.path("blockNo").asInt());
        assertEquals(1, body.path("versionNo").asInt());
        assertEquals(expected, body.path("tableDigest").asText());
        assertEquals(4, body.path("capacity").asInt());
        assertEquals(4, body.path("addedSeats").asInt());
        assertEquals("AB", body.path("treatmentCodes").asText());
        assertEquals("SEALED", body.path("status").asText());
        assertEquals(1_700_000_000_000L, body.path("sealedAt").asLong());
        assertTrue(body.path("closedAt").isNull());
        assertTrue(body.path("predecessorVersion").isNull());
        // 封存记录不得暴露具体序列或封存密钥
        assertFalse(body.has("sealKey"));
        assertFalse(body.has("sequence"));
        assertFalse(body.has("treatment"));
        assertFalse(body.has("seatNo"));

        // 库内只保存摘要等四个要素与状态字段，不保存 sealKey 列
        assertEquals(expected, jdbc.queryForObject(
                "SELECT table_digest FROM random_table_version "
                        + "WHERE experiment_id = 'SEAL-1' AND block_no = 1 AND version_no = 1",
                String.class));

        // 历史查询（REVIEWER 可读，只读不改变状态）
        ResponseEntity<String> history = exchange("/api/experiments/SEAL-1/blocks/1/versions",
                HttpMethod.GET, headers("r1", "REVIEWER", null), null);
        assertEquals(200, history.getStatusCode().value());
        JsonNode list = json(history);
        assertEquals(1, list.size());
        assertEquals(expected, list.get(0).path("tableDigest").asText());
        assertFalse(list.get(0).has("sealKey"));

        // 明细查询
        ResponseEntity<String> detail = exchange(
                "/api/experiments/SEAL-1/blocks/1/versions/1",
                HttpMethod.GET, headers("r1", "REVIEWER", null), null);
        assertEquals(200, detail.getStatusCode().value());
        assertEquals(expected, json(detail).path("tableDigest").asText());

        // 诊断查询：返回实际容量与名额
        ResponseEntity<String> diag = exchange(
                "/api/experiments/SEAL-1/blocks/1/diagnostics",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null);
        assertEquals(200, diag.getStatusCode().value());
        JsonNode diagBody = json(diag);
        assertTrue(diagBody.path("sealed").asBoolean());
        assertEquals(1, diagBody.path("latestVersion").asInt());
        assertEquals(4, diagBody.path("capacity").asInt());
        assertEquals(0, diagBody.path("allocatedCount").asInt());
        assertEquals(4, diagBody.path("remainingAllocatable").asInt());

        // 只读查询不改变状态：再次读取结果一致
        ResponseEntity<String> diagAgain = exchange(
                "/api/experiments/SEAL-1/blocks/1/diagnostics",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null);
        assertEquals(json(diag).toString(), json(diagAgain).toString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_version WHERE experiment_id = 'SEAL-1'",
                Integer.class));
    }

    @Test
    void seal_failures_conflictOrUnprocessable_andNoPartialState() throws Exception {
        createExperiment("SEAL-2", 2, "req-s2-create");

        // 已有分配：409
        allocate("SEAL-2", "P1", "req-s2-alloc-1");
        ResponseEntity<String> afterAlloc = exchange("/api/experiments/SEAL-2/blocks/1/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s2-seal-late"),
                "{\"sealKey\":\"key-late\"}");
        assertEquals(409, afterAlloc.getStatusCode().value());

        // 摘要不匹配：422，且整次回滚查询不到半成品
        ResponseEntity<String> mismatch = exchange("/api/experiments/SEAL-2/blocks/2/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s2-seal-bad-digest"),
                "{\"sealKey\":\"key-beta\",\"expectedDigest\":\""
                        + "0000000000000000000000000000000000000000000000000000000000000000\"}");
        assertEquals(422, mismatch.getStatusCode().value());
        assertTrue(json(mismatch).path("message").asText().contains("随机表摘要不匹配"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_version "
                        + "WHERE experiment_id = 'SEAL-2' AND block_no = 2", Integer.class));
        ResponseEntity<String> history = exchange("/api/experiments/SEAL-2/blocks/2/versions",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null);
        assertEquals(0, json(history).size());

        // 区组不存在：404
        ResponseEntity<String> noBlock = exchange("/api/experiments/SEAL-2/blocks/9/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s2-seal-noblock"),
                "{\"sealKey\":\"key-x\"}");
        assertEquals(404, noBlock.getStatusCode().value());

        // 实验不存在：404
        ResponseEntity<String> noExp = exchange("/api/experiments/NOPE/blocks/1/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s2-seal-noexp"),
                "{\"sealKey\":\"key-x\"}");
        assertEquals(404, noExp.getStatusCode().value());

        // REVIEWER 无权限：403（权限先于幂等）
        ResponseEntity<String> forbidden = exchange("/api/experiments/SEAL-2/blocks/2/seal",
                HttpMethod.POST, headers("r1", "REVIEWER", "req-s2-seal-reviewer"),
                "{\"sealKey\":\"key-x\"}");
        assertEquals(403, forbidden.getStatusCode().value());

        // 缺 sealKey：400
        ResponseEntity<String> blank = exchange("/api/experiments/SEAL-2/blocks/2/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s2-seal-blank"),
                "{\"sealKey\":\"\"}");
        assertEquals(400, blank.getStatusCode().value());
    }

    @Test
    void seal_duplicateSeal_conflict409_irrevocable() throws Exception {
        createExperiment("SEAL-3", 2, "req-s3-create");
        assertEquals(201, exchange("/api/experiments/SEAL-3/blocks/1/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s3-seal"),
                "{\"sealKey\":\"key-one\"}").getStatusCode().value());

        // 重复封存（即使换 sealKey）：409，封存不可撤销
        ResponseEntity<String> again = exchange("/api/experiments/SEAL-3/blocks/1/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s3-seal-again"),
                "{\"sealKey\":\"key-two\"}");
        assertEquals(409, again.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_version "
                        + "WHERE experiment_id = 'SEAL-3' AND block_no = 1", Integer.class));
    }

    // ---------------- 分配固化版本与序号 ----------------

    @Test
    void allocation_fixesTableVersionAndSeq_expansionKeepsHistory() throws Exception {
        createExperiment("SEAL-4", 2, "req-s4-create");
        assertEquals(201, exchange("/api/experiments/SEAL-4/blocks/1/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s4-seal"),
                "{\"sealKey\":\"key-v1\"}").getStatusCode().value());

        // 封存后分配：tableVersion 固化为 1，普通视图仍无席位/序号/处理代码
        for (int i = 1; i <= 4; i++) {
            ResponseEntity<String> resp = exchange(
                    "/api/experiments/SEAL-4/participants/P" + i + "/allocations",
                    HttpMethod.POST, headers("c1", "COORDINATOR", "req-s4-alloc-" + i), null);
            assertEquals(201, resp.getStatusCode().value());
            JsonNode node = json(resp);
            assertEquals(1, node.path("tableVersion").asInt());
            assertFalse(node.has("seatNo"));
            assertFalse(node.has("seqNo"));
            assertFalse(node.has("treatment"));
        }
        // 库内序号与席位一一对应且按剩余序列顺序
        List<Integer> seqs = jdbc.queryForList(
                "SELECT seq_no FROM allocation WHERE experiment_id = 'SEAL-4' "
                        + "ORDER BY id", Integer.class);
        assertEquals(List.of(1, 2, 3, 4), seqs);

        // 扩容：区组1已满（余量0），新增4席
        ResponseEntity<String> expand = exchange("/api/experiments/SEAL-4/blocks/1/expansions",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s4-expand"),
                "{\"baseVersion\":1,\"addedSeats\":4,\"expectedUnallocated\":0,"
                        + "\"sealKey\":\"key-v2\"}");
        assertEquals(201, expand.getStatusCode().value());
        JsonNode v2 = json(expand);
        assertEquals(2, v2.path("versionNo").asInt());
        assertEquals(8, v2.path("capacity").asInt());
        assertEquals(4, v2.path("addedSeats").asInt());
        assertEquals(1, v2.path("predecessorVersion").asInt());
        assertEquals("AB", v2.path("treatmentCodes").asText());

        // 扩容后新分配进入版本2的剩余序列（席位5起）
        ResponseEntity<String> p5 = exchange(
                "/api/experiments/SEAL-4/participants/P5/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s4-alloc-5"), null);
        assertEquals(201, p5.getStatusCode().value());
        assertEquals(2, json(p5).path("tableVersion").asInt());
        assertEquals(5, jdbc.queryForObject(
                "SELECT seq_no FROM allocation WHERE experiment_id = 'SEAL-4' "
                        + "AND participant_id = 'P5'", Integer.class));

        // 历史不改写：既有分配版本与序号保持原值
        List<Integer> oldVersions = jdbc.queryForList(
                "SELECT table_version FROM allocation WHERE experiment_id = 'SEAL-4' "
                        + "AND participant_id IN ('P1','P2','P3','P4') ORDER BY id",
                Integer.class);
        assertEquals(List.of(1, 1, 1, 1), oldVersions);
        assertEquals(List.of(1, 2, 3, 4), jdbc.queryForList(
                "SELECT seq_no FROM allocation WHERE experiment_id = 'SEAL-4' "
                        + "AND participant_id IN ('P1','P2','P3','P4') ORDER BY id",
                Integer.class));

        // 既有席位处理代码不变，新席位按前半A后半B追加
        List<String> treatments = jdbc.queryForList(
                "SELECT treatment FROM seat WHERE experiment_id = 'SEAL-4' AND block_no = 1 "
                        + "ORDER BY seat_no", String.class);
        assertEquals(List.of("A", "A", "B", "B", "A", "A", "B", "B"), treatments);

        // 版本历史：两个版本，版本1保持原摘要与状态
        ResponseEntity<String> history = exchange("/api/experiments/SEAL-4/blocks/1/versions",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null);
        JsonNode versions = json(history);
        assertEquals(2, versions.size());
        assertEquals(1, versions.get(0).path("versionNo").asInt());
        assertEquals("SEALED", versions.get(0).path("status").asText());
        assertEquals(2, versions.get(1).path("versionNo").asInt());
        assertNotEquals(versions.get(0).path("tableDigest").asText(),
                versions.get(1).path("tableDigest").asText());
    }

    @Test
    void allocation_beforeSeal_hasNullTableVersion() throws Exception {
        createExperiment("SEAL-5", 2, "req-s5-create");
        ResponseEntity<String> resp = exchange(
                "/api/experiments/SEAL-5/participants/P1/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s5-alloc-1"), null);
        assertEquals(201, resp.getStatusCode().value());
        assertTrue(json(resp).path("tableVersion").isNull());
    }

    // ---------------- 扩容失败分支 ----------------

    @Test
    void expand_failures_andAtomicRollback() throws Exception {
        createExperiment("SEAL-6", 2, "req-s6-create");

        // 未封存：409 扩容必须引用已封存版本
        ResponseEntity<String> unsealed = exchange("/api/experiments/SEAL-6/blocks/1/expansions",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s6-exp-unsealed"),
                "{\"baseVersion\":1,\"addedSeats\":2,\"expectedUnallocated\":4,"
                        + "\"sealKey\":\"k\"}");
        assertEquals(409, unsealed.getStatusCode().value());

        assertEquals(201, exchange("/api/experiments/SEAL-6/blocks/1/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s6-seal"),
                "{\"sealKey\":\"key-v1\"}").getStatusCode().value());
        allocate("SEAL-6", "P1", "req-s6-alloc-1");

        // baseVersion 不是当前最新版本：409
        ResponseEntity<String> wrongBase = exchange("/api/experiments/SEAL-6/blocks/1/expansions",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s6-exp-wrong-base"),
                "{\"baseVersion\":7,\"addedSeats\":2,\"expectedUnallocated\":3,"
                        + "\"sealKey\":\"k\"}");
        assertEquals(409, wrongBase.getStatusCode().value());

        // 未分配名额不守恒：422，响应含要求/实际/差额，整次回滚无半成品
        ResponseEntity<String> notConserved = exchange(
                "/api/experiments/SEAL-6/blocks/1/expansions",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s6-exp-not-conserved"),
                "{\"baseVersion\":1,\"addedSeats\":2,\"expectedUnallocated\":2,"
                        + "\"sealKey\":\"k\"}");
        assertEquals(422, notConserved.getStatusCode().value());
        String message = json(notConserved).path("message").asText();
        assertTrue(message.contains("要求=2"), message);
        assertTrue(message.contains("实际=3"), message);
        assertTrue(message.contains("差额=1"), message);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_version "
                        + "WHERE experiment_id = 'SEAL-6' AND block_no = 1", Integer.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'SEAL-6' AND block_no = 1",
                Integer.class));

        // addedSeats 为奇数：400
        ResponseEntity<String> odd = exchange("/api/experiments/SEAL-6/blocks/1/expansions",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s6-exp-odd"),
                "{\"baseVersion\":1,\"addedSeats\":3,\"expectedUnallocated\":3,"
                        + "\"sealKey\":\"k\"}");
        assertEquals(400, odd.getStatusCode().value());

        // 守恒成立：201，容量 4+2=6
        ResponseEntity<String> ok = exchange("/api/experiments/SEAL-6/blocks/1/expansions",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s6-exp-ok"),
                "{\"baseVersion\":1,\"addedSeats\":2,\"expectedUnallocated\":3,"
                        + "\"sealKey\":\"key-v2\"}");
        assertEquals(201, ok.getStatusCode().value());
        assertEquals(6, json(ok).path("capacity").asInt());

        // 诊断反映扩容后的实际值
        ResponseEntity<String> diag = exchange(
                "/api/experiments/SEAL-6/blocks/1/diagnostics",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null);
        JsonNode diagBody = json(diag);
        assertEquals(6, diagBody.path("capacity").asInt());
        assertEquals(1, diagBody.path("allocatedCount").asInt());
        assertEquals(5, diagBody.path("remainingAllocatable").asInt());
    }

    // ---------------- 关闭余量与表错误恢复 ----------------

    @Test
    void closeRemainder_thenExpandWithNewVersion_neverReordersExisting() throws Exception {
        createExperiment("SEAL-7", 2, "req-s7-create");
        assertEquals(201, exchange("/api/experiments/SEAL-7/blocks/1/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s7-seal"),
                "{\"sealKey\":\"key-v1\"}").getStatusCode().value());
        allocate("SEAL-7", "P1", "req-s7-alloc-1");
        allocate("SEAL-7", "P2", "req-s7-alloc-2");

        // 发现表错误：关闭版本1未分配余量（席位3、4作废）
        clock.advance(1_000L);
        ResponseEntity<String> closed = exchange(
                "/api/experiments/SEAL-7/blocks/1/versions/1/remainder-closure",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s7-close"), null);
        assertEquals(200, closed.getStatusCode().value());
        JsonNode closedBody = json(closed);
        assertEquals("CLOSED", closedBody.path("status").asText());
        assertEquals(1_700_000_001_000L, closedBody.path("closedAt").asLong());

        // 关闭不可撤销：再次关闭 409
        ResponseEntity<String> again = exchange(
                "/api/experiments/SEAL-7/blocks/1/versions/1/remainder-closure",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s7-close-again"), null);
        assertEquals(409, again.getStatusCode().value());

        // 关闭后诊断：余量为0；新分配落到区组2
        ResponseEntity<String> diag = exchange(
                "/api/experiments/SEAL-7/blocks/1/diagnostics",
                HttpMethod.GET, headers("c1", "COORDINATOR", null), null);
        assertEquals(0, json(diag).path("remainingAllocatable").asInt());
        ResponseEntity<String> p3 = exchange(
                "/api/experiments/SEAL-7/participants/P3/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s7-alloc-3"), null);
        assertEquals(201, p3.getStatusCode().value());
        assertEquals(2, json(p3).path("blockNo").asInt());

        // 以已关闭的封存版本为基扩容：版本2，席位5、6
        ResponseEntity<String> expand = exchange("/api/experiments/SEAL-7/blocks/1/expansions",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s7-expand"),
                "{\"baseVersion\":1,\"addedSeats\":2,\"expectedUnallocated\":0,"
                        + "\"sealKey\":\"key-v2\"}");
        assertEquals(201, expand.getStatusCode().value());
        assertEquals(6, json(expand).path("capacity").asInt());

        // 非最新版本不可再关闭：409
        ResponseEntity<String> closeOld = exchange(
                "/api/experiments/SEAL-7/blocks/1/versions/1/remainder-closure",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s7-close-old"), null);
        assertEquals(409, closeOld.getStatusCode().value());

        // 新分配使用版本2的序列（席位5），既有参与者不重排
        ResponseEntity<String> p4 = exchange(
                "/api/experiments/SEAL-7/participants/P4/allocations",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s7-alloc-4"), null);
        assertEquals(201, p4.getStatusCode().value());
        JsonNode p4Body = json(p4);
        assertEquals(1, p4Body.path("blockNo").asInt());
        assertEquals(2, p4Body.path("tableVersion").asInt());
        assertEquals(5, jdbc.queryForObject(
                "SELECT seq_no FROM allocation WHERE experiment_id = 'SEAL-7' "
                        + "AND participant_id = 'P4'", Integer.class));
        assertEquals(List.of(1, 2), jdbc.queryForList(
                "SELECT seq_no FROM allocation WHERE experiment_id = 'SEAL-7' "
                        + "AND participant_id IN ('P1','P2') ORDER BY id", Integer.class));
        assertEquals(List.of(1, 1), jdbc.queryForList(
                "SELECT table_version FROM allocation WHERE experiment_id = 'SEAL-7' "
                        + "AND participant_id IN ('P1','P2') ORDER BY id", Integer.class));
    }

    // ---------------- 幂等 ----------------

    @Test
    void seal_idempotency_replaySame_conflictDifferent_failureDoesNotOccupyKey()
            throws Exception {
        createExperiment("SEAL-8", 2, "req-s8-create");

        // 失败不占键：422 后同一 requestId 换正确参数可成功
        ResponseEntity<String> failed = exchange("/api/experiments/SEAL-8/blocks/1/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s8-idem"),
                "{\"sealKey\":\"key-v1\",\"expectedDigest\":\""
                        + "0000000000000000000000000000000000000000000000000000000000000000\"}");
        assertEquals(422, failed.getStatusCode().value());
        ResponseEntity<String> retried = exchange("/api/experiments/SEAL-8/blocks/1/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s8-idem"),
                "{\"sealKey\":\"key-v1\"}");
        assertEquals(201, retried.getStatusCode().value());

        // 同参重放：原样返回首次结果
        ResponseEntity<String> replay = exchange("/api/experiments/SEAL-8/blocks/1/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s8-idem"),
                "{\"sealKey\":\"key-v1\"}");
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(retried.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_version "
                        + "WHERE experiment_id = 'SEAL-8' AND block_no = 1", Integer.class));

        // 异参重放：409
        ResponseEntity<String> different = exchange("/api/experiments/SEAL-8/blocks/1/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s8-idem"),
                "{\"sealKey\":\"key-other\"}");
        assertEquals(409, different.getStatusCode().value());
    }

    // ---------------- 并发 ----------------

    @Test
    void concurrentSeal_sameBlock_exactlyOneSuccess() throws Exception {
        createExperiment("SEAL-9", 2, "req-s9-create");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String reqId = "req-s9-seal-" + i;
                futures.add(pool.submit(() -> exchange("/api/experiments/SEAL-9/blocks/1/seal",
                        HttpMethod.POST, headers("c1", "COORDINATOR", reqId),
                        "{\"sealKey\":\"key-conc\"}").getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, statuses.stream().filter(s -> s == 201).count(),
                    "并发封存同一区组仅一次成功");
            assertEquals(threads - 1L, statuses.stream().filter(s -> s == 409).count(),
                    "其余全部冲突 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_version "
                        + "WHERE experiment_id = 'SEAL-9' AND block_no = 1", Integer.class));
    }

    @Test
    void concurrentAllocation_sealedBlock_exactlyRemainingCapacitySucceeds() throws Exception {
        createExperiment("SEAL-11", 2, "req-s11-create");
        assertEquals(201, exchange("/api/experiments/SEAL-11/blocks/1/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s11-seal"),
                "{\"sealKey\":\"key-v1\"}").getStatusCode().value());

        // 10 个参与者并发：容量 8，恰好 8 成功，且同一序号最多一人
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                final String pid = "SP" + i;
                final String reqId = "req-s11-alloc-" + i;
                futures.add(pool.submit(() -> exchange(
                        "/api/experiments/SEAL-11/participants/" + pid + "/allocations",
                        HttpMethod.POST, headers("c1", "COORDINATOR", reqId), null)
                        .getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(8, statuses.stream().filter(s -> s == 201).count());
            assertEquals(2, statuses.stream().filter(s -> s == 422).count());
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        // 同一随机表序号最多一人成功
        List<Integer> duplicatedSeq = jdbc.queryForList(
                "SELECT seq_no FROM allocation WHERE experiment_id = 'SEAL-11' AND block_no = 1 "
                        + "GROUP BY seq_no HAVING COUNT(*) > 1", Integer.class);
        assertTrue(duplicatedSeq.isEmpty(), "同一序号最多一人成功");
        // 区组1的分配全部固化版本1
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE experiment_id = 'SEAL-11' "
                        + "AND block_no = 1 AND table_version = 1", Integer.class));
    }

    // ---------------- 揭盲不改写历史 ----------------

    @Test
    void unblind_afterSeal_doesNotRewriteAllocationHistory() throws Exception {
        createExperiment("SEAL-12", 2, "req-s12-create");
        assertEquals(201, exchange("/api/experiments/SEAL-12/blocks/1/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s12-seal"),
                "{\"sealKey\":\"key-v1\"}").getStatusCode().value());
        allocate("SEAL-12", "P1", "req-s12-alloc-1");

        ResponseEntity<String> apply = exchange(
                "/api/experiments/SEAL-12/participants/P1/unblind-requests",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s12-ub-apply"),
                "{\"reason\":\"疑似不良事件需揭盲\"}");
        assertEquals(201, apply.getStatusCode().value());
        String ubId = json(apply).path("requestId").asText();
        ResponseEntity<String> approve = exchange(
                "/api/unblind-requests/" + ubId + "/approval",
                HttpMethod.POST, headers("r1", "REVIEWER", "req-s12-ub-approve"), null);
        assertEquals(200, approve.getStatusCode().value());

        // 揭盲后分配历史不变：版本、序号、时间原样
        assertEquals(1, jdbc.queryForObject(
                "SELECT table_version FROM allocation WHERE experiment_id = 'SEAL-12' "
                        + "AND participant_id = 'P1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT seq_no FROM allocation WHERE experiment_id = 'SEAL-12' "
                        + "AND participant_id = 'P1'", Integer.class));
        assertEquals(1_700_000_000_000L, jdbc.queryForObject(
                "SELECT assigned_at FROM allocation WHERE experiment_id = 'SEAL-12' "
                        + "AND participant_id = 'P1'", Long.class));

        // 已揭盲区组（已有分配）封存：409
        ResponseEntity<String> sealAfter = exchange("/api/experiments/SEAL-12/blocks/1/seal",
                HttpMethod.POST, headers("c1", "COORDINATOR", "req-s12-seal-late"),
                "{\"sealKey\":\"key-late\"}");
        assertEquals(409, sealAfter.getStatusCode().value());
    }
}
