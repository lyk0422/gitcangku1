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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随机表封存与区组扩容：
 * 封存主流程与失败分支（409/422/400/403/404）、封存不可撤销、
 * 扩容版本隔离与守恒校验、历史分配固化不改写、幂等回放与并发封存。
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

    private ResponseEntity<String> post(String path, HttpHeaders headers, String body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody());
        return mapper.readTree(response.getBody());
    }

    private void createExperiment(String id, int blockCount, String requestId) {
        ResponseEntity<String> resp = post("/api/experiments/" + id,
                headers("c1", "COORDINATOR", requestId), "{\"blockCount\":" + blockCount + "}");
        assertEquals(201, resp.getStatusCode().value());
    }

    private void register(String expId, String participantId, String requestId) {
        ResponseEntity<String> resp = post(
                "/api/experiments/" + expId + "/participants/" + participantId + "/allocations",
                headers("c1", "COORDINATOR", requestId), null);
        assertEquals(201, resp.getStatusCode().value());
    }

    private JsonNode currentTable(String expId, int blockNo) throws Exception {
        ResponseEntity<String> resp = get(
                "/api/experiments/" + expId + "/blocks/" + blockNo + "/random-table",
                headers("c1", "COORDINATOR", null));
        assertEquals(200, resp.getStatusCode().value());
        return json(resp);
    }

    private ResponseEntity<String> seal(String expId, int blockNo, String requestId,
                                        String sealKey, String digest) {
        return post("/api/experiments/" + expId + "/blocks/" + blockNo + "/seal",
                headers("c1", "COORDINATOR", requestId),
                "{\"sealKey\":\"" + sealKey + "\",\"tableDigest\":\"" + digest + "\"}");
    }

    private long versionId(String expId, int blockNo, int versionNo) {
        return jdbc.queryForObject(
                "SELECT id FROM random_table_version WHERE experiment_id = ? "
                        + "AND block_no = ? AND version_no = ?",
                Long.class, expId, blockNo, versionNo);
    }

    @Test
    void seal_happyPath_recordPersistsAndViewHidesSequenceAndSealKey() throws Exception {
        createExperiment("EXP-S1", 2, "req-s1-create");

        JsonNode table = currentTable("EXP-S1", 1);
        assertEquals(1, table.path("versionNo").asInt());
        assertEquals(4, table.path("capacity").asInt());
        assertEquals("A,B", table.path("treatmentCodes").asText());
        assertFalse(table.path("sealed").asBoolean());
        assertEquals(0, table.path("allocatedCount").asLong());
        assertEquals(4, table.path("remainingCount").asLong());
        String digest = table.path("tableDigest").asText();
        assertTrue(digest.matches("[0-9a-f]{64}"), "摘要应为64位hex: " + digest);
        // 明细视图不得暴露具体序列
        assertFalse(table.has("seatNo"));
        assertFalse(table.has("seats"));
        assertFalse(table.has("sequence"));

        ResponseEntity<String> sealed = seal("EXP-S1", 1, "req-s1-seal", "SEAL-KEY-1", digest);
        assertEquals(201, sealed.getStatusCode().value());
        JsonNode sealBody = json(sealed);
        assertEquals("EXP-S1", sealBody.path("experimentId").asText());
        assertEquals(1, sealBody.path("blockNo").asInt());
        assertEquals(versionId("EXP-S1", 1, 1), sealBody.path("versionId").asLong());
        assertEquals(digest, sealBody.path("tableDigest").asText());
        assertEquals(4, sealBody.path("capacity").asInt());
        assertEquals("A,B", sealBody.path("treatmentCodes").asText());
        assertEquals("c1", sealBody.path("sealedActor").asText());
        assertEquals(1_700_000_000_000L, sealBody.path("sealedAt").asLong());
        // 封存记录不保存、不返回 sealKey
        assertFalse(sealBody.has("sealKey"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'random_table_seal' AND column_name LIKE '%key%'",
                Integer.class).intValue());

        // 封存历史可查，且查询不改变状态
        ResponseEntity<String> history = get("/api/experiments/EXP-S1/blocks/1/seals",
                headers("r1", "REVIEWER", null));
        assertEquals(200, history.getStatusCode().value());
        JsonNode seals = json(history);
        assertEquals(1, seals.size());
        assertEquals(digest, seals.get(0).path("tableDigest").asText());
        assertFalse(seals.get(0).has("sealKey"));
        assertTrue(currentTable("EXP-S1", 1).path("sealed").asBoolean());

        // 封存后新增分配按该版本剩余序列进行并固化版本与序号
        register("EXP-S1", "P1", "req-s1-alloc-1");
        register("EXP-S1", "P2", "req-s1-alloc-2");
        List<Integer> seqs = jdbc.queryForList(
                "SELECT seq_no FROM allocation WHERE experiment_id = 'EXP-S1' ORDER BY seq_no",
                Integer.class);
        assertEquals(List.of(1, 2), seqs);
        assertEquals(versionId("EXP-S1", 1, 1), jdbc.queryForObject(
                "SELECT table_version_id FROM allocation WHERE experiment_id = 'EXP-S1' "
                        + "AND participant_id = 'P1'", Long.class).longValue());
        JsonNode after = currentTable("EXP-S1", 1);
        assertEquals(2, after.path("allocatedCount").asLong());
        assertEquals(2, after.path("remainingCount").asLong());
    }

    @Test
    void seal_failures_digestMismatch409And422Branches_andFailureDoesNotHoldKey() throws Exception {
        createExperiment("EXP-S2", 2, "req-s2-create");
        String digest = currentTable("EXP-S2", 1).path("tableDigest").asText();
        String wrongDigest = "0".repeat(64);

        // 摘要不匹配 422；失败不占键，同键换正确摘要可成功
        ResponseEntity<String> mismatch = seal("EXP-S2", 1, "req-s2-seal", "K", wrongDigest);
        assertEquals(422, mismatch.getStatusCode().value());
        ResponseEntity<String> retry = seal("EXP-S2", 1, "req-s2-seal", "K", digest);
        assertEquals(201, retry.getStatusCode().value(), "失败不占幂等键，修正后应成功");

        // 重复封存（不可撤销）409
        ResponseEntity<String> again = seal("EXP-S2", 1, "req-s2-seal-again", "K2", digest);
        assertEquals(409, again.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_seal WHERE experiment_id = 'EXP-S2'",
                Integer.class));

        // 区组已有分配后封存 409（区组2未封存，先登记再封存）
        register("EXP-S2", "PX", "req-s2-alloc-x"); // 进入区组1（已满4席前）
        ResponseEntity<String> afterAlloc = seal("EXP-S2", 1, "req-s2-seal-b1", "K3",
                currentTable("EXP-S2", 1).path("tableDigest").asText());
        assertEquals(409, afterAlloc.getStatusCode().value());

        // 实验不存在 404；区组不存在 404
        assertEquals(404, seal("NOPE", 1, "req-s2-404", "K", digest).getStatusCode().value());
        assertEquals(404, seal("EXP-S2", 9, "req-s2-404b", "K", digest).getStatusCode().value());

        // REVIEWER 无权封存 403；参数校验 400
        ResponseEntity<String> forbidden = post("/api/experiments/EXP-S2/blocks/2/seal",
                headers("r1", "REVIEWER", "req-s2-403"),
                "{\"sealKey\":\"K\",\"tableDigest\":\"" + digest + "\"}");
        assertEquals(403, forbidden.getStatusCode().value());
        ResponseEntity<String> blankKey = post("/api/experiments/EXP-S2/blocks/2/seal",
                headers("c1", "COORDINATOR", "req-s2-400"),
                "{\"sealKey\":\"\",\"tableDigest\":\"" + digest + "\"}");
        assertEquals(400, blankKey.getStatusCode().value());
        ResponseEntity<String> badDigest = seal("EXP-S2", 2, "req-s2-400b", "K", "not-a-digest");
        assertEquals(400, badDigest.getStatusCode().value());
    }

    @Test
    void seal_closedExperiment_conflicts() throws Exception {
        createExperiment("EXP-S3", 2, "req-s3-create");
        String digest = currentTable("EXP-S3", 1).path("tableDigest").asText();
        assertEquals(200, post("/api/experiments/EXP-S3/close",
                headers("c1", "COORDINATOR", "req-s3-close"), null).getStatusCode().value());
        assertEquals(409, seal("EXP-S3", 1, "req-s3-seal", "K", digest).getStatusCode().value());
    }

    @Test
    void expand_happyPath_versionIsolatedAndHistoryFrozen() throws Exception {
        createExperiment("EXP-E1", 2, "req-e1-create");
        String digest = currentTable("EXP-E1", 1).path("tableDigest").asText();
        assertEquals(201, seal("EXP-E1", 1, "req-e1-seal", "K", digest).getStatusCode().value());
        long v1 = versionId("EXP-E1", 1, 1);

        // 封存后先分配两席（占用 v1 序号 1、2）
        register("EXP-E1", "P1", "req-e1-alloc-1");
        register("EXP-E1", "P2", "req-e1-alloc-2");
        String p1BlindCode = jdbc.queryForObject(
                "SELECT blind_code FROM allocation WHERE experiment_id = 'EXP-E1' "
                        + "AND participant_id = 'P1'", String.class);

        // 扩容：新容量 8，已分配 2，未分配名额 = 6
        ResponseEntity<String> expanded = post("/api/experiments/EXP-E1/blocks/1/expansions",
                headers("c1", "COORDINATOR", "req-e1-expand"),
                "{\"predecessorVersionId\":" + v1
                        + ",\"addedSeats\":4,\"expectedUnallocated\":6}");
        assertEquals(201, expanded.getStatusCode().value());
        JsonNode v2 = json(expanded);
        assertEquals(2, v2.path("versionNo").asInt());
        assertEquals(8, v2.path("capacity").asInt());
        assertEquals(v1, v2.path("predecessorVersionId").asLong());
        assertEquals("A,B", v2.path("treatmentCodes").asText());
        assertFalse(v2.path("sealed").asBoolean());
        assertEquals(2, v2.path("allocatedCount").asLong());
        assertEquals(6, v2.path("remainingCount").asLong());
        long v2Id = v2.path("versionId").asLong();

        // 新席位 5~8 挂到 v2，前半 A 后半 B；旧席位仍挂 v1，内容不变
        assertEquals(8, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXP-E1' AND block_no = 1",
                Integer.class));
        List<String> newTreatments = jdbc.queryForList(
                "SELECT treatment FROM seat WHERE experiment_id = 'EXP-E1' AND block_no = 1 "
                        + "AND seat_no BETWEEN 5 AND 8 ORDER BY seat_no", String.class);
        assertEquals(List.of("A", "A", "B", "B"), newTreatments);
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXP-E1' AND block_no = 1 "
                        + "AND version_id = " + v1, Integer.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXP-E1' AND block_no = 1 "
                        + "AND version_id = " + v2Id, Integer.class));

        // 历史分配固化：版本、序号、盲码均未被扩容改写
        assertEquals(v1, jdbc.queryForObject(
                "SELECT table_version_id FROM allocation WHERE experiment_id = 'EXP-E1' "
                        + "AND participant_id = 'P1'", Long.class).longValue());
        assertEquals(1, jdbc.queryForObject(
                "SELECT seq_no FROM allocation WHERE experiment_id = 'EXP-E1' "
                        + "AND participant_id = 'P1'", Integer.class).intValue());
        assertEquals(p1BlindCode, jdbc.queryForObject(
                "SELECT blind_code FROM allocation WHERE experiment_id = 'EXP-E1' "
                        + "AND participant_id = 'P1'", String.class));

        // 扩容后分配继续按剩余序列：P3、P4 落在 v1 序号 3、4；P5 起进入 v2 序号 5
        register("EXP-E1", "P3", "req-e1-alloc-3");
        register("EXP-E1", "P4", "req-e1-alloc-4");
        register("EXP-E1", "P5", "req-e1-alloc-5");
        assertEquals(v1, jdbc.queryForObject(
                "SELECT table_version_id FROM allocation WHERE experiment_id = 'EXP-E1' "
                        + "AND participant_id = 'P4'", Long.class).longValue());
        assertEquals(v2Id, jdbc.queryForObject(
                "SELECT table_version_id FROM allocation WHERE experiment_id = 'EXP-E1' "
                        + "AND participant_id = 'P5'", Long.class).longValue());
        assertEquals(5, jdbc.queryForObject(
                "SELECT seq_no FROM allocation WHERE experiment_id = 'EXP-E1' "
                        + "AND participant_id = 'P5'", Integer.class).intValue());

        // 版本历史：两个版本，按版本号升序
        ResponseEntity<String> versions = get("/api/experiments/EXP-E1/blocks/1/random-table/versions",
                headers("r1", "REVIEWER", null));
        assertEquals(200, versions.getStatusCode().value());
        JsonNode versionList = json(versions);
        assertEquals(2, versionList.size());
        assertEquals(1, versionList.get(0).path("versionNo").asInt());
        assertEquals(2, versionList.get(1).path("versionNo").asInt());
        assertTrue(versionList.get(0).path("sealed").asBoolean());
        assertFalse(versionList.get(1).path("sealed").asBoolean());

        // 诊断：容量、席位行数、分配计数交叉核对一致；只读不改变状态
        ResponseEntity<String> diag = get("/api/experiments/EXP-E1/blocks/1/random-table/diagnostics",
                headers("c1", "COORDINATOR", null));
        assertEquals(200, diag.getStatusCode().value());
        JsonNode d = json(diag);
        assertEquals(v2Id, d.path("currentVersionId").asLong());
        assertEquals(2, d.path("versionCount").asInt());
        assertEquals(8, d.path("capacity").asInt());
        assertEquals(8, d.path("seatRows").asLong());
        assertEquals(5, d.path("allocatedCount").asLong());
        assertEquals(3, d.path("remainingCount").asLong());
        assertFalse(d.path("currentVersionSealed").asBoolean());
        assertEquals(1, d.path("sealCount").asInt());
        assertTrue(d.path("capacityConserved").asBoolean());
        ResponseEntity<String> diagAgain = get(
                "/api/experiments/EXP-E1/blocks/1/random-table/diagnostics",
                headers("c1", "COORDINATOR", null));
        assertEquals(diag.getBody(), diagAgain.getBody(), "诊断查询只读，重复读取结果一致");
    }

    @Test
    void expand_failures_unsealedNotLatestConservation_andNoPartialWrites() throws Exception {
        createExperiment("EXP-E2", 2, "req-e2-create");
        long v1 = versionId("EXP-E2", 1, 1);

        // 前驱未封存 409
        ResponseEntity<String> unsealed = post("/api/experiments/EXP-E2/blocks/1/expansions",
                headers("c1", "COORDINATOR", "req-e2-unsealed"),
                "{\"predecessorVersionId\":" + v1 + ",\"addedSeats\":4,\"expectedUnallocated\":8}");
        assertEquals(409, unsealed.getStatusCode().value());

        // 前驱不存在 404；前驱属于其他区组 404
        String digest = currentTable("EXP-E2", 1).path("tableDigest").asText();
        assertEquals(201, seal("EXP-E2", 1, "req-e2-seal", "K", digest).getStatusCode().value());
        String digest2 = currentTable("EXP-E2", 2).path("tableDigest").asText();
        assertEquals(201, seal("EXP-E2", 2, "req-e2-seal-b2", "K", digest2).getStatusCode().value());
        long otherBlockVersion = versionId("EXP-E2", 2, 1);
        ResponseEntity<String> notFound = post("/api/experiments/EXP-E2/blocks/1/expansions",
                headers("c1", "COORDINATOR", "req-e2-nf"),
                "{\"predecessorVersionId\":999999,\"addedSeats\":4,\"expectedUnallocated\":8}");
        assertEquals(404, notFound.getStatusCode().value());
        ResponseEntity<String> wrongBlock = post("/api/experiments/EXP-E2/blocks/1/expansions",
                headers("c1", "COORDINATOR", "req-e2-wrongblock"),
                "{\"predecessorVersionId\":" + otherBlockVersion
                        + ",\"addedSeats\":4,\"expectedUnallocated\":8}");
        assertEquals(404, wrongBlock.getStatusCode().value());

        // 守恒校验失败 422：响应含声明值与实际值，且整次回滚查询不到半成品
        ResponseEntity<String> notConserved = post("/api/experiments/EXP-E2/blocks/1/expansions",
                headers("c1", "COORDINATOR", "req-e2-conservation"),
                "{\"predecessorVersionId\":" + v1 + ",\"addedSeats\":4,\"expectedUnallocated\":7}");
        assertEquals(422, notConserved.getStatusCode().value());
        String errorBody = notConserved.getBody();
        assertNotNull(errorBody);
        assertTrue(errorBody.contains("7"), "错误响应应包含声明的未分配名额");
        assertTrue(errorBody.contains("8"), "错误响应应包含实际未分配名额");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_version WHERE experiment_id = 'EXP-E2' "
                        + "AND block_no = 1", Integer.class), "失败整次回滚，不得残留新版本");
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXP-E2' AND block_no = 1",
                Integer.class), "失败整次回滚，不得残留新席位");

        // addedSeats 为奇数 400
        ResponseEntity<String> odd = post("/api/experiments/EXP-E2/blocks/1/expansions",
                headers("c1", "COORDINATOR", "req-e2-odd"),
                "{\"predecessorVersionId\":" + v1 + ",\"addedSeats\":3,\"expectedUnallocated\":7}");
        assertEquals(400, odd.getStatusCode().value());

        // 成功扩容后，再次引用 v1（已被取代）409
        assertEquals(201, post("/api/experiments/EXP-E2/blocks/1/expansions",
                headers("c1", "COORDINATOR", "req-e2-expand"),
                "{\"predecessorVersionId\":" + v1 + ",\"addedSeats\":4,\"expectedUnallocated\":8}")
                .getStatusCode().value());
        ResponseEntity<String> stale = post("/api/experiments/EXP-E2/blocks/1/expansions",
                headers("c1", "COORDINATOR", "req-e2-stale"),
                "{\"predecessorVersionId\":" + v1 + ",\"addedSeats\":4,\"expectedUnallocated\":12}");
        assertEquals(409, stale.getStatusCode().value());
    }

    @Test
    void sealAndExpand_idempotency_replaySameResultAndDifferentParamsConflict() throws Exception {
        createExperiment("EXP-I1", 2, "req-i1-create");
        String digest = currentTable("EXP-I1", 1).path("tableDigest").asText();

        ResponseEntity<String> first = seal("EXP-I1", 1, "req-i1-seal", "K1", digest);
        assertEquals(201, first.getStatusCode().value());
        // 同键同参重放：原样返回首次结果，不产生第二条封存记录
        ResponseEntity<String> replay = seal("EXP-I1", 1, "req-i1-seal", "K1", digest);
        assertEquals(201, replay.getStatusCode().value());
        assertEquals(first.getBody(), replay.getBody());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_seal WHERE experiment_id = 'EXP-I1'",
                Integer.class));
        // 同键异参 409
        ResponseEntity<String> conflict = seal("EXP-I1", 1, "req-i1-seal", "K2", digest);
        assertEquals(409, conflict.getStatusCode().value());

        // 扩容幂等：同键重放不重复建版本
        long v1 = versionId("EXP-I1", 1, 1);
        String expandBody = "{\"predecessorVersionId\":" + v1
                + ",\"addedSeats\":2,\"expectedUnallocated\":6}";
        ResponseEntity<String> expandFirst = post("/api/experiments/EXP-I1/blocks/1/expansions",
                headers("c1", "COORDINATOR", "req-i1-expand"), expandBody);
        assertEquals(201, expandFirst.getStatusCode().value());
        ResponseEntity<String> expandReplay = post("/api/experiments/EXP-I1/blocks/1/expansions",
                headers("c1", "COORDINATOR", "req-i1-expand"), expandBody);
        assertEquals(201, expandReplay.getStatusCode().value());
        assertEquals(expandFirst.getBody(), expandReplay.getBody());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_version WHERE experiment_id = 'EXP-I1' "
                        + "AND block_no = 1", Integer.class));
        assertEquals(6, jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = 'EXP-I1' AND block_no = 1",
                Integer.class));
    }

    @Test
    void concurrentSeal_sameVersion_exactlyOneSucceeds() throws Exception {
        createExperiment("EXP-C1", 2, "req-c1-create");
        String digest = currentTable("EXP-C1", 1).path("tableDigest").asText();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String reqId = "req-c1-seal-" + i;
                futures.add(pool.submit(() -> seal("EXP-C1", 1, reqId, "K" + reqId, digest)
                        .getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long success = statuses.stream().filter(s -> s == 201).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, success, "并发封存同一版本至多一次成功");
            assertEquals(threads - 1L, conflict, "其余并发封存全部 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM random_table_seal WHERE experiment_id = 'EXP-C1'",
                Integer.class));
    }
}
