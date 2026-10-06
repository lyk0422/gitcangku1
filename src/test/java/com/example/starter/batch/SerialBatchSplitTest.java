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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
 * 受控拆批与序号归属查询测试：并集精确等于父域、两两不交、子数量按区间长度生成、
 * 期望版本、入口隔离、拆后父域冻结为历史、序号查询返回当前叶批及召回状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SerialBatchSplitTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM serial_interval");
        jdbc.update("DELETE FROM serial_batch");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 受控拆批主流程 ----------

    @Test
    void split_happy_parentDomain1to10_into1to4And5to10() throws Exception {
        String parent = "SB-SP-P-" + unique();
        createSerial(parent, "PROD-S", new BigDecimal("10"), new long[][]{{1, 10}});
        releaseBatch(parent, "insp-1");

        String childA = "SB-SP-A-" + unique();
        String childB = "SB-SP-B-" + unique();
        MvcResult result = splitSerial(parent, "RQ-SP-" + unique(), 1,
                List.of(child(childA, new long[][]{{1, 4}}),
                        child(childB, new long[][]{{5, 10}})), 201);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(parent, body.path("parentBatchKey").asText());
        assertEquals("SPLIT", body.path("parentStatus").asText());
        assertEquals(2, body.path("parentVersion").asInt());
        assertEquals(2, body.path("children").size());
        // 子数量按区间长度生成
        assertEquals(4, body.path("children").get(0).path("quantity").asLong());
        assertEquals(6, body.path("children").get(1).path("quantity").asLong());
        assertEquals(childA, body.path("children").get(0).path("batchKey").asText());
        assertEquals(childB, body.path("children").get(1).path("batchKey").asText());

        // 父批区间冻结为历史（不删除不改写），子批区间各自落库
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM serial_interval WHERE batch_key = ?", Integer.class, parent));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM serial_interval WHERE batch_key = ?", Integer.class, childA));
        assertEquals(4, jdbc.queryForObject(
                "SELECT quantity FROM serial_batch WHERE batch_key = ?", Long.class, childA));
        assertEquals(6, jdbc.queryForObject(
                "SELECT quantity FROM serial_batch WHERE batch_key = ?", Long.class, childB));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM serial_batch WHERE is_root = FALSE", Integer.class));
        // 血缘与父批状态
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));
        assertEquals("SPLIT", jdbc.queryForObject(
                "SELECT status FROM batch WHERE batch_key = ?", String.class, parent));
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM serial_batch WHERE batch_key = ?", Integer.class, parent));
        // 子批继承必做检验项，初始隔离
        assertEquals("QUARANTINED", jdbc.queryForObject(
                "SELECT status FROM batch WHERE batch_key = ?", String.class, childA));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_required_test WHERE batch_key = ?", Integer.class,
                childA));
    }

    @Test
    void split_childCanBeSplitAgain_grandchildPartitionsExactly() throws Exception {
        String parent = "SB-ML-P-" + unique();
        createSerial(parent, "PROD-S", new BigDecimal("10"), new long[][]{{1, 10}});
        releaseBatch(parent, "insp-1");
        String childA = "SB-ML-A-" + unique();
        String childB = "SB-ML-B-" + unique();
        splitSerial(parent, "RQ-ML1-" + unique(), 1,
                List.of(child(childA, new long[][]{{1, 4}}),
                        child(childB, new long[][]{{5, 10}})), 201);

        releaseBatch(childB, "insp-2");
        String grand1 = "SB-ML-G1-" + unique();
        String grand2 = "SB-ML-G2-" + unique();
        // 子批版本为 1；[5,10] 再拆为 [5,7] 与 [8,10]
        splitSerial(childB, "RQ-ML2-" + unique(), 1,
                List.of(child(grand1, new long[][]{{5, 7}}),
                        child(grand2, new long[][]{{8, 10}})), 201);
        assertEquals(3, jdbc.queryForObject(
                "SELECT quantity FROM serial_batch WHERE batch_key = ?", Long.class, grand1));
        // 父域全部冻结为历史：root、childA、childB 各 1 条 + 两个孙批各 1 条
        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM serial_interval WHERE product_code = 'PROD-S'",
                Integer.class));
    }

    // ---------- 拆批失败分支：遗漏、重叠、区间外 422 ----------

    @Test
    void split_overlapGapOutOfRange_return422_parentUntouchedNoChildren() throws Exception {
        // 重叠：第二子改为 [4,10]，与 [1,4] 相交
        assertSplitRejectedAndParentIntact(new long[][]{{1, 4}}, new long[][]{{4, 10}});
        // 遗漏：[1,4] 与 [6,10] 之间缺 5
        assertSplitRejectedAndParentIntact(new long[][]{{1, 4}}, new long[][]{{6, 10}});
        // 区间外：[5,11] 超出父域 [1,10]
        assertSplitRejectedAndParentIntact(new long[][]{{1, 4}}, new long[][]{{5, 11}});
    }

    private void assertSplitRejectedAndParentIntact(long[][] first, long[][] second)
            throws Exception {
        String parent = "SB-RJ-P-" + unique();
        // 每个场景独立产品号，避免根域相交干扰
        createSerial(parent, "PROD-RJ-" + unique(), new BigDecimal("10"), new long[][]{{1, 10}});
        releaseBatch(parent, "insp-1");
        String childA = "SB-RJ-A-" + unique();
        String childB = "SB-RJ-B-" + unique();
        long requestLogBefore = jdbc.queryForObject("SELECT COUNT(*) FROM request_log",
                Long.class);
        splitSerial(parent, "RQ-RJ-" + unique(), 1,
                List.of(child(childA, first), child(childB, second)), 422);

        // 父批仍完整：状态 RELEASED、版本 1、区间仍 1 条；没有任何子批或血缘
        assertEquals("RELEASED", jdbc.queryForObject(
                "SELECT status FROM batch WHERE batch_key = ?", String.class, parent));
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM serial_batch WHERE batch_key = ?", Integer.class, parent));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM serial_interval WHERE batch_key = ?", Integer.class,
                parent));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key IN (?, ?)", Integer.class,
                childA, childB));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));
        // 失败不占键：request_log 不因失败的拆批新增记录
        assertEquals(requestLogBefore, jdbc.queryForObject("SELECT COUNT(*) FROM request_log",
                Long.class));
    }

    // ---------- 拆批门禁：放行状态、期望版本、类型入口 ----------

    @Test
    void split_gates_releasedStatusAndExpectedVersion() throws Exception {
        // 未放行（QUARANTINED）→ 409
        String quarantined = "SB-GT-Q-" + unique();
        createSerial(quarantined, "PROD-GQ", new BigDecimal("10"), new long[][]{{1, 10}});
        splitSerial(quarantined, "RQ-GT1-" + unique(), 1,
                List.of(child("SB-GT-Q1-" + unique(), new long[][]{{1, 4}}),
                        child("SB-GT-Q2-" + unique(), new long[][]{{5, 10}})), 409);

        // 期望版本不符 → 409
        String parent = "SB-GT-P-" + unique();
        createSerial(parent, "PROD-GP", new BigDecimal("10"), new long[][]{{1, 10}});
        releaseBatch(parent, "insp-1");
        splitSerial(parent, "RQ-GT2-" + unique(), 7,
                List.of(child("SB-GT-C1-" + unique(), new long[][]{{1, 4}}),
                        child("SB-GT-C2-" + unique(), new long[][]{{5, 10}})), 409);
        assertEquals("RELEASED", jdbc.queryForObject(
                "SELECT status FROM batch WHERE batch_key = ?", String.class, parent));

        // 正确版本成功；拆分后父批版本变为 2，再用旧版本拆 → 409（且状态已 SPLIT）
        splitSerial(parent, "RQ-GT3-" + unique(), 1,
                List.of(child("SB-GT-C3-" + unique(), new long[][]{{1, 4}}),
                        child("SB-GT-C4-" + unique(), new long[][]{{5, 10}})), 201);
        splitSerial(parent, "RQ-GT4-" + unique(), 1,
                List.of(child("SB-GT-C5-" + unique(), new long[][]{{1, 4}}),
                        child("SB-GT-C6-" + unique(), new long[][]{{5, 10}})), 409);
    }

    @Test
    void split_entryIsolation_serialParentCannotUseQuantitySplit_andViceVersa() throws Exception {
        // 新类型父批不能用原按数量拆批入口 → 422，父批不变
        String serialParent = "SB-ISO-P-" + unique();
        createSerial(serialParent, "PROD-S", new BigDecimal("10"), new long[][]{{1, 10}});
        releaseBatch(serialParent, "insp-1");
        mockMvc.perform(post("/api/batches/" + serialParent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-ISO-" + unique()
                                + "\",\"children\":[{\"batchKey\":\"SB-ISO-X1-" + unique()
                                + "\",\"batchNo\":\"L1\"},{\"batchKey\":\"SB-ISO-X2-" + unique()
                                + "\",\"batchNo\":\"L2\"}]}"))
                .andExpect(status().is(422));
        assertEquals("RELEASED", jdbc.queryForObject(
                "SELECT status FROM batch WHERE batch_key = ?", String.class, serialParent));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class,
                serialParent));

        // 旧普通批不能用受控拆批入口 → 422，仍可用原入口拆批（兼容性）
        String plainParent = "BK-ISO-P-" + unique();
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\""
                                + plainParent + "\",\"productCode\":\"PROD-P\",\"batchNo\":\"L1\","
                                + "\"producedAt\":\"2026-01-02T03:04:05Z\","
                                + "\"requiredTests\":[\"t1\"]}"))
                .andExpect(status().isCreated());
        releaseBatch(plainParent, "insp-2");
        splitSerial(plainParent, "RQ-ISO-" + unique(), 1,
                List.of(child("SB-ISO-Y1-" + unique(), new long[][]{{1, 4}}),
                        child("SB-ISO-Y2-" + unique(), new long[][]{{5, 10}})), 422);
        String plainChild = "BK-ISO-C-" + unique();
        mockMvc.perform(post("/api/batches/" + plainParent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-ISO2-" + unique()
                                + "\",\"children\":[{\"batchKey\":\"" + plainChild
                                + "\",\"batchNo\":\"L1\"},{\"batchKey\":\"BK-ISO-C2-" + unique()
                                + "\",\"batchNo\":\"L2\"}]}"))
                .andExpect(status().isCreated());
        assertEquals("SPLIT", jdbc.queryForObject(
                "SELECT status FROM batch WHERE batch_key = ?", String.class, plainParent));
        // 普通批拆分不产生序号域记录
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM serial_batch WHERE batch_key = ?", Integer.class,
                plainChild));
    }

    // ---------- 拆批幂等 ----------

    @Test
    void split_idempotent_intervalReorderReplays_changedParams409_failureNotOccupying()
            throws Exception {
        String parent = "SB-SI-P-" + unique();
        createSerial(parent, "PROD-S", new BigDecimal("10"), new long[][]{{1, 10}});
        releaseBatch(parent, "insp-1");
        String childA = "SB-SI-A-" + unique();
        String childB = "SB-SI-B-" + unique();
        String requestId = "RQ-SI-" + unique();

        // 失败不占键：先以重叠区间 422
        splitSerial(parent, requestId, 1,
                List.of(child(childA, new long[][]{{1, 4}}),
                        child(childB, new long[][]{{4, 10}})), 422);
        // 同 requestId 修正参数后成功；子批规格显式构造以便后续重放同参
        ChildSpec specA = new ChildSpec(childA, "LA", List.of(new IntervalSpec(1, 4)));
        ChildSpec specB = new ChildSpec(childB, "LB", List.of(new IntervalSpec(5, 10)));
        MvcResult first = splitSerial(parent, requestId, 1, List.of(specA, specB), 201);

        // 重放：子批区间换序（[5,10] 写为 [6,10],[5,5] 相邻合并）属同参，返回首次完整响应
        ChildSpec specBReordered = new ChildSpec(childB, "LB",
                List.of(new IntervalSpec(6, 10), new IntervalSpec(5, 5)));
        MvcResult replay = mockMvc.perform(post("/api/serial-batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SplitCmd(requestId, "op-1", 1,
                                        List.of(specA, specBReordered)))))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不重复建子批
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));

        // 同 requestId 改参（期望版本不同）→ 409
        mockMvc.perform(post("/api/serial-batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SplitCmd(requestId, "op-1", 2, List.of(specA, specB)))))
                .andExpect(status().isConflict());
    }

    // ---------- 序号归属查询 ----------

    @Test
    void serialQuery_returnsCurrentLeafAcrossSplits() throws Exception {
        String parent = "SB-Q-P-" + unique();
        createSerial(parent, "PROD-Q", new BigDecimal("10"), new long[][]{{1, 10}});
        // 拆分前：序号归根批
        assertLeaf("PROD-Q", 3, parent, false, null);
        releaseBatch(parent, "insp-1");
        String childA = "SB-Q-A-" + unique();
        String childB = "SB-Q-B-" + unique();
        splitSerial(parent, "RQ-Q1-" + unique(), 1,
                List.of(child(childA, new long[][]{{1, 4}}),
                        child(childB, new long[][]{{5, 10}})), 201);
        // 拆后：序号归子批，父域只作历史
        assertLeaf("PROD-Q", 3, childA, false, null);
        assertLeaf("PROD-Q", 7, childB, false, null);

        // 子批再拆：序号归孙批
        releaseBatch(childB, "insp-2");
        String grand1 = "SB-Q-G1-" + unique();
        String grand2 = "SB-Q-G2-" + unique();
        splitSerial(childB, "RQ-Q2-" + unique(), 1,
                List.of(child(grand1, new long[][]{{5, 7}}),
                        child(grand2, new long[][]{{8, 10}})), 201);
        assertLeaf("PROD-Q", 7, grand1, false, null);
        assertLeaf("PROD-Q", 9, grand2, false, null);
        assertLeaf("PROD-Q", 3, childA, false, null);

        // 未登记序号 → 404；其他产品同序号 → 404；越界序号 → 400
        mockMvc.perform(get("/api/serial-batches/PROD-Q/serials/500"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/serial-batches/PROD-OTHER/serials/3"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/serial-batches/PROD-Q/serials/0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void serialQuery_recallStatus_considersSelfAndAncestorRecall() throws Exception {
        String parent = "SB-QR-P-" + unique();
        createSerial(parent, "PROD-QR", new BigDecimal("10"), new long[][]{{1, 10}});
        releaseBatch(parent, "insp-1");
        String childA = "SB-QR-A-" + unique();
        String childB = "SB-QR-B-" + unique();
        splitSerial(parent, "RQ-QR1-" + unique(), 1,
                List.of(child(childA, new long[][]{{1, 4}}),
                        child(childB, new long[][]{{5, 10}})), 201);

        // 祖先（已 SPLIT 的根）召回 → 后代序号查询 recalled=true，recalledBy 为根
        recall(parent, "qa-lead", "根批召回", "CK-QR-" + unique(), 201);
        assertLeaf("PROD-QR", 3, childA, true, parent);
        assertLeaf("PROD-QR", 7, childB, true, parent);

        // 另一棵树：叶批自身召回
        String parent2 = "SB-QR-P2-" + unique();
        createSerial(parent2, "PROD-QR2", new BigDecimal("4"), new long[][]{{1, 4}});
        releaseBatch(parent2, "insp-2");
        recall(parent2, "qa", "叶批召回", "CK-QR2-" + unique(), 201);
        assertLeaf("PROD-QR2", 2, parent2, true, parent2);
        // 无关产品不受影响
        assertLeaf("PROD-QR", 3, childA, true, parent);
    }

    // ---------- 并发 ----------

    @Test
    void concurrentCreateRoot_sameProductOverlapping_exactlyOneWins() throws Exception {
        String keyA = "SB-CC-A-" + unique();
        String keyB = "SB-CC-B-" + unique();
        String bodyA = createBody("RQ-CC-A-" + unique(), keyA, "PROD-C",
                new BigDecimal("10"), new long[][]{{1, 10}});
        String bodyB = createBody("RQ-CC-B-" + unique(), keyB, "PROD-C",
                new BigDecimal("11"), new long[][]{{5, 15}});

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/serial-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyA)),
                () -> callStatus(post("/api/serial-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(bodyB)));
        int statusA = results.get(0).get(30, TimeUnit.SECONDS);
        int statusB = results.get(1).get(30, TimeUnit.SECONDS);
        // 恰有一个登记成功，另一个因根域相交 409；不暴露唯一键错误（无 500）
        assertEquals(1, List.of(statusA, statusB).stream().filter(s -> s == 201).count());
        assertEquals(1, List.of(statusA, statusB).stream().filter(s -> s == 409).count());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM serial_batch WHERE product_code = 'PROD-C'",
                Integer.class));
    }

    @Test
    void concurrentSplit_sameRequestId_bothReplayFirstResponse() throws Exception {
        String parent = "SB-CS-P-" + unique();
        createSerial(parent, "PROD-S", new BigDecimal("10"), new long[][]{{1, 10}});
        releaseBatch(parent, "insp-1");
        String childA = "SB-CS-A-" + unique();
        String childB = "SB-CS-B-" + unique();
        String body = objectMapper.writeValueAsString(
                new SplitCmd("RQ-CS-" + unique(), "op-1", 1,
                        List.of(child(childA, new long[][]{{1, 4}}),
                                child(childB, new long[][]{{5, 10}}))));

        List<Future<MvcResult>> results = runConcurrentResults(
                () -> mockMvc.perform(post("/api/serial-batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(),
                () -> mockMvc.perform(post("/api/serial-batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn());
        MvcResult r1 = results.get(0).get(30, TimeUnit.SECONDS);
        MvcResult r2 = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, r1.getResponse().getStatus());
        assertEquals(201, r2.getResponse().getStatus());
        assertEquals(r1.getResponse().getContentAsString(), r2.getResponse().getContentAsString());
        // 只建了一轮子批与血缘
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_lineage WHERE parent_key = ?", Integer.class, parent));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch WHERE batch_key = ?", Integer.class, childA));
    }

    @Test
    void concurrentSplitAndAncestorRecall_commitOrderDecides() throws Exception {
        String parent = "SB-CR-P-" + unique();
        createSerial(parent, "PROD-S", new BigDecimal("10"), new long[][]{{1, 10}});
        releaseBatch(parent, "insp-1");
        String childA = "SB-CR-A-" + unique();
        String childB = "SB-CR-B-" + unique();
        String splitBody = objectMapper.writeValueAsString(
                new SplitCmd("RQ-CR-" + unique(), "op-1", 1,
                        List.of(child(childA, new long[][]{{1, 4}}),
                                child(childB, new long[][]{{5, 10}}))));

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/serial-batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody)),
                () -> callStatus(post("/api/batches/" + parent + "/recall")
                        .header("X-Actor-Id", "qa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CR-" + UUID.randomUUID()
                                + "\",\"reason\":\"召回\"}")));
        int split = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall, "RELEASED 父批始终可召回");
        if (split == 201) {
            // 拆分先提交：子批已建立，随后召回将父批（SPLIT）置为 RECALLED，子批随祖先召回不可用
            assertEquals("RECALLED", jdbc.queryForObject(
                    "SELECT status FROM batch WHERE batch_key = ?", String.class, parent));
            assertEquals("QUARANTINED", jdbc.queryForObject(
                    "SELECT status FROM batch WHERE batch_key = ?", String.class, childA));
            assertLeaf("PROD-S", 3, childA, true, parent);
        } else {
            // 召回先提交：拆分被拦截 409（状态不再 RELEASED），无任何子批
            assertEquals(409, split);
            assertEquals("RECALLED", jdbc.queryForObject(
                    "SELECT status FROM batch WHERE batch_key = ?", String.class, parent));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM batch WHERE batch_key IN (?, ?)", Integer.class,
                    childA, childB));
        }
    }

    // ---------- helpers ----------

    private void assertLeaf(String productCode, long serial, String expectedKey,
                            boolean expectedRecalled, String expectedRecalledBy) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/serial-batches/" + productCode + "/serials/" + serial))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(expectedKey, body.path("batchKey").asText());
        assertEquals(expectedRecalled, body.path("recalled").asBoolean());
        if (expectedRecalledBy == null) {
            assertTrue(body.path("recalledBy").isNull());
        } else {
            assertEquals(expectedRecalledBy, body.path("recalledBy").asText());
        }
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record IntervalSpec(long start, long end) {
    }

    private record CreateCmd(String requestId, String operator, String batchKey,
                             String productCode, String batchNo, Instant producedAt,
                             List<String> requiredTests, BigDecimal quantity,
                             List<IntervalSpec> serialIntervals) {
    }

    private record ChildSpec(String batchKey, String batchNo, List<IntervalSpec> serialIntervals) {
    }

    private record SplitCmd(String requestId, String operator, int expectedVersion,
                            List<ChildSpec> children) {
    }

    private ChildSpec child(String batchKey, long[][] intervals) {
        return new ChildSpec(batchKey, "LOT-" + unique(), toSpecs(intervals));
    }

    private List<IntervalSpec> toSpecs(long[][] intervals) {
        return Arrays.stream(intervals)
                .map(iv -> new IntervalSpec(iv[0], iv[1]))
                .toList();
    }

    private String createBody(String requestId, String batchKey, String productCode,
                              BigDecimal quantity, long[][] intervals) throws Exception {
        return objectMapper.writeValueAsString(new CreateCmd(requestId, "op-1", batchKey,
                productCode, "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), List.of("t1"),
                quantity, toSpecs(intervals)));
    }

    private void createSerial(String batchKey, String productCode, BigDecimal quantity,
                              long[][] intervals) throws Exception {
        mockMvc.perform(post("/api/serial-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("RQ-C-" + unique(), batchKey, productCode, quantity,
                                intervals)))
                .andExpect(status().isCreated());
    }

    private MvcResult splitSerial(String parentKey, String requestId, int expectedVersion,
                                  List<ChildSpec> children, int expected) throws Exception {
        return mockMvc.perform(post("/api/serial-batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SplitCmd(requestId, "op-1", expectedVersion, children))))
                .andExpect(status().is(expected)).andReturn();
    }

    /**
     * 让批次走完 检验 PASS + 双角色批准 进入 RELEASED（沿用原放行入口）。
     */
    private void releaseBatch(String batchKey, String inspector) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        for (JsonNode item : node.path("batch").path("requiredTests")) {
            mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-"
                                    + unique() + "\",\"testItem\":\"" + item.asText()
                                    + "\",\"result\":\"PASS\",\"inspector\":\"" + inspector
                                    + "\"}"))
                    .andExpect(status().isCreated());
        }
        approve(batchKey, "qa-" + unique(), "QUALITY");
        approve(batchKey, "ops-" + unique(), "OPERATIONS");
    }

    private void approve(String batchKey, String actor, String role) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().isCreated());
    }

    private void recall(String batchKey, String actor, String reason, String commandKey,
                        int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expected));
    }

    private int callStatus(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
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

    @SafeVarargs
    private List<Future<MvcResult>> runConcurrentResults(Callable<MvcResult>... tasks)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (Callable<MvcResult> task : tasks) {
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