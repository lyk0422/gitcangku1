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
 * 返工重投代次与召回闭包扩展测试：返工代次生成、失败分支、代次上限、
 * reworkKey/commandKey 幂等与异参冲突、返工边召回闭包扩展与已放行后代待处置、
 * 返工链与召回闭包查询、召回与返工/放行的并发提交顺序裁决（真实 H2 MySQL 兼容库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchReworkTest {

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
        jdbc.update("DELETE FROM rework_order");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 返工主流程与代次 ----------

    @Test
    void happyRework_originReworked_newBatchGenerationOne_inheritsRequiredTestsNotConclusions() throws Exception {
        String origin = "BK-RW-O-" + unique();
        createBatch(origin, List.of("t1", "t2"), 201);
        // 已完成全部必做检验但判定不合格：t1 PASS 后 t2 FAIL
        submitTest(origin, "t1", "PASS", "insp-1", 201);
        submitTest(origin, "t2", "FAIL", "insp-1", 201);
        assertEquals("REJECTED", currentStatus(origin));

        String reworkKey = "RK-1-" + unique();
        String reworkBatch = "BK-RW-N-" + unique();
        MvcResult result = rework(origin, "CK-RW-1", reworkKey, reworkBatch, "灭菌返工", 201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(reworkKey, body.path("reworkKey").asText());
        assertEquals(origin, body.path("originBatchKey").asText());
        assertEquals("REWORKED", body.path("originStatus").asText());
        assertEquals("灭菌返工", body.path("reworkReason").asText());
        JsonNode rb = body.path("reworkBatch");
        assertEquals(reworkBatch, rb.path("batchKey").asText());
        assertEquals("QUARANTINED", rb.path("status").asText());
        assertEquals(1, rb.path("generation").asInt());
        assertEquals(List.of("t1", "t2"), objectMapper.convertValue(rb.path("requiredTests"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));

        // 原批次落定 REWORKED 终态，离开可用列表
        assertEquals("REWORKED", currentStatus(origin));
        assertFalse(availableKeys().contains(origin));

        // 新返工批次不继承检验结论与批准记录，初始 QUARANTINED 且在可用列表
        assertEquals("QUARANTINED", currentStatus(reworkBatch));
        assertTrue(availableKeys().contains(reworkBatch));
        MvcResult rwHistory = mockMvc.perform(get("/api/batches/" + reworkBatch + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode rwNode = objectMapper.readTree(rwHistory.getResponse().getContentAsString());
        assertEquals(0, rwNode.path("tests").size());
        assertEquals(0, rwNode.path("approvals").size());
        assertEquals(1, rwNode.path("batch").path("generation").asInt());
        assertTrue(rwNode.path("recall").isNull());

        // 原批次检验与历史保留不改写
        MvcResult originHistory = mockMvc.perform(get("/api/batches/" + origin + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode originNode = objectMapper.readTree(originHistory.getResponse().getContentAsString());
        assertEquals(2, originNode.path("tests").size());
        assertEquals("REWORKED", originNode.path("batch").path("status").asText());

        // REWORK 血缘边与返工登记落库
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ? AND child_key = ? AND edge_type = 'REWORK'",
                Integer.class, origin, reworkBatch));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rework_order WHERE origin_batch_key = ? AND rework_batch_key = ?"
                        + " AND generation = 1", Integer.class, origin, reworkBatch));

        // 返工批次重新执行全部必做检验 + 双角色放行
        releaseBatch(reworkBatch, "insp-2");
        assertEquals("RELEASED", currentStatus(reworkBatch));
    }

    @Test
    void reworkedOrigin_isTerminal_noReleaseSplitTestsOrRework() throws Exception {
        String origin = "BK-RT-O-" + unique();
        createBatch(origin, List.of("t1", "t2"), 201);
        rejectFully(origin);
        rework(origin, "CK-RT-1", "RK-RT-" + unique(), "BK-RT-N-" + unique(), "r", 201);

        // REWORKED 终态：检验 409、批准 409、拆分 409、再次返工 409
        submitTest(origin, "t1", "PASS", "insp-x", 409);
        approve(origin, "qa-x", "QUALITY", "CK-RT-A", 409);
        split(origin, "CK-RT-S", twoChildren(), 409);
        rework(origin, "CK-RT-2", "RK-RT-2-" + unique(), "BK-RT-N2-" + unique(), "r2", 409);
        assertEquals("REWORKED", currentStatus(origin));
    }

    @Test
    void splitChildInheritsGeneration() throws Exception {
        String origin = "BK-GEN-O-" + unique();
        createBatch(origin, List.of("t1"), 201);
        submitTest(origin, "t1", "FAIL", "insp", 201);
        String rw = "BK-GEN-RW-" + unique();
        rework(origin, "CK-GEN-1", "RK-GEN-" + unique(), rw, "r", 201);
        releaseBatch(rw, "insp-2");

        String c1 = "BK-GEN-C1-" + unique();
        String c2 = "BK-GEN-C2-" + unique();
        split(rw, "CK-GEN-S", List.of(new String[]{c1, "L1"}, new String[]{c2, "L2"}), 201);
        // 拆分子批继承父批代次（代次不随拆分变化）
        assertEquals(1, jdbc.queryForObject("SELECT generation FROM batch WHERE batch_key = ?",
                Integer.class, c1));
        MvcResult history = mockMvc.perform(get("/api/batches/" + c1 + "/history"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(1, objectMapper.readTree(history.getResponse().getContentAsString())
                .path("batch").path("generation").asInt());
    }

    // ---------- 失败分支 ----------

    @Test
    void reworkNonRejectedStates_returns409() throws Exception {
        // QUARANTINED
        String quarantined = "BK-RNS-Q-" + unique();
        createBatch(quarantined, List.of("t1"), 201);
        rework(quarantined, "CK-RNS-Q", rk(), "BK-RNS-NQ-" + unique(), "r", 409);

        // PENDING_RELEASE / RELEASE_REVIEW
        String pending = "BK-RNS-P-" + unique();
        createBatch(pending, List.of("t1"), 201);
        submitTest(pending, "t1", "PASS", "insp", 201);
        rework(pending, "CK-RNS-P", rk(), "BK-RNS-NP-" + unique(), "r", 409);
        approve(pending, "qa", "QUALITY", "CK-RNS-A1", 201);
        rework(pending, "CK-RNS-RV", rk(), "BK-RNS-NRV-" + unique(), "r", 409);

        // RELEASED
        String released = "BK-RNS-R-" + unique();
        createBatch(released, List.of("t1"), 201);
        releaseBatch(released, "insp");
        rework(released, "CK-RNS-R", rk(), "BK-RNS-NR-" + unique(), "r", 409);

        // RECALLED
        recall(released, "u", "原因", "CK-RNS-RC", 201);
        rework(released, "CK-RNS-RC2", rk(), "BK-RNS-NRC-" + unique(), "r", 409);

        // SPLIT
        String split = "BK-RNS-S-" + unique();
        createBatch(split, List.of("t1"), 201);
        releaseBatch(split, "insp");
        split(split, "CK-RNS-SP", twoChildren(), 201);
        rework(split, "CK-RNS-S2", rk(), "BK-RNS-NS-" + unique(), "r", 409);
    }

    @Test
    void reworkUnknownOrigin_returns404_andInvalidBodiesReturn400() throws Exception {
        rework("NO-SUCH-BATCH", "CK-404", rk(), "BK-404-" + unique(), "r", 404);

        String origin = "BK-R400-O-" + unique();
        createBatch(origin, List.of("t1"), 201);
        submitTest(origin, "t1", "FAIL", "insp", 201);
        String newBatch = "BK-R400-N-" + unique();
        // 缺 reworkKey
        mockMvc.perform(post("/api/batches/" + origin + "/rework")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R400-1\",\"reworkBatchKey\":\"" + newBatch
                                + "\",\"reworkReason\":\"r\"}"))
                .andExpect(status().isBadRequest());
        // 缺返工说明
        mockMvc.perform(post("/api/batches/" + origin + "/rework")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R400-2\",\"reworkKey\":\"" + rk()
                                + "\",\"reworkBatchKey\":\"" + newBatch + "\"}"))
                .andExpect(status().isBadRequest());
        // 缺 commandKey
        mockMvc.perform(post("/api/batches/" + origin + "/rework")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reworkKey\":\"" + rk() + "\",\"reworkBatchKey\":\""
                                + "BK-R400-N2-" + unique() + "\",\"reworkReason\":\"r\"}"))
                .andExpect(status().isBadRequest());
        assertEquals("REJECTED", currentStatus(origin));
    }

    @Test
    void reworkDuplicateBatchKeyAndReusedReworkKey_return409() throws Exception {
        String o1 = "BK-RDUP-O1-" + unique();
        createBatch(o1, List.of("t1"), 201);
        submitTest(o1, "t1", "FAIL", "insp", 201);
        String existing = "BK-RDUP-E-" + unique();
        createBatch(existing, List.of("t1"), 201);
        // 返工批次键已存在
        rework(o1, "CK-RDUP-1", rk(), existing, "r", 409);

        String n1 = "BK-RDUP-N1-" + unique();
        String sharedReworkKey = "RK-SHARED-" + unique();
        rework(o1, "CK-RDUP-2", sharedReworkKey, n1, "r", 201);
        // 另一原批次复用同一 reworkKey → 409
        String o2 = "BK-RDUP-O2-" + unique();
        createBatch(o2, List.of("t1"), 201);
        submitTest(o2, "t1", "FAIL", "insp", 201);
        rework(o2, "CK-RDUP-3", sharedReworkKey, "BK-RDUP-N2-" + unique(), "r", 409);
        assertEquals("REJECTED", currentStatus(o2));
    }

    @Test
    void reworkWithIncompleteRequiredTests_returns422() throws Exception {
        String origin = "BK-RINC-O-" + unique();
        createBatch(origin, List.of("t1", "t2"), 201);
        // t1 先 FAIL 立即 REJECTED，t2 未完成
        submitTest(origin, "t1", "FAIL", "insp", 201);
        assertEquals("REJECTED", currentStatus(origin));
        rework(origin, "CK-RINC-1", rk(), "BK-RINC-N-" + unique(), "r", 422);
        assertEquals("REJECTED", currentStatus(origin));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rework_order", Integer.class));
    }

    @Test
    void generationCap_fourthReworkReturns422WithCurrentGeneration() throws Exception {
        String gen0 = "BK-CAP-G0-" + unique();
        createBatch(gen0, List.of("t1", "t2"), 201);
        String current = gen0;
        for (int gen = 1; gen <= 3; gen++) {
            rejectFully(current);
            String next = "BK-CAP-G" + gen + "-" + unique();
            MvcResult result = rework(current, "CK-CAP-" + gen, "RK-CAP-" + gen + "-" + unique(),
                    next, "返工至代次" + gen, 201);
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertEquals(gen, body.path("reworkBatch").path("generation").asInt());
            current = next;
        }
        assertEquals(3, jdbc.queryForObject("SELECT generation FROM batch WHERE batch_key = ?",
                Integer.class, current));

        // 代次 3 的批次完整检验不合格后再次返工 → 422，错误体给出当前代次 3
        rejectFully(current);
        MvcResult blocked = rework(current, "CK-CAP-4", "RK-CAP-4-" + unique(),
                "BK-CAP-G4-" + unique(), "超限返工", 422);
        JsonNode error = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertEquals("PRECONDITION_FAILED", error.path("code").asText());
        assertTrue(error.path("message").asText().contains("当前代次为 3"),
                "422 错误信息必须给出当前代次: " + error.path("message").asText());
        // 未产生代次 4 批次，原批次保持 REJECTED
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = 'BK-CAP-G4-' OR generation > 3",
                Integer.class));
        assertEquals("REJECTED", currentStatus(current));
    }

    // ---------- 幂等 ----------

    @Test
    void reworkIdempotent_sameReworkKeyReplaysFirstResult_changedParamsConflict() throws Exception {
        String origin = "BK-RID-O-" + unique();
        createBatch(origin, List.of("t1"), 201);
        submitTest(origin, "t1", "FAIL", "insp", 201);
        String reworkKey = "RK-RID-" + unique();
        String newBatch = "BK-RID-N-" + unique();
        String body = reworkBody("CK-RID-1", reworkKey, newBatch, "r");

        MvcResult first = mockMvc.perform(post("/api/batches/" + origin + "/rework")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同 commandKey 同参重放
        MvcResult replay = mockMvc.perform(post("/api/batches/" + origin + "/rework")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 换 commandKey、同 reworkKey 同参仍回放首次结果
        MvcResult replay2 = mockMvc.perform(post("/api/batches/" + origin + "/rework")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reworkBody("CK-RID-OTHER", reworkKey, newBatch, "r")))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay2.getResponse().getContentAsString());

        // 仅一条返工登记与一条 REWORK 边
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rework_order WHERE origin_batch_key = ?", Integer.class, origin));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, origin));

        // 同 commandKey 改参 → 409；同 reworkKey 改返工批次/说明 → 409
        mockMvc.perform(post("/api/batches/" + origin + "/rework")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reworkBody("CK-RID-1", reworkKey, "BK-RID-CHANGED-" + unique(), "r")))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/batches/" + origin + "/rework")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reworkBody("CK-RID-2", reworkKey, newBatch, "changed reason")))
                .andExpect(status().isConflict());
        // 不同 reworkKey 再次返工同一批次 → 409
        rework(origin, "CK-RID-3", rk(), "BK-RID-N3-" + unique(), "r2", 409);
    }

    @Test
    void failedReworkDoesNotOccupyCommandKey() throws Exception {
        String origin = "BK-RF-O-" + unique();
        createBatch(origin, List.of("t1"), 201);
        submitTest(origin, "t1", "FAIL", "insp", 201);
        String existing = "BK-RF-E-" + unique();
        createBatch(existing, List.of("t1"), 201);

        // 首次失败（返工批次键冲突）409，不占 commandKey
        rework(origin, "CK-RF-1", rk(), existing, "r", 409);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_key = 'CK-RF-1'", Integer.class));
        // 同一 commandKey 修正参数后成功
        String newBatch = "BK-RF-N-" + unique();
        rework(origin, "CK-RF-1", rk(), newBatch, "r", 201);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_key = 'CK-RF-1'", Integer.class));
        assertEquals("REWORKED", currentStatus(origin));
        assertEquals("QUARANTINED", currentStatus(newBatch));
    }

    // ---------- 召回闭包沿返工边扩展 ----------

    @Test
    void recallClosureExtendsAlongReworkAndSplitEdges_releasedDescendantsPendingDisposal() throws Exception {
        // R(gen0,RELEASED) --SPLIT--> C(gen0), D(gen0)
        // C 完整检验不合格 --REWORK--> RW(gen1, REWORKED 原批 C)
        // RW 放行后 --SPLIT--> G1(gen1), G2(gen1)；G1 放行；D 放行
        String root = "BK-RC-R-" + unique();
        createBatch(root, List.of("t1", "t2"), 201);
        releaseBatch(root, "insp-0");
        String c = "BK-RC-C-" + unique();
        String d = "BK-RC-D-" + unique();
        split(root, "CK-RC-S1", List.of(new String[]{c, "L1"}, new String[]{d, "L2"}), 201);

        rejectFully(c, "insp-1");
        String rw = "BK-RC-RW-" + unique();
        rework(c, "CK-RC-RW1", "RK-RC-" + unique(), rw, "C 批返工", 201);
        releaseBatch(rw, "insp-2");
        String g1 = "BK-RC-G1-" + unique();
        String g2 = "BK-RC-G2-" + unique();
        split(rw, "CK-RC-S2", List.of(new String[]{g1, "G1"}, new String[]{g2, "G2"}), 201);
        releaseBatch(g1, "insp-3");
        releaseBatch(d, "insp-4");

        // 召回根批：闭包覆盖 SPLIT+REWORK 两类边的全部后代
        recall(root, "qa-lead", "原料污染", "CK-RC-REC", 201);
        assertEquals("RECALLED", currentStatus(root));

        // 已放行后代（D 与 G1）在同一事务标记为待处置；其余后代状态不改写
        assertEquals("PENDING_DISPOSAL", currentStatus(d));
        assertEquals("PENDING_DISPOSAL", currentStatus(g1));
        assertEquals("REWORKED", currentStatus(c));
        assertEquals("SPLIT", currentStatus(rw));
        assertEquals("QUARANTINED", currentStatus(g2));

        // 待处置批次不补写召回记录，检验/批准历史不删除不改写
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM recall", Integer.class));
        MvcResult g1History = mockMvc.perform(get("/api/batches/" + g1 + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode g1Node = objectMapper.readTree(g1History.getResponse().getContentAsString());
        assertTrue(g1Node.path("recall").isNull());
        assertEquals(2, g1Node.path("tests").size());
        assertEquals(2, g1Node.path("approvals").size());
        // 血缘边不删除不改写
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM batch_lineage", Integer.class));

        // 全部后代离开可用列表
        List<String> available = availableKeys();
        for (String k : List.of(c, d, rw, g1, g2)) {
            assertFalse(available.contains(k));
        }

        // 拦截放行/检验/拆分/返工
        approve(g2, "qa-x", "QUALITY", "CK-RC-A1", 422);
        submitTest(g2, "t1", "PASS", "insp-x", 422);
        split(g1, "CK-RC-S3", twoChildren(), 422);
        // 让 g2 先完整检验不合格（召回前无法做到；改为在 rw 的另一返工产物上验证返工拦截）
        // 已待处置批次本身也不可返工
        rework(g1, "CK-RC-RW2", rk(), "BK-RC-X-" + unique(), "r", 409);

        // 后代查询标注边类型与召回祖先
        MvcResult descendants = mockMvc.perform(get("/api/batches/" + root + "/descendants"))
                .andExpect(status().isOk()).andReturn();
        JsonNode nodes = objectMapper.readTree(descendants.getResponse().getContentAsString());
        assertEquals(5, nodes.size());
        assertEntry(nodes, c, "SPLIT", root);
        assertEntry(nodes, d, "SPLIT", root);
        assertEntry(nodes, rw, "REWORK", root);
        assertEntry(nodes, g1, "SPLIT", root);
        assertEntry(nodes, g2, "SPLIT", root);

        // 祖先查询：rw 的直接父批 c 通过 REWORK 边，再向上 root 为 SPLIT 边
        MvcResult ancestors = mockMvc.perform(get("/api/batches/" + rw + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode ancestorNodes = objectMapper.readTree(ancestors.getResponse().getContentAsString());
        assertEquals(c, ancestorNodes.get(0).path("batchKey").asText());
        assertEquals("REWORK", ancestorNodes.get(0).path("edgeType").asText());
        assertEquals(root, ancestorNodes.get(1).path("batchKey").asText());
        assertEquals("SPLIT", ancestorNodes.get(1).path("edgeType").asText());
    }

    @Test
    void ancestorRecallBlocksReworkCreation_422() throws Exception {
        String root = "BK-RBR-R-" + unique();
        createBatch(root, List.of("t1", "t2"), 201);
        releaseBatch(root, "insp-0");
        String c = "BK-RBR-C-" + unique();
        split(root, "CK-RBR-S", List.of(new String[]{c, "L1"},
                new String[]{"BK-RBR-D-" + unique(), "L2"}), 201);
        rejectFully(c, "insp-1");

        recall(root, "u", "先召回", "CK-RBR-RC", 201);
        // 祖先已召回：子批返工被拦截 422，不产生返工批次
        rework(c, "CK-RBR-RW", rk(), "BK-RBR-RW-" + unique(), "r", 422);
        assertEquals("REJECTED", currentStatus(c));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rework_order", Integer.class));
    }

    @Test
    void recallReleasedReworkBatch_marksItsSplitChildrenBlocked() throws Exception {
        String origin = "BK-RRW-O-" + unique();
        createBatch(origin, List.of("t1"), 201);
        submitTest(origin, "t1", "FAIL", "insp", 201);
        String rw = "BK-RRW-RW-" + unique();
        rework(origin, "CK-RRW-1", "RK-RRW-" + unique(), rw, "r", 201);
        releaseBatch(rw, "insp-2");
        String g1 = "BK-RRW-G1-" + unique();
        String g2 = "BK-RRW-G2-" + unique();
        split(rw, "CK-RRW-S", List.of(new String[]{g1, "G1"}, new String[]{g2, "G2"}), 201);

        // 直接召回已放行返工批次：其拆批产物进入闭包并被拦截（状态不改写）
        recall(rw, "u", "返工批异常", "CK-RRW-RC", 201);
        assertEquals("RECALLED", currentStatus(rw));
        assertEquals("QUARANTINED", currentStatus(g1));
        assertFalse(availableKeys().contains(g1));
        submitTest(g1, "t1", "PASS", "insp-3", 422);
    }

    // ---------- 返工链与召回闭包查询 ----------

    @Test
    void reworkChainQuery_spineFromHeadToLatest_splitChildStopsAtReworkAncestor() throws Exception {
        String g0 = "BK-RCH-G0-" + unique();
        createBatch(g0, List.of("t1", "t2"), 201);
        String g1 = "BK-RCH-G1-" + unique();
        rejectFully(g0);
        String rk1 = "RK-RCH-1-" + unique();
        rework(g0, "CK-RCH-1", rk1, g1, "r1", 201);
        String g2 = "BK-RCH-G2-" + unique();
        rejectFully(g1);
        String rk2 = "RK-RCH-2-" + unique();
        rework(g1, "CK-RCH-2", rk2, g2, "r2", 201);

        // 链头自身查询：[g0, g1, g2]
        checkChain(g0, g0, new String[][]{
                {g0, "0", null, null}, {g1, "1", rk1, "r1"}, {g2, "2", rk2, "r2"}});
        // 中间批次查询：同一脊柱
        checkChain(g1, g0, new String[][]{
                {g0, "0", null, null}, {g1, "1", rk1, "r1"}, {g2, "2", rk2, "r2"}});

        // g2 放行拆分，拆分子批查询时链尾停在 g2（不含拆分子批自身）
        releaseBatch(g2, "insp-3");
        String child = "BK-RCH-C-" + unique();
        split(g2, "CK-RCH-S", List.of(new String[]{child, "L1"},
                new String[]{"BK-RCH-C2-" + unique(), "L2"}), 201);
        checkChain(child, g0, new String[][]{
                {g0, "0", null, null}, {g1, "1", rk1, "r1"}, {g2, "2", rk2, "r2"}});

        // 无返工关系的批次查询：仅自身一条
        String plain = "BK-RCH-P-" + unique();
        createBatch(plain, List.of("t1"), 201);
        checkChain(plain, plain, new String[][]{{plain, "0", null, null}});

        // 拆分子树内再次返工：a --REWORK--> b --SPLIT--> c --REWORK--> d
        // c/d 构成独立返工链段，不与 a-b 串成一条链
        String a = "BK-RCH-A-" + unique();
        createBatch(a, List.of("t1", "t2"), 201);
        rejectFully(a);
        String b = "BK-RCH-B-" + unique();
        String rkA = "RK-RCH-A-" + unique();
        rework(a, "CK-RCH-A", rkA, b, "ra", 201);
        releaseBatch(b, "insp-a");
        String c = "BK-RCH-C-" + unique();
        split(b, "CK-RCH-BS", List.of(new String[]{c, "C1"},
                new String[]{"BK-RCH-CX-" + unique(), "C2"}), 201);
        rejectFully(c, "insp-c");
        String d = "BK-RCH-D-" + unique();
        String rkC = "RK-RCH-C-" + unique();
        rework(c, "CK-RCH-CR", rkC, d, "rc", 201);
        checkChain(d, c, new String[][]{
                {c, "1", null, null}, {d, "2", rkC, "rc"}});
        checkChain(c, c, new String[][]{
                {c, "1", null, null}, {d, "2", rkC, "rc"}});
        checkChain(b, a, new String[][]{
                {a, "0", null, null}, {b, "1", rkA, "ra"}});

        // 不存在批次 404
        mockMvc.perform(get("/api/batches/NO-SUCH/rework-chain"))
                .andExpect(status().isNotFound());
    }

    @Test
    void recallClosureQuery_returnsRootAndDispositions_unrelatedBatch409() throws Exception {
        String root = "BK-RCL-R-" + unique();
        createBatch(root, List.of("t1", "t2"), 201);
        releaseBatch(root, "insp-0");
        String c = "BK-RCL-C-" + unique();
        String d = "BK-RCL-D-" + unique();
        split(root, "CK-RCL-S", List.of(new String[]{c, "L1"}, new String[]{d, "L2"}), 201);
        rejectFully(c, "insp-1");
        String rw = "BK-RCL-RW-" + unique();
        rework(c, "CK-RCL-RW", "RK-RCL-" + unique(), rw, "r", 201);
        releaseBatch(d, "insp-2");

        // 召回前查询闭包 → 409
        mockMvc.perform(get("/api/batches/" + c + "/recall-closure"))
                .andExpect(status().isConflict());

        recall(root, "u", "污染", "CK-RCL-RC", 201);

        // 查询根：闭包顺序按血缘创建顺序 C, D, RW
        MvcResult rootClosure = mockMvc.perform(get("/api/batches/" + root + "/recall-closure"))
                .andExpect(status().isOk()).andReturn();
        JsonNode rootNode = objectMapper.readTree(rootClosure.getResponse().getContentAsString());
        assertEquals(root, rootNode.path("queriedBatchKey").asText());
        assertEquals(root, rootNode.path("recalledRootBatchKey").asText());
        JsonNode closure = rootNode.path("closure");
        assertEquals(3, closure.size());
        assertClosureEntry(closure, 0, c, "REWORKED", "BLOCKED");
        assertClosureEntry(closure, 1, d, "PENDING_DISPOSAL", "PENDING_DISPOSAL");
        assertClosureEntry(closure, 2, rw, "QUARANTINED", "BLOCKED");

        // 查询闭包深处批次：解析到同一召回根
        MvcResult rwClosure = mockMvc.perform(get("/api/batches/" + rw + "/recall-closure"))
                .andExpect(status().isOk()).andReturn();
        JsonNode rwNode = objectMapper.readTree(rwClosure.getResponse().getContentAsString());
        assertEquals(rw, rwNode.path("queriedBatchKey").asText());
        assertEquals(root, rwNode.path("recalledRootBatchKey").asText());
        assertEquals(3, rwNode.path("closure").size());

        // 无关批次不在任何闭包 → 409；不存在批次 → 404
        String unrelated = "BK-RCL-U-" + unique();
        createBatch(unrelated, List.of("t1"), 201);
        mockMvc.perform(get("/api/batches/" + unrelated + "/recall-closure"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/batches/NO-SUCH/recall-closure"))
                .andExpect(status().isNotFound());
    }

    // ---------- 并发裁决 ----------

    @Test
    void concurrentReworkCreationAndAncestorRecall_commitOrderDecides() throws Exception {
        String root = "BK-CRW-R-" + unique();
        createBatch(root, List.of("t1", "t2"), 201);
        releaseBatch(root, "insp-0");
        String c = "BK-CRW-C-" + unique();
        split(root, "CK-CRW-S", List.of(new String[]{c, "L1"},
                new String[]{"BK-CRW-D-" + unique(), "L2"}), 201);
        rejectFully(c, "insp-1");
        String rw = "BK-CRW-RW-" + unique();
        String reworkBody = reworkBody("CK-CRW-RW", "RK-CRW-" + unique(), rw, "并发返工");

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + c + "/rework")
                        .contentType(MediaType.APPLICATION_JSON).content(reworkBody)),
                () -> callStatus(post("/api/batches/" + root + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CRW-RC\",\"reason\":\"根批召回\"}"))
        );

        int rework = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall);
        if (rework == 201) {
            // 返工先提交：召回闭包必须包含新返工批次
            assertEquals("REWORKED", currentStatus(c));
            assertEquals("QUARANTINED", currentStatus(rw));
            assertFalse(availableKeys().contains(rw));
            submitTest(rw, "t1", "PASS", "insp-x", 422);
            MvcResult closure = mockMvc.perform(get("/api/batches/" + root + "/recall-closure"))
                    .andExpect(status().isOk()).andReturn();
            JsonNode nodes = objectMapper.readTree(closure.getResponse().getContentAsString())
                    .path("closure");
            boolean containsRework = false;
            for (JsonNode node : nodes) {
                if (rw.equals(node.path("batchKey").asText())) {
                    containsRework = true;
                }
            }
            assertTrue(containsRework, "返工先提交时召回闭包须包含新批次");
        } else {
            // 召回先提交：返工被祖先召回拦截 422，无返工批次与登记
            assertEquals(422, rework);
            assertEquals("REJECTED", currentStatus(c));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rework_order", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                    Integer.class, rw));
        }
    }

    @Test
    void concurrentReworkBatchFinalApprovalAndAncestorRecall_commitOrderDecides() throws Exception {
        String root = "BK-CRA2-R-" + unique();
        createBatch(root, List.of("t1", "t2"), 201);
        releaseBatch(root, "insp-0");
        String c = "BK-CRA2-C-" + unique();
        split(root, "CK-CRA2-S", List.of(new String[]{c, "L1"},
                new String[]{"BK-CRA2-D-" + unique(), "L2"}), 201);
        rejectFully(c, "insp-1");
        String rw = "BK-CRA2-RW-" + unique();
        rework(c, "CK-CRA2-RW", "RK-CRA2-" + unique(), rw, "r", 201);
        // 返工批次完成全部必做检验与第一角色批准，停在 RELEASE_REVIEW
        submitTest(rw, "t1", "PASS", "insp-2", 201);
        submitTest(rw, "t2", "PASS", "insp-2", 201);
        approve(rw, "qa-1", "QUALITY", "CK-CRA2-Q", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + rw + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CRA2-O\"}")),
                () -> callStatus(post("/api/batches/" + root + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CRA2-RC\",\"reason\":\"根批召回\"}"))
        );

        int finalApproval = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall);
        if (finalApproval == 201) {
            // 放行先提交：返工批次曾 RELEASED，同事务被标记待处置
            assertEquals("PENDING_DISPOSAL", currentStatus(rw));
        } else {
            // 召回先提交：返工批次放行被拦截 422
            assertEquals(422, finalApproval);
            assertEquals("RELEASE_REVIEW", currentStatus(rw));
        }
        assertFalse(finalApproval == 201 && availableKeys().contains(rw));
    }

    @Test
    void concurrentSameReworkKey_sameWinnerForBoth() throws Exception {
        String origin = "BK-CSC2-O-" + unique();
        createBatch(origin, List.of("t1"), 201);
        submitTest(origin, "t1", "FAIL", "insp", 201);
        String newBatch = "BK-CSC2-N-" + unique();
        String body = reworkBody("CK-CSC2", "RK-CSC2-" + unique(), newBatch, "r");

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + origin + "/rework")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + origin + "/rework")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发返工均返回首次结果");
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rework_order WHERE origin_batch_key = ?", Integer.class, origin));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, newBatch));
        assertEquals("REWORKED", currentStatus(origin));
    }

    // ---------- helpers ----------

    private void assertEntry(JsonNode array, String batchKey, String edgeType, String recalledAncestor) {
        for (JsonNode node : array) {
            if (batchKey.equals(node.path("batchKey").asText())) {
                assertEquals(edgeType, node.path("edgeType").asText(), batchKey + " 边类型");
                assertEquals(recalledAncestor,
                        node.path("unavailableDueToRecalledAncestor").asText(),
                        batchKey + " 召回祖先");
                return;
            }
        }
        fail("后代查询缺少批次: " + batchKey);
    }

    private void assertClosureEntry(JsonNode closure, int index, String batchKey,
                                    String status, String disposition) {
        JsonNode node = closure.get(index);
        assertEquals(batchKey, node.path("batchKey").asText());
        assertEquals(status, node.path("status").asText());
        assertEquals(disposition, node.path("disposition").asText());
    }

    private void checkChain(String queried, String anchor, String[][] expected) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + queried + "/rework-chain"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(queried, node.path("queriedBatchKey").asText());
        assertEquals(anchor, node.path("anchorBatchKey").asText());
        JsonNode chain = node.path("chain");
        assertEquals(expected.length, chain.size());
        for (int i = 0; i < expected.length; i++) {
            JsonNode entry = chain.get(i);
            assertEquals(expected[i][0], entry.path("batchKey").asText());
            assertEquals(Integer.parseInt(expected[i][1]), entry.path("generation").asInt());
            if (expected[i][2] == null) {
                assertTrue(entry.path("reworkKey").isNull());
                assertTrue(entry.path("reworkReason").isNull());
            } else {
                assertEquals(expected[i][2], entry.path("reworkKey").asText());
                assertEquals(expected[i][3], entry.path("reworkReason").asText());
            }
        }
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String rk() {
        return "RK-" + unique();
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private void createBatch(String batchKey, List<String> items, int expected) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), items));
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    /**
     * 让批次在完成全部必做检验后落定 REJECTED：除最后一项逐项 PASS，最后一项 FAIL。
     */
    private void rejectFully(String batchKey) throws Exception {
        rejectFully(batchKey, "insp-" + unique());
    }

    private void rejectFully(String batchKey, String inspector) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        List<String> items = objectMapper.convertValue(node.path("batch").path("requiredTests"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        for (int i = 0; i < items.size() - 1; i++) {
            submitTest(batchKey, items.get(i), "PASS", inspector, 201);
        }
        submitTest(batchKey, items.get(items.size() - 1), "FAIL", inspector, 201);
        assertEquals("REJECTED", currentStatus(batchKey));
    }

    private void releaseBatch(String batchKey, String inspector) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        for (JsonNode item : node.path("batch").path("requiredTests")) {
            submitTest(batchKey, item.asText(), "PASS", inspector, 201);
        }
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
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

    private MvcResult split(String parentKey, String commandKey, List<String[]> children,
                            int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(splitBody(commandKey, children)))
                .andExpect(status().is(expected)).andReturn();
    }

    private String reworkBody(String commandKey, String reworkKey, String reworkBatchKey,
                              String reason) {
        return "{\"commandKey\":\"" + commandKey + "\",\"reworkKey\":\"" + reworkKey
                + "\",\"reworkBatchKey\":\"" + reworkBatchKey + "\",\"reworkReason\":\"" + reason
                + "\"}";
    }

    private MvcResult rework(String originKey, String commandKey, String reworkKey,
                             String reworkBatchKey, String reason, int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + originKey + "/rework")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reworkBody(commandKey, reworkKey, reworkBatchKey, reason)))
                .andExpect(status().is(expected)).andReturn();
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        array.forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
    }

    private int callStatus(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
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
