package com.example.starter.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
 * 批次条件放行测试：条件登记与核销、到期自动降级、批准前置校验、
 * 召回并发、commandKey/conditionKey 幂等与并发边界。
 * 到期判定通过可注入时钟（{@link TimeSource}）控制，不依赖后台任务。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConditionalReleaseTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TimeSource timeSource;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM condition_item");
        jdbc.update("DELETE FROM conditional_release");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch");
        timeSource.set(T0);
    }

    @AfterEach
    void tearDown() {
        timeSource.reset();
    }

    // ---------- 主流程：登记 → 核销 → RELEASED ----------

    @Test
    void happyFlow_conditionalReleaseThenFullClearanceReleasesBatch() throws Exception {
        String batchKey = "BK-COND-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp-1");
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        // 创建条件放行：批次 CONDITIONAL 且出现在可用列表
        MvcResult created = createConditional(batchKey, "qa-1", "QUALITY", "CK-CR-1",
                "COND-1", List.of("补充稳定性数据", "完成留样复检"), "2026-06-10T00:00:00Z", 201);
        JsonNode cond = readJson(created);
        assertEquals("CONDITIONAL", currentStatus(batchKey));
        assertTrue(availableKeys().contains(batchKey));
        assertEquals("ACTIVE", cond.path("status").asText());
        assertFalse(cond.path("expired").asBoolean());
        assertEquals(2, cond.path("items").size());
        assertEquals(2, cond.path("pendingItems").size());
        assertEquals("1", cond.path("items").get(0).path("itemKey").asText());
        assertEquals("补充稳定性数据", cond.path("items").get(0).path("description").asText());

        // CONDITIONAL 期间不得走双角色批准，也不得再建条件放行
        approve(batchKey, "qa-2", "QUALITY", "CK-A-X", 409);
        createConditional(batchKey, "ops-1", "OPERATIONS", "CK-CR-2",
                "COND-2", List.of("另一条"), "2026-06-11T00:00:00Z", 409);

        // 创建角色不能核销（须不同批准角色）
        clearItem(batchKey, "COND-1", "qa-9", "QUALITY", "CK-CL-0", "1", "证明", 422);

        // 另一角色逐条核销
        MvcResult clear1 = clearItem(batchKey, "COND-1", "ops-1", "OPERATIONS",
                "CK-CL-1", "1", "稳定性数据已补交", 201);
        JsonNode c1 = readJson(clear1);
        assertEquals("CONDITIONAL", c1.path("batchStatus").asText());
        assertEquals(List.of("2"), toTextList(c1.path("pendingItems")));

        MvcResult clear2 = clearItem(batchKey, "COND-1", "ops-2", "OPERATIONS",
                "CK-CL-2", "2", "留样复检合格", 201);
        JsonNode c2 = readJson(clear2);
        assertEquals("RELEASED", c2.path("batchStatus").asText());
        assertEquals(0, c2.path("pendingItems").size());

        // 全部核销后批次 RELEASED，条件记录保留且状态 FULFILLED
        assertEquals("RELEASED", currentStatus(batchKey));
        JsonNode detail = conditionDetail(batchKey, "COND-1");
        assertEquals("FULFILLED", detail.path("status").asText());
        assertTrue(detail.path("items").get(0).path("cleared").asBoolean());
        assertEquals("ops-1", detail.path("items").get(0).path("clearedBy").asText());
        assertEquals("OPERATIONS", detail.path("items").get(0).path("clearedRole").asText());
        assertEquals("稳定性数据已补交", detail.path("items").get(0).path("evidence").asText());
        assertFalse(detail.path("completedAt").isNull());

        // 已完成后重复核销 → 409；历史里保留条件放行记录
        clearItem(batchKey, "COND-1", "ops-3", "OPERATIONS", "CK-CL-3", "1", "重复", 409);
        JsonNode history = history(batchKey);
        assertEquals(1, history.path("conditionalReleases").size());
        assertEquals("FULFILLED",
                history.path("conditionalReleases").get(0).path("status").asText());
    }

    // ---------- 创建前置校验 ----------

    @Test
    void createConditional_preconditionFailures() throws Exception {
        // 检验未全部通过 → 422
        String quarantined = "BK-COND-Q-" + unique();
        createBatch(quarantined, List.of("t1", "t2"), 201);
        submitTest(quarantined, "TK-1", "t1", "PASS", "insp");
        createConditional(quarantined, "qa", "QUALITY", "CK-1", "C-Q",
                List.of("x"), "2026-06-10T00:00:00Z", 422);

        // 有效期不晚于当前时刻 → 422
        String batchKey = "BK-COND-E-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp");
        createConditional(batchKey, "qa", "QUALITY", "CK-2", "C-E1",
                List.of("x"), "2026-05-01T00:00:00Z", 422);
        createConditional(batchKey, "qa", "QUALITY", "CK-3", "C-E2",
                List.of("x"), T0.toString(), 422);

        // 条件说明条数越界 → 400
        createConditional(batchKey, "qa", "QUALITY", "CK-4", "C-E3",
                List.of(), "2026-06-10T00:00:00Z", 400);
        createConditional(batchKey, "qa", "QUALITY", "CK-5", "C-E4",
                List.of("a", "b", "c", "d", "e", "f"), "2026-06-10T00:00:00Z", 400);

        // 缺头/非法角色 → 400
        String body = "{\"commandKey\":\"CK-6\",\"conditionKey\":\"C-E5\",\"conditions\":[\"x\"],"
                + "\"expiresAt\":\"2026-06-10T00:00:00Z\"}";
        mockMvc.perform(post("/api/batches/" + batchKey + "/conditional-releases")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/batches/" + batchKey + "/conditional-releases")
                        .header("X-Actor-Id", "qa").header("X-Approval-Role", "BOSS")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        // 失败不占键：修正参数后同一 commandKey 可成功
        createConditional(batchKey, "qa", "QUALITY", "CK-4", "C-OK",
                List.of("x"), "2026-06-10T00:00:00Z", 201);
        assertEquals("CONDITIONAL", currentStatus(batchKey));

        // 同一 conditionKey 只能创建一次（跨批次也唯一）
        String other = "BK-COND-O-" + unique();
        createBatch(other, List.of("t1"), 201);
        submitTest(other, "TK-1", "t1", "PASS", "insp");
        createConditional(other, "qa", "QUALITY", "CK-7", "C-OK",
                List.of("y"), "2026-06-10T00:00:00Z", 409);

        // 批次不存在 → 404
        createConditional("NO-SUCH", "qa", "QUALITY", "CK-8", "C-404",
                List.of("x"), "2026-06-10T00:00:00Z", 404);
    }

    // ---------- 到期自动降级 ----------

    @Test
    void expiry_downgradesBatchAndBlocksClearanceWith422() throws Exception {
        String batchKey = "BK-EXP-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp");
        createConditional(batchKey, "qa", "QUALITY", "CK-CR", "C-EXP",
                List.of("条件甲", "条件乙"), "2026-06-05T00:00:00Z", 201);
        assertTrue(availableKeys().contains(batchKey));

        // 核销一条后时钟越过有效期
        clearItem(batchKey, "C-EXP", "ops", "OPERATIONS", "CK-CL-1", "1", "已核销", 201);
        timeSource.set(Instant.parse("2026-06-06T00:00:00Z"));

        // 到期降级：批次从可用查询中消失，状态降回 PENDING_RELEASE
        assertFalse(availableKeys().contains(batchKey));
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        // 到期后核销 → 422 并列出未核销子项；已核销记录保留
        MvcResult denied = mockMvc.perform(post("/api/batches/" + batchKey
                        + "/conditional-releases/C-EXP/clearances")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CL-2\",\"itemKey\":\"2\",\"evidence\":\"e\"}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = readJson(denied);
        assertEquals(List.of("2"), toTextList(error.path("pendingItems")));

        JsonNode detail = conditionDetail(batchKey, "C-EXP");
        assertEquals("EXPIRED", detail.path("status").asText());
        assertTrue(detail.path("expired").asBoolean());
        assertEquals(List.of("2"), toTextList(detail.path("pendingItems")));
        assertTrue(detail.path("items").get(0).path("cleared").asBoolean(),
                "已核销子项记录不得被撤销");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM conditional_release WHERE condition_key = 'C-EXP'",
                Integer.class), "条件记录不物理删除");

        // 降级后可重新创建条件放行
        createConditional(batchKey, "ops", "OPERATIONS", "CK-CR-2", "C-EXP-2",
                List.of("新条件"), "2026-06-20T00:00:00Z", 201);
        assertEquals("CONDITIONAL", currentStatus(batchKey));
        assertTrue(availableKeys().contains(batchKey));
    }

    @Test
    void expiry_thenNormalApprovalFlowAllowed() throws Exception {
        String batchKey = "BK-EXP-AP-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp");
        createConditional(batchKey, "qa", "QUALITY", "CK-CR", "C-EXP-AP",
                List.of("条件"), "2026-06-05T00:00:00Z", 201);

        timeSource.set(Instant.parse("2026-06-06T00:00:00Z"));
        // 触发降级后走原双角色批准
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 201);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        JsonNode detail = conditionDetail(batchKey, "C-EXP-AP");
        assertEquals("EXPIRED", detail.path("status").asText());
    }

    // ---------- 召回与条件放行并发 ----------

    @Test
    void recallConditionalBatch_blocksFurtherClearance_keepsClearedRecords() throws Exception {
        String batchKey = "BK-RC-COND-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp");
        createConditional(batchKey, "qa", "QUALITY", "CK-CR", "C-RC",
                List.of("条件一", "条件二"), "2026-06-10T00:00:00Z", 201);
        clearItem(batchKey, "C-RC", "ops", "OPERATIONS", "CK-CL-1", "1", "已核销", 201);

        // 召回 CONDITIONAL 批次生效
        recall(batchKey, "user-1", "上游原料召回", "CK-RC", 201);
        assertEquals("RECALLED", currentStatus(batchKey));
        assertFalse(availableKeys().contains(batchKey));

        // 召回后核销 → 422；已核销记录与条件记录保留
        MvcResult denied = mockMvc.perform(post("/api/batches/" + batchKey
                        + "/conditional-releases/C-RC/clearances")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CL-2\",\"itemKey\":\"2\",\"evidence\":\"e\"}"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertEquals(List.of("2"), toTextList(readJson(denied).path("pendingItems")));
        JsonNode detail = conditionDetail(batchKey, "C-RC");
        assertTrue(detail.path("items").get(0).path("cleared").asBoolean());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM conditional_release WHERE condition_key = 'C-RC'",
                Integer.class));
    }

    @Test
    void concurrentRecallAndClearance_commitOrderDecides() throws Exception {
        String batchKey = "BK-RACE-RC-CL-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp");
        createConditional(batchKey, "qa", "QUALITY", "CK-CR", "C-RACE",
                List.of("唯一条件"), "2026-06-10T00:00:00Z", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey
                                + "/conditional-releases/C-RACE/clearances")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CL\",\"itemKey\":\"1\",\"evidence\":\"e\"}")),
                () -> callStatus(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-R\",\"reason\":\"祖先召回\"}"))
        );
        int clear = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        String finalStatus = currentStatus(batchKey);
        if (recall == 201 && clear != 201) {
            // 召回先提交：核销 422，批次 RECALLED
            assertEquals(422, clear);
            assertEquals("RECALLED", finalStatus);
        } else {
            // 核销先提交：批次 RELEASED 后召回仍生效；已完成的核销记录不撤销
            assertEquals(201, clear);
            assertEquals(201, recall, "核销先提交后批次 RELEASED，召回应继续生效");
            assertEquals("RECALLED", finalStatus);
            JsonNode detail = conditionDetail(batchKey, "C-RACE");
            assertTrue(detail.path("items").get(0).path("cleared").asBoolean(),
                    "已完成的核销记录不得撤销");
        }
    }

    // ---------- 幂等 ----------

    @Test
    void commandKey_idempotencyForCreateAndClear() throws Exception {
        String batchKey = "BK-IDEM-C-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp");

        String createBody = "{\"commandKey\":\"CK-CR\",\"conditionKey\":\"C-IDEM\","
                + "\"conditions\":[\"条件一\"],\"expiresAt\":\"2026-06-10T00:00:00Z\"}";
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/conditional-releases")
                        .header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/conditional-releases")
                        .header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同键同参重放首次结果");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM conditional_release WHERE condition_key = 'C-IDEM'",
                Integer.class));

        // 同键改参 → 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/conditional-releases")
                        .header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CR\",\"conditionKey\":\"C-IDEM-2\","
                                + "\"conditions\":[\"条件一\"],\"expiresAt\":\"2026-06-10T00:00:00Z\"}"))
                .andExpect(status().isConflict());

        // 核销同键重放
        String clearBody = "{\"commandKey\":\"CK-CL\",\"itemKey\":\"1\",\"evidence\":\"证明\"}";
        MvcResult c1 = mockMvc.perform(post("/api/batches/" + batchKey
                        + "/conditional-releases/C-IDEM/clearances")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content(clearBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult c2 = mockMvc.perform(post("/api/batches/" + batchKey
                        + "/conditional-releases/C-IDEM/clearances")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content(clearBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(c1.getResponse().getContentAsString(),
                c2.getResponse().getContentAsString());
        // 核销不得重复计数
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM condition_item WHERE condition_key = 'C-IDEM' AND cleared = 1",
                Integer.class));
        // 核销同键改参 → 409
        mockMvc.perform(post("/api/batches/" + batchKey
                        + "/conditional-releases/C-IDEM/clearances")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CL\",\"itemKey\":\"1\",\"evidence\":\"改参\"}"))
                .andExpect(status().isConflict());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentClearSameItem_exactlyOneSucceeds() throws Exception {
        String batchKey = "BK-RACE-CL-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp");
        createConditional(batchKey, "qa", "QUALITY", "CK-CR", "C-CL",
                List.of("条件一"), "2026-06-10T00:00:00Z", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey
                                + "/conditional-releases/C-CL/clearances")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-1\",\"itemKey\":\"1\",\"evidence\":\"e1\"}")),
                () -> callStatus(post("/api/batches/" + batchKey
                                + "/conditional-releases/C-CL/clearances")
                        .header("X-Actor-Id", "ops-2").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-2\",\"itemKey\":\"1\",\"evidence\":\"e2\"}"))
        );
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                ok++;
            } else if (code == 409) {
                conflict++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, ok, "同一子项并发核销只能成功一次");
        assertEquals(1, conflict);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM condition_item WHERE condition_key = 'C-CL' AND cleared = 1",
                Integer.class), "核销不得重复计数");
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void concurrentCreateSameConditionKey_exactlyOneSucceeds() throws Exception {
        String batchKey = "BK-RACE-CK-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp");

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/conditional-releases")
                        .header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-1\",\"conditionKey\":\"C-DUP\","
                                + "\"conditions\":[\"x\"],\"expiresAt\":\"2026-06-10T00:00:00Z\"}")),
                () -> callStatus(post("/api/batches/" + batchKey + "/conditional-releases")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-2\",\"conditionKey\":\"C-DUP\","
                                + "\"conditions\":[\"y\"],\"expiresAt\":\"2026-06-11T00:00:00Z\"}"))
        );
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                ok++;
            } else if (code == 409) {
                conflict++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, ok, "同一 conditionKey 只能被创建一次");
        assertEquals(1, conflict);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM conditional_release WHERE condition_key = 'C-DUP'",
                Integer.class));
        assertEquals("CONDITIONAL", currentStatus(batchKey));
    }

    // ---------- 查询 ----------

    @Test
    void conditionQueries_stableOrderingAndUnknownKeys() throws Exception {
        String batchKey = "BK-Q-" + unique();
        createBatch(batchKey, List.of("t1"), 201);
        submitTest(batchKey, "TK-1", "t1", "PASS", "insp");
        createConditional(batchKey, "qa", "QUALITY", "CK-1", "C-Q1",
                List.of("b条件", "a条件", "c条件"), "2026-06-10T00:00:00Z", 201);
        createConditional(batchKey, "qa", "QUALITY", "CK-2", "C-Q2",
                List.of("x"), "2026-06-10T00:00:00Z", 409);

        MvcResult list = mockMvc.perform(get("/api/batches/" + batchKey + "/conditional-releases"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = readJson(list);
        assertEquals(1, array.size());
        JsonNode items = array.get(0).path("items");
        // 稳定排序：按创建顺序而非名称排序
        assertEquals("b条件", items.get(0).path("description").asText());
        assertEquals("a条件", items.get(1).path("description").asText());
        assertEquals("c条件", items.get(2).path("description").asText());

        mockMvc.perform(get("/api/batches/NO-SUCH/conditional-releases"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/" + batchKey + "/conditional-releases/NO-SUCH"))
                .andExpect(status().isNotFound());
        // 条件不属于该批次 → 404
        String other = "BK-Q2-" + unique();
        createBatch(other, List.of("t1"), 201);
        mockMvc.perform(get("/api/batches/" + other + "/conditional-releases/C-Q1"))
                .andExpect(status().isNotFound());
        clearItem(other, "C-Q1", "ops", "OPERATIONS", "CK-X", "1", "e", 404);
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> toTextList(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    private void createBatch(String batchKey, List<String> items, int expected) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateCmd("CK-B-" + unique(), batchKey,
                "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), items));
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode,
                             String batchNo, Instant producedAt, List<String> requiredTests) {
    }

    private void submitTest(String batchKey, String testKey, String item, String result,
                            String inspector) throws Exception {
        String body = objectMapper.writeValueAsString(new TestCmd("CK-T-" + unique(), testKey,
                item, result, inspector));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private MvcResult createConditional(String batchKey, String actor, String role,
                                        String commandKey, String conditionKey,
                                        List<String> conditions, String expiresAt,
                                        int expected) throws Exception {
        String body = objectMapper.writeValueAsString(new CondCmd(commandKey, conditionKey,
                conditions, Instant.parse(expiresAt)));
        return mockMvc.perform(post("/api/batches/" + batchKey + "/conditional-releases")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private record CondCmd(String commandKey, String conditionKey, List<String> conditions,
                           Instant expiresAt) {
    }

    private MvcResult clearItem(String batchKey, String conditionKey, String actor, String role,
                                String commandKey, String itemKey, String evidence,
                                int expected) throws Exception {
        String body = "{\"commandKey\":\"" + commandKey + "\",\"itemKey\":\"" + itemKey
                + "\",\"evidence\":\"" + evidence + "\"}";
        return mockMvc.perform(post("/api/batches/" + batchKey + "/conditional-releases/"
                        + conditionKey + "/clearances")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
    }

    private void approve(String batchKey, String actor, String role, String commandKey,
                         int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private void recall(String batchKey, String actor, String reason, String commandKey,
                        int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\""
                                + reason + "\"}"))
                .andExpect(status().is(expected));
    }

    private String currentStatus(String batchKey) throws Exception {
        return history(batchKey).path("batch").path("status").asText();
    }

    private JsonNode history(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return readJson(result);
    }

    private JsonNode conditionDetail(String batchKey, String conditionKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey
                        + "/conditional-releases/" + conditionKey))
                .andExpect(status().isOk()).andReturn();
        return readJson(result);
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = readJson(result);
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
