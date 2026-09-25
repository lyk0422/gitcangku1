package com.example.starter.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 返工重投代次与召回闭包扩展测试：返工代次生成与重新放行、同批仅返工一次及 reworkKey/commandKey
 * 幂等、返工前置状态 409、代次上限 422、闭包沿返工边扩展并同事务标记已放行后代为待处置、
 * 返工链/闭包查询、失败整次回滚、返工与祖先召回并发按提交顺序裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchReworkReentryTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM rework_order");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 返工代次生成与重新放行 ----------

    @Test
    void rework_rejectedBatch_createsNextGeneration_sourceBecomesReworked_andRerunsFullFlow()
            throws Exception {
        String source = "BK-RW-S-" + unique();
        createBatch(source, List.of("外观", "含量"), 201);
        submitTest(source, "外观", "PASS", "insp-1", 201);
        submitTest(source, "含量", "FAIL", "insp-2", 201);
        assertEquals("REJECTED", currentStatus(source));

        String reworkKey = "RK-1-" + unique();
        String rwBatch = "BK-RW-N-" + unique();
        MvcResult result = rework(source, "CK-RW-1", reworkKey, rwBatch, "LOT-RW-1",
                "含量不合格返工", 201);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(reworkKey, body.path("reworkKey").asText());
        assertEquals(source, body.path("sourceBatchKey").asText());
        assertEquals("REWORKED", body.path("sourceStatus").asText());
        assertEquals(rwBatch, body.path("reworkBatchKey").asText());
        assertEquals(1, body.path("generation").asInt());
        assertEquals(List.of("外观", "含量"), objectMapper.convertValue(body.path("requiredTests"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));

        // 原批次终态 REWORKED，不再可用；新返工批次代次 1、初始隔离、出现在可用列表
        assertEquals("REWORKED", currentStatus(source));
        assertFalse(availableKeys().contains(source));
        assertEquals(1, currentGeneration(rwBatch));
        assertEquals("QUARANTINED", currentStatus(rwBatch));
        assertTrue(availableKeys().contains(rwBatch));

        // 唯一父批为原批次，血缘边类型为 REWORK
        assertEquals(source, jdbc.queryForObject(
                "SELECT parent_key FROM batch_lineage WHERE child_key = ?", String.class, rwBatch));
        assertEquals("REWORK", jdbc.queryForObject(
                "SELECT edge_type FROM batch_lineage WHERE child_key = ?", String.class, rwBatch));

        // 新批次不继承检验结论与批准：历史为空，须重新执行全部必做检验与双角色放行
        JsonNode rwHistory = history(rwBatch);
        assertEquals(0, rwHistory.path("tests").size());
        assertEquals(0, rwHistory.path("approvals").size());
        releaseBatch(rwBatch, "insp-rw");
        assertEquals("RELEASED", currentStatus(rwBatch));

        // 原批次历史仍完整保留其不合格检验，不被删除或改写
        JsonNode srcHistory = history(source);
        assertEquals(2, srcHistory.path("tests").size());
        assertEquals("REWORKED", srcHistory.path("batch").path("status").asText());

        // REWORKED 终态不可再放行、拆分
        submitTest(source, "外观", "PASS", "insp-x", 409);
        split(source, "CK-RW-SPLIT", twoChildren(), 409);
    }

    // ---------- 同一批次只能返工一次与幂等 ----------

    @Test
    void sameBatch_reworkedOnce_replayReturnsFirstResult_newReworkKeyConflicts() throws Exception {
        String source = "BK-IDEM-S-" + unique();
        createRejected(source, "insp");
        String reworkKey = "RK-IDEM-" + unique();
        String rwBatch = "BK-IDEM-N-" + unique();
        String reqBody = reworkBody("CK-IDEM", reworkKey, rwBatch, "LOT-RW", "返工说明");

        MvcResult first = mockMvc.perform(post("/api/batches/" + source + "/rework")
                        .contentType(MediaType.APPLICATION_JSON).content(reqBody))
                .andExpect(status().isCreated()).andReturn();
        // 同 commandKey 同参重放 → 首次结果
        MvcResult replay = mockMvc.perform(post("/api/batches/" + source + "/rework")
                        .contentType(MediaType.APPLICATION_JSON).content(reqBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());

        // 同 reworkKey 换 commandKey 再提交（同参）→ 仍幂等返回首次结果
        MvcResult replayByReworkKey = mockMvc.perform(post("/api/batches/" + source + "/rework")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reworkBody("CK-OTHER", reworkKey, rwBatch, "LOT-RW", "返工说明")))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replayByReworkKey.getResponse().getContentAsString());

        // 同 commandKey 改参 → 409
        mockMvc.perform(post("/api/batches/" + source + "/rework")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reworkBody("CK-IDEM", reworkKey, "BK-IDEM-CHANGED-" + unique(),
                                "LOT-RW", "返工说明")))
                .andExpect(status().isConflict());
        // 同 reworkKey 改参 → 409
        mockMvc.perform(post("/api/batches/" + source + "/rework")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reworkBody("CK-OTHER2", reworkKey, rwBatch, "LOT-RW", "改过的说明")))
                .andExpect(status().isConflict());

        // 换新 reworkKey 对已返工批次再次提交 → 409
        rework(source, "CK-IDEM2", "RK-NEW-" + unique(), "BK-IDEM-N2-" + unique(), "L2", "再返工",
                409);

        // 仅一次返工登记、一条返工边、一个返工批次
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rework_order WHERE source_batch_key = ?", Integer.class, source));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ? AND edge_type = 'REWORK'",
                Integer.class, source));
        assertEquals("REWORKED", currentStatus(source));
    }

    @Test
    void reworkConflict_failureDoesNotOccupyKey_andRollsBackWholeTransaction() throws Exception {
        String source = "BK-ROLL-S-" + unique();
        createRejected(source, "insp");
        String occupied = "BK-ROLL-EXIST-" + unique();
        createBatch(occupied, List.of("t1"), 201);

        // 返工批次键已存在 → 整次 409 回滚：原批次仍 REJECTED，无血缘边/返工登记/新批次
        rework(source, "CK-ROLL", "RK-ROLL-" + unique(), occupied, "LOT", "说明", 409);
        assertEquals("REJECTED", currentStatus(source));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rework_order WHERE source_batch_key = ?", Integer.class, source));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, source));

        // 失败不占键：同一 commandKey/reworkKey 修正返工批次键后成功
        String rwBatch = "BK-ROLL-NEW-" + unique();
        rework(source, "CK-ROLL", "RK-ROLL-" + unique(), rwBatch, "LOT", "说明", 201);
        assertEquals("REWORKED", currentStatus(source));
        assertEquals("QUARANTINED", currentStatus(rwBatch));
    }

    // ---------- 返工前置状态 ----------

    @Test
    void rework_wrongStatus_returns409() throws Exception {
        // QUARANTINED：尚未完成检验
        String quarantined = "BK-WS-Q-" + unique();
        createBatch(quarantined, List.of("t1"), 201);
        rework(quarantined, "CK-WS-Q", "RK-WS-Q-" + unique(), "BK-WS-QN-" + unique(), "L", "r", 409);

        // PENDING_RELEASE：检验合格待放行，不得返工
        String pending = "BK-WS-P-" + unique();
        createBatch(pending, List.of("t1"), 201);
        submitTest(pending, "t1", "PASS", "insp", 201);
        rework(pending, "CK-WS-P", "RK-WS-P-" + unique(), "BK-WS-PN-" + unique(), "L", "r", 409);

        // RELEASED：已放行不得返工
        String released = "BK-WS-R-" + unique();
        createBatch(released, List.of("t1"), 201);
        releaseBatch(released, "insp");
        rework(released, "CK-WS-R", "RK-WS-R-" + unique(), "BK-WS-RN-" + unique(), "L", "r", 409);

        // SPLIT：已拆分不得返工
        String split = "BK-WS-S-" + unique();
        createBatch(split, List.of("t1"), 201);
        releaseBatch(split, "insp");
        split(split, "CK-WS-S", twoChildren(), 201);
        rework(split, "CK-WS-S2", "RK-WS-S-" + unique(), "BK-WS-SN-" + unique(), "L", "r", 409);

        // RECALLED：已召回不得返工
        String recalled = "BK-WS-RC-" + unique();
        createBatch(recalled, List.of("t1"), 201);
        releaseBatch(recalled, "insp");
        recall(recalled, "u", "召回", "CK-WS-RC", 201);
        rework(recalled, "CK-WS-RC2", "RK-WS-RC-" + unique(), "BK-WS-RCN-" + unique(), "L", "r", 409);

        // 不存在 → 404
        rework("NO-SUCH-BATCH", "CK-WS-404", "RK-WS-404-" + unique(),
                "BK-WS-404N-" + unique(), "L", "r", 404);
    }

    @Test
    void rework_invalidParams_returns400() throws Exception {
        String source = "BK-400-S-" + unique();
        createRejected(source, "insp");
        // 缺 reworkKey
        mockMvc.perform(post("/api/batches/" + source + "/rework")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-400-1\",\"reworkBatchKey\":\"BK-400-N-"
                                + unique() + "\",\"reworkBatchNo\":\"L\",\"reason\":\"r\"}"))
                .andExpect(status().isBadRequest());
        // 缺返工说明
        reworkRaw(source, "{\"commandKey\":\"CK-400-2\",\"reworkKey\":\"RK-400-" + unique()
                + "\",\"reworkBatchKey\":\"BK-400-N2-" + unique()
                + "\",\"reworkBatchNo\":\"L\"}", 400);
        assertEquals("REJECTED", currentStatus(source));
    }

    // ---------- 代次上限 ----------

    @Test
    void rework_generationCapThree_fourthReworkReturns422WithCurrentGeneration() throws Exception {
        String b0 = "BK-GEN-0-" + unique();
        createRejected(b0, "insp-0");

        String b1 = "BK-GEN-1-" + unique();
        rework(b0, "CK-GEN-1", "RK-GEN-1-" + unique(), b1, "L1", "r1", 201);
        assertEquals(1, currentGeneration(b1));
        reject(b1, "insp-1");

        String b2 = "BK-GEN-2-" + unique();
        rework(b1, "CK-GEN-2", "RK-GEN-2-" + unique(), b2, "L2", "r2", 201);
        assertEquals(2, currentGeneration(b2));
        reject(b2, "insp-2");

        String b3 = "BK-GEN-3-" + unique();
        rework(b2, "CK-GEN-3", "RK-GEN-3-" + unique(), b3, "L3", "r3", 201);
        assertEquals(3, currentGeneration(b3));
        reject(b3, "insp-3");

        // 代次 3 的批次再返工将产生代次 4 → 422，消息给出当前代次 3；原批次状态不变
        MvcResult blocked = rework(b3, "CK-GEN-4", "RK-GEN-4-" + unique(),
                "BK-GEN-4-" + unique(), "L4", "r4", 422);
        JsonNode err = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertTrue(err.path("message").asText().contains("3"), "422 消息须给出当前代次 3");
        assertEquals("REJECTED", currentStatus(b3));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rework_order", Integer.class));

        // 返工链明细：b3 所在链共 3 次返工，代次 1→2→3
        JsonNode chain = getJson("/api/batches/" + b3 + "/rework-chain");
        assertEquals(3, chain.size());
        assertEquals(1, chain.get(0).path("generation").asInt());
        assertEquals(b0, chain.get(0).path("sourceBatchKey").asText());
        assertEquals(b1, chain.get(0).path("reworkBatchKey").asText());
        assertEquals(2, chain.get(1).path("generation").asInt());
        assertEquals(3, chain.get(2).path("generation").asInt());
        assertEquals(b3, chain.get(2).path("reworkBatchKey").asText());
    }

    // ---------- 召回闭包沿返工边扩展 ----------

    @Test
    void recallClosure_expandsAlongReworkEdges_releasedDescendantsMarkedForDisposal()
            throws Exception {
        // root(SPLIT) --SPLIT--> childA(REWORKED) --REWORK--> rw1(SPLIT) --SPLIT--> g1(RELEASED), g2(QUARANTINED)
        //                    \--SPLIT--> childB(QUARANTINED)
        String root = "BK-CL-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-root");
        String childA = "BK-CL-A-" + unique();
        String childB = "BK-CL-B-" + unique();
        split(root, "CK-CL-S1",
                List.of(new String[]{childA, "LA"}, new String[]{childB, "LB"}), 201);

        reject(childA, "insp-a");
        String rw1 = "BK-CL-RW-" + unique();
        rework(childA, "CK-CL-RW", "RK-CL-" + unique(), rw1, "LRW", "返工", 201);
        releaseBatch(rw1, "insp-rw");

        String g1 = "BK-CL-G1-" + unique();
        String g2 = "BK-CL-G2-" + unique();
        split(rw1, "CK-CL-S2", List.of(new String[]{g1, "G1"}, new String[]{g2, "G2"}), 201);
        releaseBatch(g1, "insp-g1");
        // g2 保持 QUARANTINED

        recall(root, "qa-lead", "上游污染", "CK-CL-RC", 201);

        // 闭包查询包含起点自身与全部 5 个后代（跨 SPLIT/REWORK 混合边）
        JsonNode closure = getJson("/api/batches/" + root + "/recall-closure");
        assertEquals(root, closure.path("rootBatchKey").asText());
        List<String> closureKeys = new ArrayList<>();
        closure.path("entries").forEach(n -> closureKeys.add(n.path("batchKey").asText()));
        assertEquals(6, closureKeys.size());
        assertTrue(closureKeys.containsAll(List.of(root, childA, childB, rw1, g1, g2)));

        // 起点直接召回；闭包内唯一已放行后代 g1 在同一事务标记为待处置
        assertEquals("RECALLED", currentStatus(root));
        assertEquals("PENDING_DISPOSAL", currentStatus(g1));
        // 非放行后代状态不改写：childA REWORKED、rw1 SPLIT、g2/childB QUARANTINED
        assertEquals("REWORKED", currentStatus(childA));
        assertEquals("SPLIT", currentStatus(rw1));
        assertEquals("QUARANTINED", currentStatus(g2));
        assertEquals("QUARANTINED", currentStatus(childB));

        // 待处置批次立即不可用；其放行/检验/拆分因祖先召回闭包被拦截（422）
        assertFalse(availableKeys().contains(g1));
        assertFalse(availableKeys().contains(g2));
        approve(g1, "qa-x", "QUALITY", "CK-CL-A1", 422);
        submitTest(g1, "t1", "PASS", "insp-x", 422);
        split(g1, "CK-CL-S3", twoChildren(), 422);
        // 未放行后代 g2 因祖先召回被拦截新增检验（422）
        submitTest(g2, "t1", "PASS", "insp-g2", 422);

        // 闭包条目标注：起点 directlyRecalled；g1 markedForDisposal；
        // 后代的 unavailableDueToRecalledAncestor 均指向 root
        for (JsonNode entry : closure.path("entries")) {
            String key = entry.path("batchKey").asText();
            if (key.equals(root)) {
                assertTrue(entry.path("directlyRecalled").asBoolean());
                assertTrue(entry.path("unavailableDueToRecalledAncestor").isNull());
            } else {
                assertFalse(entry.path("directlyRecalled").asBoolean());
                assertEquals(root, entry.path("unavailableDueToRecalledAncestor").asText());
            }
            assertEquals(key.equals(g1), entry.path("markedForDisposal").asBoolean());
        }

        // 血缘边与历史记录不被删除或改写：仍只有一条召回记录、全部边保留
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recall", Integer.class));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage", Integer.class));
        assertEquals(2, history(g1).path("approvals").size());
    }

    // ---------- 并发裁决 ----------

    @Test
    void concurrentReworkAndAncestorRecall_commitOrderDecides() throws Exception {
        // root(RELEASED) --SPLIT--> child(REJECTED)；返工 child 与召回 root 并发
        String root = "BK-CRRW-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String child = "BK-CRRW-C-" + unique();
        split(root, "CK-CRRW-S",
                List.of(new String[]{child, "L1"}, new String[]{"BK-CRRW-C2-" + unique(), "L2"}),
                201);
        reject(child, "insp-2");

        String rwBatch = "BK-CRRW-N-" + unique();
        String reworkBody = reworkBody("CK-CRRW-RW", "RK-CRRW-" + unique(), rwBatch, "LRW", "返工");
        String recallBody = "{\"commandKey\":\"CK-CRRW-RC\",\"reason\":\"根批召回\"}";

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + child + "/rework")
                        .contentType(MediaType.APPLICATION_JSON).content(reworkBody)),
                () -> callStatus(post("/api/batches/" + root + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON).content(recallBody))
        );

        int rework = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall, "RELEASED 根批始终可召回");
        if (rework == 201) {
            // 返工先提交：召回闭包必须包含新返工批次，新批次立即不可用且新增检验被拦截
            assertEquals("REWORKED", currentStatus(child));
            assertEquals("QUARANTINED", currentStatus(rwBatch));
            JsonNode closure = getJson("/api/batches/" + root + "/recall-closure");
            List<String> keys = new ArrayList<>();
            closure.path("entries").forEach(n -> keys.add(n.path("batchKey").asText()));
            assertTrue(keys.contains(rwBatch), "返工先提交时召回闭包须包含新返工批次");
            submitTest(rwBatch, "t1", "PASS", "insp-3", 422);
        } else {
            // 召回先提交：返工被祖先召回拦截 422，子批停留 REJECTED，无返工批次与边
            assertEquals(422, rework);
            assertEquals("REJECTED", currentStatus(child));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, rwBatch));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch_lineage WHERE child_key = ?", Integer.class, rwBatch));
        }
    }

    @Test
    void concurrentAncestorRecallAndReworkBatchFinalApproval_commitOrderDecides() throws Exception {
        // root(RELEASED) -> child(REJECTED) -> rw1(RELEASE_REVIEW)；终批与召回 root 并发
        String root = "BK-CRFA-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String child = "BK-CRFA-C-" + unique();
        split(root, "CK-CRFA-S",
                List.of(new String[]{child, "L1"}, new String[]{"BK-CRFA-C2-" + unique(), "L2"}),
                201);
        reject(child, "insp-2");
        String rw1 = "BK-CRFA-RW-" + unique();
        rework(child, "CK-CRFA-RW", "RK-CRFA-" + unique(), rw1, "LRW", "返工", 201);
        submitTest(rw1, "t1", "PASS", "insp-3", 201);
        approve(rw1, "qa-1", "QUALITY", "CK-CRFA-A1", 201);
        assertEquals("RELEASE_REVIEW", currentStatus(rw1));

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + rw1 + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CRFA-A2\"}")),
                () -> callStatus(post("/api/batches/" + root + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CRFA-R\",\"reason\":\"根批召回\"}"))
        );

        int finalApproval = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall);
        if (finalApproval == 201) {
            // 返工批次放行先提交：一度 RELEASED，召回闭包将其同事务标记为待处置
            assertEquals("PENDING_DISPOSAL", currentStatus(rw1));
            assertFalse(availableKeys().contains(rw1));
        } else {
            // 召回先提交：返工批次放行被拦截 422，停留 RELEASE_REVIEW
            assertEquals(422, finalApproval);
            assertEquals("RELEASE_REVIEW", currentStatus(rw1));
        }
        assertFalse(finalApproval == 201 && availableKeys().contains(rw1));
    }

    @Test
    void concurrentReworkSameReworkKey_bothReturnFirstResult() throws Exception {        String source = "BK-CRW-S-" + unique();
        createRejected(source, "insp");
        String rwBatch = "BK-CRW-N-" + unique();
        String body = reworkBody("CK-CRW", "RK-CRW-" + unique(), rwBatch, "LRW", "返工");

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + source + "/rework")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + source + "/rework")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发返工均返回首次结果");
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rework_order WHERE source_batch_key = ?", Integer.class, source));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, rwBatch));
        assertEquals("REWORKED", currentStatus(source));
    }

    // ---------- 处置失败整次回滚（真实 H2 触发器制造数据库失败） ----------

    @Test
    void recall_whenOneDescendantDisposalFails_entireTransactionRollsBack() throws Exception {
        // root(RELEASED) -> childA(RELEASED)、childB(RELEASED)；令 childA 的处置更新失败
        String root = "BK-RB-R-" + unique();
        createBatch(root, List.of("t1"), 201);
        releaseBatch(root, "insp-1");
        String childA = "BK-RB-A-" + unique();
        String childB = "BK-RB-B-" + unique();
        split(root, "CK-RB-S",
                List.of(new String[]{childA, "LA"}, new String[]{childB, "LB"}), 201);
        releaseBatch(childA, "insp-2");
        releaseBatch(childB, "insp-3");

        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_disposal");
        jdbc.execute("CREATE TRIGGER trg_fail_disposal BEFORE UPDATE ON batch FOR EACH ROW CALL \""
                + FailUpdateTrigger.class.getName() + "\"");
        FailUpdateTrigger.failKey = childA;
        try {
            // 召回在处置 childA 时触发数据库失败，异常向上抛出（非幂等业务错误，不写 command_log）
            Exception ex = assertThrows(Exception.class, () -> mockMvc.perform(
                    post("/api/batches/" + root + "/recall")
                            .header("X-Actor-Id", "u")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandKey\":\"CK-RB-RC\",\"reason\":\"根批召回\"}")));
            assertTrue(hasMessageContaining(ex, "模拟待处置更新失败"),
                    "失败应由待处置更新触发，实际: " + ex);
        } finally {
            FailUpdateTrigger.failKey = null;
            jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_disposal");
        }

        // 整次回滚：root 仍 SPLIT、两个已放行后代仍 RELEASED（无召回/待处置标记）
        assertEquals("SPLIT", currentStatus(root));
        assertEquals("RELEASED", currentStatus(childA));
        assertEquals("RELEASED", currentStatus(childB));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM recall", Integer.class));
        // 失败不占键：清除故障后同 commandKey 可成功召回
        recall(root, "u", "根批召回", "CK-RB-RC", 201);
        assertEquals("RECALLED", currentStatus(root));
        assertEquals("PENDING_DISPOSAL", currentStatus(childA));
        assertEquals("PENDING_DISPOSAL", currentStatus(childB));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private void createBatch(String batchKey, List<String> items, int expected) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), items));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    /**
     * 单必做项批次提交 FAIL 直接进入 REJECTED（该必做项已登记结论）。
     */
    private void createRejected(String batchKey, String inspector) throws Exception {
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "t1", "FAIL", inspector, 201);
        assertEquals("REJECTED", currentStatus(batchKey));
    }

    private void reject(String batchKey, String inspector) throws Exception {
        submitTest(batchKey, "t1", "FAIL", inspector, 201);
        assertEquals("REJECTED", currentStatus(batchKey));
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TestCmd("CK-T-" + unique(), "TK-" + unique(), item, result,
                                        inspector))))
                .andExpect(status().is(expected));
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private void releaseBatch(String batchKey, String inspector) throws Exception {
        JsonNode node = history(batchKey);
        for (JsonNode item : node.path("batch").path("requiredTests")) {
            submitTest(batchKey, item.asText(), "PASS", inspector, 201);
        }
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
    }

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private void recall(String batchKey, String actor, String reason, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected));
    }

    private List<String[]> twoChildren() {
        return List.of(new String[]{"BK-CH1-" + unique(), "L1"},
                new String[]{"BK-CH2-" + unique(), "L2"});
    }

    private String splitBody(String commandKey, List<String[]> children) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"children\":[");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(children.get(i)[0])
                    .append("\",\"batchNo\":\"").append(children.get(i)[1]).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private void split(String parentKey, String commandKey, List<String[]> children, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody(commandKey, children)))
                .andExpect(status().is(expected));
    }

    private String reworkBody(String commandKey, String reworkKey, String reworkBatchKey,
                              String reworkBatchNo, String reason) {
        return "{\"commandKey\":\"" + commandKey + "\",\"reworkKey\":\"" + reworkKey
                + "\",\"reworkBatchKey\":\"" + reworkBatchKey + "\",\"reworkBatchNo\":\""
                + reworkBatchNo + "\",\"reason\":\"" + reason + "\"}";
    }

    private MvcResult rework(String sourceKey, String commandKey, String reworkKey,
                             String reworkBatchKey, String reworkBatchNo, String reason, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + sourceKey + "/rework")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reworkBody(commandKey, reworkKey, reworkBatchKey, reworkBatchNo,
                                reason)))
                .andExpect(status().is(expected)).andReturn();
    }

    private void reworkRaw(String sourceKey, String rawBody, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + sourceKey + "/rework")
                        .contentType(MediaType.APPLICATION_JSON).content(rawBody))
                .andExpect(status().is(expected));
    }

    private JsonNode history(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        return history(batchKey).path("batch").path("status").asText();
    }

    private int currentGeneration(String batchKey) throws Exception {
        return history(batchKey).path("batch").path("generation").asInt();
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        array.forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private boolean hasMessageContaining(Throwable t, String fragment) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur.getMessage() != null && cur.getMessage().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private int callStatus(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SafeVarargs
    private List<Future<Integer>> runConcurrent(Callable<Integer>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return task.call();
            }));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        return futures;
    }
}
