package com.example.starter.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次条件放行端到端测试：条件登记与逐条核销、到期自动降级、批准前置校验、
 * commandKey/conditionKey 幂等、召回与核销并发裁决；全部基于真实 H2（MODE=MySQL）数据库。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:conditiondb;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc
class ConditionalReleaseFlowTest {

    private static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");

    /**
     * 可控时钟：到期判定不依赖后台任务与睡眠，直接拨放到期时刻之后。
     */
    @TestConfiguration
    static class MutableClockConfig {
        static final AtomicReference<Instant> NOW = new AtomicReference<>(T0);

        @Bean
        @Primary
        Clock mutableClock() {
            return new Clock() {
                @Override
                public Instant instant() {
                    return NOW.get();
                }

                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        MutableClockConfig.NOW.set(T0);
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM condition_item");
        jdbc.update("DELETE FROM conditional_release");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程：登记 → 期内可用 → 逐条核销 → RELEASED ----------

    @Test
    void createCondition_thenCloseAllItemsSameTransaction_released() throws Exception {
        String batchKey = "BK-COND-HAPPY-" + unique();
        preparePendingBatch(batchKey, List.of("外观", "含量"));

        String conditionKey = "COND-" + unique();
        Instant expiresAt = T0.plusSeconds(7200);
        MvcResult created = createCondition(batchKey, "CK-C1", conditionKey, expiresAt,
                List.of(item("c-1", "补充留样"), item("c-2", "加贴标签")),
                "qa-2", "QUALITY", 201);
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        assertEquals("CONDITIONAL", body.path("batchStatus").asText());
        assertFalse(body.path("expired").asBoolean());
        assertEquals(2, body.path("items").size());
        assertEquals("c-1", body.path("items").get(0).path("itemKey").asText());
        assertFalse(body.path("items").get(0).path("closed").asBoolean());
        assertEquals("CONDITIONAL", currentStatus(batchKey));
        assertTrue(availableKeys().contains(batchKey), "条件期内批次应可标记为可用");

        // 核销必须由与创建不同的批准角色逐条提交
        MvcResult close1 = closeCondition(conditionKey, "CK-X1", "c-1", "留样照片已归档",
                "ops-2", "OPERATIONS", 201);
        JsonNode close1Body = objectMapper.readTree(close1.getResponse().getContentAsString());
        assertEquals(1, close1Body.path("remainingOpen").asInt());
        assertEquals("CONDITIONAL", close1Body.path("batchStatus").asText());

        MvcResult close2 = closeCondition(conditionKey, "CK-X2", "c-2", "标签复检合格",
                "ops-2", "OPERATIONS", 201);
        JsonNode close2Body = objectMapper.readTree(close2.getResponse().getContentAsString());
        assertEquals(0, close2Body.path("remainingOpen").asInt());
        // 全部子项核销后同一事务内转为既有 RELEASED 状态
        assertEquals("RELEASED", close2Body.path("batchStatus").asText());
        assertEquals("RELEASED", currentStatus(batchKey));

        // 明细保留全部条件放行与核销记录
        JsonNode detail = conditionDetail(conditionKey);
        assertEquals(2, detail.path("items").size());
        assertTrue(detail.path("items").get(0).path("closed").asBoolean());
        assertEquals("ops-2", detail.path("items").get(0).path("closerId").asText());
        assertEquals("留样照片已归档", detail.path("items").get(0).path("evidence").asText());
        assertTrue(detail.path("items").get(1).path("closed").asBoolean());

        // 批次历史包含条件放行记录，稳定排序
        JsonNode history = history(batchKey);
        assertEquals(1, history.path("conditions").size());
        assertEquals(conditionKey, history.path("conditions").get(0).path("conditionKey").asText());
    }

    @Test
    void conditionItems_areStableSortedBySequence() throws Exception {
        String batchKey = "BK-COND-SORT-" + unique();
        preparePendingBatch(batchKey, List.of("t1"));
        String conditionKey = "COND-" + unique();
        // 乱序提交 itemKey，响应与明细必须按 seq 稳定排序
        createCondition(batchKey, "CK-C1", conditionKey, T0.plusSeconds(3600),
                List.of(item("zeta", "z"), item("alpha", "a"), item("mid", "m")),
                "qa-2", "QUALITY", 201);
        JsonNode detail = conditionDetail(conditionKey);
        assertEquals(List.of("zeta", "alpha", "mid"), List.of(
                detail.path("items").get(0).path("itemKey").asText(),
                detail.path("items").get(1).path("itemKey").asText(),
                detail.path("items").get(2).path("itemKey").asText()));
        assertEquals(List.of(1, 2, 3), List.of(
                detail.path("items").get(0).path("seq").asInt(),
                detail.path("items").get(1).path("seq").asInt(),
                detail.path("items").get(2).path("seq").asInt()));
    }

    // ---------- 到期自动降级 ----------

    @Test
    void expiry_degradesAvailability_closeReturns422ListingOpenItems_recordKept() throws Exception {
        String batchKey = "BK-COND-EXP-" + unique();
        preparePendingBatch(batchKey, List.of("t1", "t2"));
        String conditionKey = "COND-" + unique();
        Instant expiresAt = T0.plusSeconds(3600);
        createCondition(batchKey, "CK-C1", conditionKey, expiresAt,
                List.of(item("c-1", "条件一"), item("c-2", "条件二")),
                "qa-2", "QUALITY", 201);
        closeCondition(conditionKey, "CK-X1", "c-1", "证明一", "ops-2", "OPERATIONS", 201);
        assertTrue(availableKeys().contains(batchKey));

        // 到期时刻已到：不回写状态、不依赖后台任务，可用查询实时降级
        MutableClockConfig.NOW.set(expiresAt);
        assertFalse(availableKeys().contains(batchKey), "到期仍有未核销子项时必须降级为不可用");
        assertEquals("CONDITIONAL", currentStatus(batchKey), "到期判定不回写批次状态");
        JsonNode detail = conditionDetail(conditionKey);
        assertTrue(detail.path("expired").asBoolean());
        assertFalse(detail.path("items").get(1).path("closed").asBoolean());

        // 到期后核销 → 422 并列出未核销子项
        MvcResult blocked = closeCondition(conditionKey, "CK-X2", "c-2", "迟到的证明",
                "ops-2", "OPERATIONS", 422);
        JsonNode err = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertEquals("CONDITION_BLOCKED", err.path("code").asText());
        assertEquals("CONDITION_EXPIRED", err.path("reason").asText());
        assertEquals(List.of("c-2"), toStringList(err.path("openItems")));

        // 不物理删除条件记录，已核销记录保留
        JsonNode after = conditionDetail(conditionKey);
        assertEquals(2, after.path("items").size());
        assertTrue(after.path("items").get(0).path("closed").asBoolean());
        assertFalse(after.path("items").get(1).path("closed").asBoolean());
    }

    @Test
    void afterExpiry_canCreateNewConditionOrUseDualRoleApproval() throws Exception {
        String batchKey = "BK-COND-RENEW-" + unique();
        preparePendingBatch(batchKey, List.of("t1"));
        String firstKey = "COND-A-" + unique();
        Instant firstExpiry = T0.plusSeconds(600);
        createCondition(batchKey, "CK-C1", firstKey, firstExpiry,
                List.of(item("c-1", "条件一")), "qa-2", "QUALITY", 201);

        // 到期降级后可重新创建新的条件放行
        MutableClockConfig.NOW.set(firstExpiry.plusSeconds(1));
        assertFalse(availableKeys().contains(batchKey));
        String secondKey = "COND-B-" + unique();
        createCondition(batchKey, "CK-C2", secondKey, firstExpiry.plusSeconds(3600),
                List.of(item("d-1", "新条件一")), "qa-2", "QUALITY", 201);
        assertEquals("CONDITIONAL", currentStatus(batchKey));
        assertTrue(availableKeys().contains(batchKey), "新条件放行期内恢复可用");
        // 旧条件放行记录保留
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM conditional_release WHERE batch_key = ?", Integer.class, batchKey));

        // 新条件放行再次到期后，允许走既有双角色批准流程
        MutableClockConfig.NOW.set(firstExpiry.plusSeconds(3601));
        approve(batchKey, "qa-3", "QUALITY", "CK-A1", 201);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        approve(batchKey, "ops-3", "OPERATIONS", "CK-A2", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
        // 两条条件放行记录均仍可查
        JsonNode history = history(batchKey);
        assertEquals(2, history.path("conditions").size());
    }

    // ---------- 创建前置校验 ----------

    @Test
    void createCondition_guards() throws Exception {
        String pending = "BK-COND-G1-" + unique();
        preparePendingBatch(pending, List.of("t1"));

        // 未全部必做检验通过（QUARANTINED）→ 422
        String quarantined = "BK-COND-G2-" + unique();
        createBatchRaw(quarantined, List.of("t1"));
        createCondition(quarantined, "CK-Q1", "COND-Q-" + unique(), T0.plusSeconds(60),
                List.of(item("c", "d")), "qa-2", "QUALITY", 422);

        // REJECTED → 422
        String rejected = "BK-COND-G3-" + unique();
        createBatchRaw(rejected, List.of("t1"));
        submitTestRaw(rejected, "TK-F", "t1", "FAIL", "inspect-a");
        createCondition(rejected, "CK-R1", "COND-R-" + unique(), T0.plusSeconds(60),
                List.of(item("c", "d")), "qa-2", "QUALITY", 422);

        String conditionKey = "COND-G-" + unique();
        createCondition(pending, "CK-C1", conditionKey, T0.plusSeconds(600),
                List.of(item("c-1", "d1")), "qa-2", "QUALITY", 201);

        // CONDITIONAL 期内不得再建另一条条件放行
        createCondition(pending, "CK-C2", "COND-OTHER-" + unique(), T0.plusSeconds(600),
                List.of(item("x", "y")), "qa-2", "QUALITY", 409);
        // CONDITIONAL 期内不得直接走双角色批准
        approve(pending, "ops-3", "OPERATIONS", "CK-AP-1", 409);

        // expiresAt 不晚于当前时刻 → 400
        String otherPending = "BK-COND-G4-" + unique();
        preparePendingBatch(otherPending, List.of("t1"));
        createCondition(otherPending, "CK-PAST", "COND-PAST-" + unique(), T0,
                List.of(item("c", "d")), "qa-2", "QUALITY", 400);

        // 检验人不得担任条件放行创建批准人 → 422
        createCondition(otherPending, "CK-INSP", "COND-INSP-" + unique(), T0.plusSeconds(60),
                List.of(item("c", "d")), "inspect-a", "QUALITY", 422);

        // 同一 conditionKey 全局只能创建一次（换批次也 409）
        String yetAnother = "BK-COND-G5-" + unique();
        preparePendingBatch(yetAnother, List.of("t1"));
        createCondition(yetAnother, "CK-DUP", conditionKey, T0.plusSeconds(60),
                List.of(item("c", "d")), "qa-2", "QUALITY", 409);
    }

    @Test
    void createCondition_invalidBodies_return400() throws Exception {
        String batchKey = "BK-COND-BAD-" + unique();
        preparePendingBatch(batchKey, List.of("t1"));
        String base = "/api/batches/" + batchKey + "/conditions";

        // 0 条条件
        String zero = "{\"commandKey\":\"c\",\"conditionKey\":\"k0\",\"expiresAt\":\""
                + T0.plusSeconds(600) + "\",\"conditions\":[]}";
        mockMvc.perform(post(base).header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                .contentType(MediaType.APPLICATION_JSON).content(zero)).andExpect(status().isBadRequest());

        // 6 条条件
        String six = "{\"commandKey\":\"c\",\"conditionKey\":\"k6\",\"expiresAt\":\""
                + T0.plusSeconds(600) + "\",\"conditions\":["
                + "{\"itemKey\":\"a\",\"description\":\"x\"},"
                + "{\"itemKey\":\"b\",\"description\":\"x\"},"
                + "{\"itemKey\":\"c\",\"description\":\"x\"},"
                + "{\"itemKey\":\"d\",\"description\":\"x\"},"
                + "{\"itemKey\":\"e\",\"description\":\"x\"},"
                + "{\"itemKey\":\"f\",\"description\":\"x\"}]}";
        mockMvc.perform(post(base).header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                .contentType(MediaType.APPLICATION_JSON).content(six)).andExpect(status().isBadRequest());

        // conditionKey 内重复子项
        String dup = "{\"commandKey\":\"c\",\"conditionKey\":\"kd\",\"expiresAt\":\""
                + T0.plusSeconds(600) + "\",\"conditions\":["
                + "{\"itemKey\":\"a\",\"description\":\"x\"},"
                + "{\"itemKey\":\"a\",\"description\":\"y\"}]}";
        mockMvc.perform(post(base).header("X-Actor-Id", "qa").header("X-Approval-Role", "QUALITY")
                .contentType(MediaType.APPLICATION_JSON).content(dup)).andExpect(status().isBadRequest());
    }

    // ---------- 核销前置校验 ----------

    @Test
    void closeCondition_guards() throws Exception {
        String batchKey = "BK-CLOSE-G-" + unique();
        preparePendingBatch(batchKey, List.of("t1"));
        String conditionKey = "COND-" + unique();
        createCondition(batchKey, "CK-C1", conditionKey, T0.plusSeconds(600),
                List.of(item("c-1", "d1"), item("c-2", "d2")), "qa-2", "QUALITY", 201);

        // 核销角色与创建角色相同 → 409
        closeCondition(conditionKey, "CK-X0", "c-1", "e", "qa-3", "QUALITY", 409);
        // 检验人不得核销 → 422
        closeCondition(conditionKey, "CK-XI", "c-1", "e", "inspect-a", "OPERATIONS", 422);
        // 不存在的 conditionKey → 404；不存在的 itemKey → 404
        closeCondition("COND-NO-SUCH-" + unique(), "CK-XN", "c-1", "e",
                "ops-2", "OPERATIONS", 404);
        closeCondition(conditionKey, "CK-XM", "no-item", "e", "ops-2", "OPERATIONS", 404);

        // 正常核销一次后，换新 commandKey 重复核销同一子项 → 409，不重复计数
        closeCondition(conditionKey, "CK-X1", "c-1", "首次证明", "ops-2", "OPERATIONS", 201);
        closeCondition(conditionKey, "CK-X1-DUP", "c-1", "再次证明", "ops-3", "OPERATIONS", 409);
        Integer closedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM condition_item WHERE condition_key = ? AND item_key = 'c-1'"
                        + " AND closed_at IS NOT NULL", Integer.class, conditionKey);
        assertEquals(1, closedCount, "同一子项核销不得重复计数");
        // 首次核销记录未被重复请求改写
        String evidence = jdbc.queryForObject(
                "SELECT evidence FROM condition_item WHERE condition_key = ? AND item_key = 'c-1'",
                String.class, conditionKey);
        assertEquals("首次证明", evidence);
    }

    // ---------- commandKey 幂等 ----------

    @Test
    void createCondition_commandKeyReplayConflictAndFailureDoesNotOccupyKey() throws Exception {
        String batchKey = "BK-COND-IDEM-" + unique();
        createBatchRaw(batchKey, List.of("t1"));

        String body = conditionBody("CK-IDEM-1", "COND-IDEM-" + unique(), T0.plusSeconds(600),
                List.of(item("c-1", "d1")));
        // 首次失败（批次仍 QUARANTINED，422）不占用 commandKey
        mockMvc.perform(post("/api/batches/" + batchKey + "/conditions")
                .header("X-Actor-Id", "qa-2").header("X-Approval-Role", "QUALITY")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
        // 补交全部必做检验 PASS → PENDING_RELEASE（批次不重建）
        submitAllPassing(batchKey, List.of("t1"));

        // 同一 commandKey 同参重试 → 首次成功结果 201
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/conditions")
                .header("X-Actor-Id", "qa-2").header("X-Approval-Role", "QUALITY")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/conditions")
                .header("X-Actor-Id", "qa-2").header("X-Approval-Role", "QUALITY")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());

        // 同键异参（到期时刻不同）→ 409
        String changed = conditionBody("CK-IDEM-1", "COND-IDEM-" + unique(), T0.plusSeconds(900),
                List.of(item("c-1", "d1")));
        mockMvc.perform(post("/api/batches/" + batchKey + "/conditions")
                .header("X-Actor-Id", "qa-2").header("X-Approval-Role", "QUALITY")
                .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());

        // 失败不占键：command_log 中只应有成功的一条
        Integer logged = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_log WHERE command_type = 'CREATE_CONDITION'"
                        + " AND command_key = 'CK-IDEM-1'", Integer.class);
        assertEquals(1, logged);
    }

    @Test
    void closeCondition_commandKeyReplayReturnsFirstResult() throws Exception {
        String batchKey = "BK-CLOSE-IDEM-" + unique();
        preparePendingBatch(batchKey, List.of("t1"));
        String conditionKey = "COND-" + unique();
        createCondition(batchKey, "CK-C1", conditionKey, T0.plusSeconds(600),
                List.of(item("c-1", "d1"), item("c-2", "d2")), "qa-2", "QUALITY", 201);

        String body = closeBody("CK-CLOSE-1", "c-1", "原始证明");
        MvcResult first = mockMvc.perform(post("/api/conditions/" + conditionKey + "/closings")
                .header("X-Actor-Id", "ops-2").header("X-Approval-Role", "OPERATIONS")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放 → 返回首次响应（remainingOpen 快照为 1），不产生第二次核销
        MvcResult replay = mockMvc.perform(post("/api/conditions/" + conditionKey + "/closings")
                .header("X-Actor-Id", "ops-2").header("X-Approval-Role", "OPERATIONS")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, JsonNodeCounts.int1(first));

        // 同键异参（不同子项）→ 409
        mockMvc.perform(post("/api/conditions/" + conditionKey + "/closings")
                .header("X-Actor-Id", "ops-2").header("X-Approval-Role", "OPERATIONS")
                .contentType(MediaType.APPLICATION_JSON)
                .content(closeBody("CK-CLOSE-1", "c-2", "别的证明")))
                .andExpect(status().isConflict());
    }

    // ---------- 并发：召回与核销按提交顺序裁决 ----------

    @Test
    void concurrentRecallAndClose_arbitratedByCommitOrder_completedClosingsKept() throws Exception {
        String batchKey = "BK-RACE-CR-" + unique();
        preparePendingBatch(batchKey, List.of("t1"));
        String conditionKey = "COND-" + unique();
        createCondition(batchKey, "CK-C1", conditionKey, T0.plusSeconds(3600),
                List.of(item("c-1", "d1"), item("c-2", "d2")), "qa-2", "QUALITY", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/conditions/" + conditionKey + "/closings")
                        .header("X-Actor-Id", "ops-2").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(closeBody("CK-RACE-CLOSE", "c-1", "并发核销证明"))),
                () -> callStatus(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RACE-RECALL\",\"reason\":\"祖先批次召回\"}"))
        );

        int closeCode = results.get(0).get(30, TimeUnit.SECONDS);
        int recallCode = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals("RECALLED", currentStatus(batchKey), "两种裁决顺序下召回最终都应生效或批次已被召回");

        Integer closed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM condition_item WHERE condition_key = ? AND item_key = 'c-1'"
                        + " AND closed_at IS NOT NULL", Integer.class, conditionKey);
        if (closeCode == 201) {
            // 核销先提交：召回仍能生效，但已完成的核销记录不得撤销
            assertEquals(201, recallCode);
            assertEquals(1, closed, "先提交的核销记录必须保留");
            String evidence = jdbc.queryForObject(
                    "SELECT evidence FROM condition_item WHERE condition_key = ? AND item_key = 'c-1'",
                    String.class, conditionKey);
            assertNotNull(evidence);
        } else {
            // 召回先提交：条件核销 422（BATCH_RECALLED），无核销落库
            assertEquals(422, closeCode);
            assertEquals(201, recallCode);
            assertEquals(0, closed, "召回先提交时不得有核销落库");
        }
    }

    @Test
    void concurrentCloseSameItem_onlyCountedOnce() throws Exception {
        String batchKey = "BK-RACE-CC-" + unique();
        preparePendingBatch(batchKey, List.of("t1"));
        String conditionKey = "COND-" + unique();
        createCondition(batchKey, "CK-C1", conditionKey, T0.plusSeconds(3600),
                List.of(item("c-1", "d1")), "qa-2", "QUALITY", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/conditions/" + conditionKey + "/closings")
                        .header("X-Actor-Id", "ops-2").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(closeBody("CK-CC-1", "c-1", "证明一"))),
                () -> callStatus(post("/api/conditions/" + conditionKey + "/closings")
                        .header("X-Actor-Id", "ops-3").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(closeBody("CK-CC-2", "c-1", "证明二")))
        );

        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        int ok = (first == 201 ? 1 : 0) + (second == 201 ? 1 : 0);
        int conflict = (first == 409 ? 1 : 0) + (second == 409 ? 1 : 0);
        assertEquals(1, ok, "同一子项并发核销只能成功一次");
        assertEquals(1, conflict, "失败者必须收到 409");

        Integer closed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM condition_item WHERE condition_key = ? AND closed_at IS NOT NULL",
                Integer.class, conditionKey);
        assertEquals(1, closed, "核销不得重复计数");
        // 批次仍为 CONDITIONAL（单条件已核销但尚未全部完成——此处仅一项时成功核销应转 RELEASED）
        // 本用例只有一个子项：赢者提交同事务把批次转 RELEASED
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void concurrentCreateSameConditionKey_sameWinnerForBoth() throws Exception {
        String batchKey = "BK-RACE-CK-" + unique();
        preparePendingBatch(batchKey, List.of("t1"));
        String body = conditionBody("CK-RACE-CREATE", "COND-RACE-" + unique(),
                T0.plusSeconds(600), List.of(item("c-1", "d1")));

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/conditions")
                        .header("X-Actor-Id", "qa-2").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batchKey + "/conditions")
                        .header("X-Actor-Id", "qa-2").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发创建均返回首次结果");
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM conditional_release WHERE batch_key = ?",
                Integer.class, batchKey));
    }

    @Test
    void recalledBatchConditionRecord_andLineageUntouched() throws Exception {
        // 核销先提交再召回：已核销记录保留；未核销子项仍可在明细中查询
        String batchKey = "BK-COND-KEEP-" + unique();
        preparePendingBatch(batchKey, List.of("t1"));
        String conditionKey = "COND-" + unique();
        createCondition(batchKey, "CK-C1", conditionKey, T0.plusSeconds(3600),
                List.of(item("c-1", "d1"), item("c-2", "d2")), "qa-2", "QUALITY", 201);
        closeCondition(conditionKey, "CK-X1", "c-1", "已核销证明", "ops-2", "OPERATIONS", 201);
        recall(batchKey, "u1", "祖先召回", "CK-R1", 201);
        assertEquals("RECALLED", currentStatus(batchKey));
        assertFalse(availableKeys().contains(batchKey));

        // 召回后再核销任何子项均 422，openItems 为剩余未核销项
        MvcResult blocked = closeCondition(conditionKey, "CK-X2", "c-2", "e",
                "ops-2", "OPERATIONS", 422);
        JsonNode err = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertEquals("BATCH_RECALLED", err.path("reason").asText());
        assertEquals(List.of("c-2"), toStringList(err.path("openItems")));

        // 明细不删除：一闭一开
        JsonNode detail = conditionDetail(conditionKey);
        assertTrue(detail.path("items").get(0).path("closed").asBoolean());
        assertFalse(detail.path("items").get(1).path("closed").asBoolean());
    }

    // ---------- helpers ----------

    private record ItemInput(String itemKey, String description) {
    }

    private static ItemInput item(String key, String desc) {
        return new ItemInput(key, desc);
    }

    private record CreateConditionCmd(String commandKey, String conditionKey, Instant expiresAt,
                                      List<ItemInput> conditions) {
    }

    private record CloseCmd(String commandKey, String itemKey, String evidence) {
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private record CreateBatchCmd(String commandKey, String batchKey, String productCode,
                                  String batchNo, Instant producedAt, List<String> requiredTests) {
    }

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String conditionBody(String commandKey, String conditionKey, Instant expiresAt,
                                 List<ItemInput> items) throws Exception {
        return objectMapper.writeValueAsString(
                new CreateConditionCmd(commandKey, conditionKey, expiresAt, items));
    }

    private String closeBody(String commandKey, String itemKey, String evidence) throws Exception {
        return objectMapper.writeValueAsString(new CloseCmd(commandKey, itemKey, evidence));
    }

    private void createBatchRaw(String batchKey, List<String> items) throws Exception {
        String body = objectMapper.writeValueAsString(new CreateBatchCmd("CK-B-" + unique(),
                batchKey, "PROD-1", "LOT-1", Instant.parse("2026-01-02T03:04:05Z"), items));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private void preparePendingBatch(String batchKey, List<String> items) throws Exception {
        createBatchRaw(batchKey, items);
        submitAllPassing(batchKey, items);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));
    }

    private void submitAllPassing(String batchKey, List<String> items) throws Exception {
        for (int i = 0; i < items.size(); i++) {
            String body = objectMapper.writeValueAsString(new TestCmd("CK-T-" + unique(),
                    "TK-" + batchKey + "-" + i, items.get(i), "PASS", "inspect-a"));
            mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }
    }

    private void submitTestRaw(String batchKey, String testKey, String item, String result,
                               String inspector) throws Exception {
        String body = objectMapper.writeValueAsString(
                new TestCmd("CK-T-" + unique(), testKey, item, result, inspector));
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    private MvcResult createCondition(String batchKey, String commandKey, String conditionKey,
                                      Instant expiresAt, List<ItemInput> items,
                                      String actor, String role, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/conditions")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conditionBody(commandKey, conditionKey, expiresAt, items)))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private MvcResult closeCondition(String conditionKey, String commandKey, String itemKey,
                                     String evidence, String actor, String role,
                                     int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/conditions/" + conditionKey + "/closings")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(closeBody(commandKey, itemKey, evidence)))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private void approve(String batchKey, String actor, String role, String commandKey,
                         int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private void recall(String batchKey, String actor, String reason, String commandKey,
                        int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/recall")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private JsonNode conditionDetail(String conditionKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/conditions/" + conditionKey))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode history(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        return history(batchKey).path("batch").path("status").asText();
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        array.forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
    }

    private List<String> toStringList(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    private int callStatus(MockHttpServletRequestBuilder req) {
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

    /**
     * 小型辅助，避免在断言行里直接解析 JSON。
     */
    private static final class JsonNodeCounts {
        static int int1(MvcResult result) throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readTree(result.getResponse().getContentAsString()).path("remainingOpen").asInt();
        }
    }
}
