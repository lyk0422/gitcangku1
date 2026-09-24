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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
 * 批次有效期到期与复检延期测试：到期可用性/拦截、延期上限、双人复检、
 * 召回祖先交互、拆分子批继承，以及 commandKey/extensionKey 幂等与并发提交顺序裁决。
 * 全部使用可控服务端时钟与真实 H2（MODE=MySQL）数据库。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchShelfLifeExtensionTest {

    private static final Instant T0 = Instant.parse("2026-02-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TimeProvider clock;

    @BeforeEach
    void setUp() {
        clock.setFixed(T0);
        jdbc.update("DELETE FROM batch_extension");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    // ---------- 到期可用性 ----------

    @Test
    void expiry_excludesFromAvailable_blocksApproveAndSplit_keepsStatus() throws Exception {
        String batchKey = createReleased("BK-EXP-", 100);
        assertEquals(100, shelfLife(batchKey).path("shelfLifeMinutes").asInt());

        // 未到期：可用，剩余 50 分钟
        clock.setFixed(T0.plusSeconds(50 * 60));
        assertTrue(availableKeys().contains(batchKey));
        JsonNode shelf = shelfLife(batchKey);
        assertFalse(shelf.path("expired").asBoolean());
        assertEquals(50, shelf.path("remainingMinutes").asLong());
        assertFalse(expiredKeys().contains(batchKey));

        // 到达有效期（等于）即到期：可用查询排除，进入到期清单，状态不改写
        clock.setFixed(T0.plusSeconds(100 * 60));
        shelf = shelfLife(batchKey);
        assertTrue(shelf.path("expired").asBoolean());
        assertEquals(0, shelf.path("remainingMinutes").asLong());
        assertFalse(availableKeys().contains(batchKey));
        assertTrue(expiredKeys().contains(batchKey));
        assertEquals("RELEASED", currentStatus(batchKey));

        // 到期禁止批准和拆分 → 422
        approve(batchKey, "qa-late", "QUALITY", "CK-QX", 422);
        split(batchKey, "CK-SX", 422);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void pendingReleaseBatch_expiring_blocksApproval() throws Exception {
        String batchKey = createBatch("BK-PEND-", List.of("t1"), 2);
        clock.setFixed(T0.plusSeconds(60));
        submitTest(batchKey, "t1", "PASS", "insp");
        assertEquals("PENDING_RELEASE", currentStatus(batchKey));

        clock.setFixed(T0.plusSeconds(120));
        approve(batchKey, "qa-1", "QUALITY", "CK-A1", 422);
        assertEquals("PENDING_RELEASE", currentStatus(batchKey), "到期不改写批次状态");
    }

    // ---------- 延期主流程：恢复可用 ----------

    @Test
    void extension_submitThenConfirm_extendsValidityAndRestoresAvailable() throws Exception {
        String batchKey = createReleased("BK-EXT-", 100);
        clock.setFixed(T0.plusSeconds(100 * 60));
        assertTrue(expiredKeys().contains(batchKey));
        assertFalse(availableKeys().contains(batchKey));

        // 提交延期：仅落待确认记录，有效期与可用性不变
        String extKey = submitExtension(batchKey, "rc-1", "EK-1", "合格", 60, "CK-ES-1", 201);
        assertEquals("SUBMITTED", extension(batchKey, extKey).path("status").asText());
        assertTrue(shelfLife(batchKey).path("expired").asBoolean());
        assertFalse(availableKeys().contains(batchKey));

        // 另一批准角色确认：同一事务顺延有效期并恢复可用
        confirmExtension(batchKey, extKey, "cf-1", "QUALITY", "CK-EC-1", 201);
        JsonNode shelf = shelfLife(batchKey);
        assertFalse(shelf.path("expired").asBoolean());
        assertEquals(T0.plusSeconds(160 * 60).toString(), shelf.path("expiresAt").asText());
        assertEquals(T0.plusSeconds(100 * 60).toString(), shelf.path("baseExpiresAt").asText());
        assertEquals(60, shelf.path("remainingMinutes").asLong());
        assertEquals(1, shelf.path("extensionCount").asInt());
        assertEquals(60, shelf.path("extendedMinutes").asInt());
        assertTrue(availableKeys().contains(batchKey));
        assertFalse(expiredKeys().contains(batchKey));
        assertEquals("RELEASED", currentStatus(batchKey));

        // 批次历史含已生效延期，既有检验/批准记录不变
        JsonNode history = history(batchKey);
        assertEquals(1, history.path("extensions").size());
        assertEquals("CONFIRMED", history.path("extensions").get(0).path("status").asText());
        assertEquals("cf-1", history.path("extensions").get(0).path("confirmerId").asText());
        assertEquals(1, history.path("tests").size());
        assertEquals(2, history.path("approvals").size());
    }

    // ---------- 延期上限 ----------

    @Test
    void extension_cumulativeMinutesCannotExceedTwiceShelfLife() throws Exception {
        String batchKey = createReleased("BK-CAP-M-", 100);
        submitAndConfirm(batchKey, "EK-1", 100);
        submitAndConfirm(batchKey, "EK-2", 100);
        assertEquals(200, shelfLife(batchKey).path("extendedMinutes").asInt());

        // 第三次顺延 1 分钟会使累计 201 超过两倍保质 200 → 提交即 422，不留记录
        submitExtension(batchKey, "rc-1", "EK-3", "合格", 1, "CK-ES-3", 422);
        assertEquals(2, extensionHistory(batchKey).size());
        assertEquals(200, shelfLife(batchKey).path("extendedMinutes").asInt());
    }

    @Test
    void extension_atMostThreeTimes() throws Exception {
        String batchKey = createReleased("BK-CAP-C-", 100000);
        submitAndConfirm(batchKey, "EK-1", 60);
        submitAndConfirm(batchKey, "EK-2", 60);
        submitAndConfirm(batchKey, "EK-3", 60);
        assertEquals(3, shelfLife(batchKey).path("extensionCount").asInt());

        // 第四次提交 → 422
        submitExtension(batchKey, "rc-1", "EK-4", "合格", 60, "CK-ES-4", 422);
        assertEquals(3, extensionHistory(batchKey).size());
    }

    @Test
    void extension_invalidMinutesAndConclusion_rejected() throws Exception {
        String batchKey = createReleased("BK-INV-", 100);
        // 顺延分钟越界 → 400
        mockMvc.perform(extensionPost(batchKey, "rc-1",
                body("CK-E0", "EK-0", "合格", 0))).andExpect(status().isBadRequest());
        mockMvc.perform(extensionPost(batchKey, "rc-1",
                body("CK-E0B", "EK-0B", "合格", 43201))).andExpect(status().isBadRequest());
        // 结论非空但不合格 → 422
        submitExtension(batchKey, "rc-1", "EK-NQ", "不合格", 60, "CK-E1", 422);
        submitExtension(batchKey, "rc-1", "EK-NQ2", " 待复检 ", 60, "CK-E2", 422);
        // 结论为空 → 400
        mockMvc.perform(extensionPost(batchKey, "rc-1",
                body("CK-E3", "EK-E3", "", 60))).andExpect(status().isBadRequest());
        assertEquals(0, extensionHistory(batchKey).size());
        assertEquals(100, shelfLife(batchKey).path("shelfLifeMinutes").asInt());
    }

    // ---------- 双人复检 ----------

    @Test
    void extension_reviewerMustDifferFromApprovers_confirmerMustDifferFromReviewer() throws Exception {
        String batchKey = createReleased("BK-TWO-", 100);

        // 复检人是任一原批准人 → 422
        submitExtension(batchKey, "qa-1", "EK-A", "合格", 60, "CK-A", 422);
        submitExtension(batchKey, "ops-1", "EK-B", "合格", 60, "CK-B", 422);

        // 合法复检人提交，确认人若与复检人相同 → 422，记录停留在 SUBMITTED、有效期不变
        String extKey = "EK-C";
        submitExtension(batchKey, "rc-9", extKey, "合格", 60, "CK-C", 201);
        confirmExtension(batchKey, extKey, "rc-9", "QUALITY", "CK-CC", 422);
        assertEquals("SUBMITTED", extension(batchKey, extKey).path("status").asText());
        assertEquals(0, shelfLife(batchKey).path("extensionCount").asInt());

        // 缺批准角色头 → 400
        mockMvc.perform(post("/api/batches/" + batchKey + "/extensions/" + extKey + "/confirm")
                        .header("X-Actor-Id", "cf-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-CC2\"}"))
                .andExpect(status().isBadRequest());

        // 另一批准角色确认成功
        confirmExtension(batchKey, extKey, "cf-1", "OPERATIONS", "CK-CC3", 201);
        assertEquals("CONFIRMED", extension(batchKey, extKey).path("status").asText());
        assertEquals(1, shelfLife(batchKey).path("extensionCount").asInt());
    }

    @Test
    void extension_requiresReleasedBatchWithTwoApprovals() throws Exception {
        // PENDING_RELEASE：检验全过但未双批准 → 422
        String pending = createBatch("BK-NR-P-", List.of("t1"), 100);
        submitTest(pending, "t1", "PASS", "insp");
        submitExtension(pending, "rc-1", "EK-P", "合格", 60, "CK-P", 422);

        // RELEASE_REVIEW：仅一名批准人 → 422
        String reviewing = createBatch("BK-NR-R-", List.of("t1"), 100);
        submitTest(reviewing, "t1", "PASS", "insp");
        approve(reviewing, "qa-1", "QUALITY", "CK-Q1", 201);
        submitExtension(reviewing, "rc-1", "EK-R", "合格", 60, "CK-R", 422);

        // 未知批次 → 404
        mockMvc.perform(extensionPost("NO-SUCH", "rc-1",
                body("CK-X", "EK-X", "合格", 60))).andExpect(status().isNotFound());
    }

    // ---------- 召回交互 ----------

    @Test
    void recalledAncestor_blocksDescendantExtensions_andKeepsEffectiveRecord() throws Exception {
        String root = createReleased("BK-RC-R-", 100000);
        String child = splitTwoChildren(root).get(0);
        releaseBatch(child, "insp-c");
        // 子批在祖先召回前已生效一笔延期，有效期充足、未到期
        submitAndConfirm(child, "EK-C-1", 120);
        assertFalse(expiredKeys().contains(child));
        assertTrue(availableKeys().contains(child));

        recall(root, "u", "根批原料污染", "CK-RC-1", 201);

        // 子批立即不可用，但未到期；已生效延期记录保留
        assertFalse(availableKeys().contains(child));
        assertFalse(expiredKeys().contains(child));
        assertEquals("RELEASED", currentStatus(child));
        JsonNode exts = extensionHistory(child);
        assertEquals(1, exts.size());
        assertEquals("CONFIRMED", exts.get(0).path("status").asText());

        // 祖先召回后，该后代的一切新延期提交一律 422
        submitExtension(child, "rc-2", "EK-C-2", "合格", 60, "CK-ES-X", 422);
        assertEquals(1, extensionHistory(child).size());
    }

    @Test
    void submittedExtension_cannotConfirmAfterAncestorRecall() throws Exception {
        String root = createReleased("BK-RC2-R-", 100000);
        List<String> children = splitTwoChildren(root);
        String child = children.get(0);
        releaseBatch(child, "insp-c");
        String extKey = submitExtension(child, "rc-1", "EK-PEND-", "合格", 60, "CK-ES-P", 201);

        recall(root, "u", "根批召回", "CK-RC-2", 201);

        // 召回后确认待生效延期 → 422，记录保持 SUBMITTED，有效期不顺延
        JsonNode before = shelfLife(child);
        confirmExtension(child, extKey, "cf-1", "QUALITY", "CK-EC-P", 422);
        assertEquals("SUBMITTED", extension(child, extKey).path("status").asText());
        JsonNode after = shelfLife(child);
        assertEquals(before.path("expiresAt").asText(), after.path("expiresAt").asText());
        assertEquals(0, after.path("extensionCount").asInt());
    }

    // ---------- 拆分继承 ----------

    @Test
    void splitChild_inheritsShelfLifeButRecomputesExpiryWithoutExtensions() throws Exception {
        String parent = createReleased("BK-SI-P-", 100);
        // 父批延期 60 分钟
        submitAndConfirm(parent, "EK-P-1", 60);
        JsonNode parentShelf = shelfLife(parent);
        assertEquals(T0.plusSeconds(160 * 60).toString(), parentShelf.path("expiresAt").asText());

        List<String> children = splitTwoChildren(parent);
        String child = children.get(0);
        JsonNode childShelf = shelfLife(child);
        // 继承保质分钟，但按自身生产时间重算有效期，且不继承父批延期
        assertEquals(100, childShelf.path("shelfLifeMinutes").asInt());
        assertEquals(T0.plusSeconds(100 * 60).toString(), childShelf.path("baseExpiresAt").asText());
        assertEquals(T0.plusSeconds(100 * 60).toString(), childShelf.path("expiresAt").asText());
        assertEquals(0, childShelf.path("extensionCount").asInt());
        assertEquals(0, extensionHistory(child).size());
    }

    // ---------- 幂等 ----------

    @Test
    void extensionCommandKey_replaySameParams_conflictOnChangedParams_failureFreesKey() throws Exception {
        String batchKey = createReleased("BK-IDEM-", 100);

        // 首次提交
        String body1 = body("CK-IDEM-1", "EK-IDEM-1", "合格", 60);
        MvcResult first = mockMvc.perform(extensionPost(batchKey, "rc-1", body1))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(extensionPost(batchKey, "rc-1", body1))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(1, extensionHistory(batchKey).size());

        // 同 commandKey 换顺延分钟 → 409
        mockMvc.perform(extensionPost(batchKey, "rc-1", body("CK-IDEM-1", "EK-IDEM-1", "合格", 61)))
                .andExpect(status().isConflict());
        // 同 commandKey 换复检人 → 409
        mockMvc.perform(extensionPost(batchKey, "rc-2", body("CK-IDEM-1", "EK-IDEM-1", "合格", 60)))
                .andExpect(status().isConflict());

        // 失败不占键：先用 CK-IDEM-2 以原批准人身份提交（422），再以合法复检人同键成功
        submitExtension(batchKey, "qa-1", "EK-IDEM-2", "合格", 60, "CK-IDEM-2", 422);
        submitExtension(batchKey, "rc-2", "EK-IDEM-2", "合格", 60, "CK-IDEM-2", 201);

        // extensionKey 全局唯一：不同 commandKey 复用同一 extensionKey → 409
        submitExtension(batchKey, "rc-2", "EK-IDEM-1", "合格", 30, "CK-IDEM-3", 409);
    }

    @Test
    void confirmCommandKey_replayAndConflict_failureFreesKey() throws Exception {
        String batchKey = createReleased("BK-CIDEM-", 100);
        submitExtension(batchKey, "rc-1", "EK-CIDEM", "合格", 60, "CK-ES", 201);

        String body = "{\"commandKey\":\"CK-EC-IDEM\"}";
        MvcResult first = mockMvc.perform(confirmPost(batchKey, "EK-CIDEM", "cf-1", "QUALITY", body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(confirmPost(batchKey, "EK-CIDEM", "cf-1", "QUALITY", body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        // 同键换确认人 → 409
        mockMvc.perform(confirmPost(batchKey, "EK-CIDEM", "cf-2", "QUALITY", body))
                .andExpect(status().isConflict());
        // 顺延只生效一次
        assertEquals(60, shelfLife(batchKey).path("extendedMinutes").asInt());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentConfirmSameExtension_onlyOneTakesEffect() throws Exception {
        String batchKey = createReleased("BK-RACE-CF-", 100);
        submitExtension(batchKey, "rc-1", "EK-RACE", "合格", 60, "CK-ES", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(confirmPost(batchKey, "EK-RACE", "cf-1", "QUALITY",
                        "{\"commandKey\":\"CK-CF-1\"}")),
                () -> callStatus(confirmPost(batchKey, "EK-RACE", "cf-2", "OPERATIONS",
                        "{\"commandKey\":\"CK-CF-2\"}"))
        );
        int r1 = results.get(0).get(30, TimeUnit.SECONDS);
        int r2 = results.get(1).get(30, TimeUnit.SECONDS);
        // 同一 extensionKey 最多生效一次：一个 201，另一个因已确认 409
        assertEquals(1, (r1 == 201 ? 1 : 0) + (r2 == 201 ? 1 : 0));
        assertTrue((r1 == 201 && r2 == 409) || (r1 == 409 && r2 == 201));
        assertEquals(1, shelfLife(batchKey).path("extensionCount").asInt());
        assertEquals(60, shelfLife(batchKey).path("extendedMinutes").asInt());
        Integer confirmed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_extension WHERE extension_key = 'EK-RACE' AND status = 'CONFIRMED'",
                Integer.class);
        assertEquals(1, confirmed);
    }

    @Test
    void concurrentAncestorRecallAndExtensionConfirm_commitOrderDecides() throws Exception {
        String root = createReleased("BK-RACE-RC-", 100000);
        List<String> children = splitTwoChildren(root);
        String child = children.get(0);
        releaseBatch(child, "insp-c");
        submitExtension(child, "rc-1", "EK-RACE-RC", "合格", 60, "CK-ES", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(confirmPost(child, "EK-RACE-RC", "cf-1", "QUALITY",
                        "{\"commandKey\":\"CK-EC\"}")),
                () -> callStatus(post("/api/batches/" + root + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RC\",\"reason\":\"根批召回\"}"))
        );

        int confirm = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall, "SPLIT 根批始终可召回");
        assertTrue(confirm == 201 || confirm == 422);
        // 无论提交顺序如何，召回祖先的后代最终都不可用，且不在到期清单
        assertFalse(availableKeys().contains(child));
        assertFalse(expiredKeys().contains(child));
        if (confirm == 201) {
            // 确认先提交：记录生效保留，但召回随后使其不恢复可用
            assertEquals("CONFIRMED", extension(child, "EK-RACE-RC").path("status").asText());
            assertEquals(1, shelfLife(child).path("extensionCount").asInt());
        } else {
            // 召回先提交：确认被拦截，记录停留 SUBMITTED，有效期不变
            assertEquals("SUBMITTED", extension(child, "EK-RACE-RC").path("status").asText());
            assertEquals(0, shelfLife(child).path("extensionCount").asInt());
        }
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, Integer shelfLifeMinutes, List<String> requiredTests) {
    }

    private String createBatch(String prefix, List<String> items, int shelfMinutes) throws Exception {
        String batchKey = prefix + unique();
        String json = objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(), batchKey,
                "PROD-1", "LOT-1", T0, shelfMinutes, items));
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
        return batchKey;
    }

    /**
     * 创建批次并走完 检验 PASS + 双角色批准（qa-1/ops-1，检验人 insp-1）进入 RELEASED。
     */
    private String createReleased(String prefix, int shelfMinutes) throws Exception {
        String batchKey = createBatch(prefix, List.of("t1"), shelfMinutes);
        releaseBatch(batchKey, "insp-1");
        return batchKey;
    }

    private void releaseBatch(String batchKey, String inspector) throws Exception {
        JsonNode node = history(batchKey);
        node.path("batch").path("requiredTests")
                .forEach(item -> submitTest(batchKey, item.asText(), "PASS", inspector));
        approve(batchKey, "qa-1", "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-AO-" + unique(), 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private void submitTest(String batchKey, String item, String result, String inspector) {
        try {
            mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new TestCmd(
                                    "CK-T-" + unique(), "TK-" + unique(), item, result, inspector))))
                    .andExpect(status().isCreated());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().is(expected));
    }

    private List<String> splitTwoChildren(String parent) throws Exception {
        String c1 = "BK-CH-" + unique();
        String c2 = "BK-CH-" + unique();
        String json = "{\"commandKey\":\"CK-S-" + unique() + "\",\"children\":["
                + "{\"batchKey\":\"" + c1 + "\",\"batchNo\":\"L1\"},"
                + "{\"batchKey\":\"" + c2 + "\",\"batchNo\":\"L2\"}]}";
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
        return List.of(c1, c2);
    }

    private void split(String parent, String commandKey, int expected) throws Exception {
        String json = "{\"commandKey\":\"" + commandKey + "\",\"children\":["
                + "{\"batchKey\":\"BK-SX1-" + unique() + "\",\"batchNo\":\"L1\"},"
                + "{\"batchKey\":\"BK-SX2-" + unique() + "\",\"batchNo\":\"L2\"}]}";
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is(expected));
    }

    private String body(String commandKey, String extensionKey, String conclusion, int minutes) {
        return "{\"commandKey\":\"" + commandKey + "\",\"extensionKey\":\"" + extensionKey
                + "\",\"recheckConclusion\":\"" + conclusion + "\",\"extendMinutes\":" + minutes + "}";
    }

    private MockHttpServletRequestBuilder extensionPost(String batchKey, String reviewer, String json) {
        return post("/api/batches/" + batchKey + "/extensions")
                .header("X-Actor-Id", reviewer)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private MockHttpServletRequestBuilder confirmPost(String batchKey, String extensionKey,
                                                      String confirmer, String role, String json) {
        return post("/api/batches/" + batchKey + "/extensions/" + extensionKey + "/confirm")
                .header("X-Actor-Id", confirmer).header("X-Approval-Role", role)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private String submitExtension(String batchKey, String reviewer, String extensionKey,
                                   String conclusion, int minutes, String commandKey, int expected)
            throws Exception {
        String key = extensionKey.endsWith("-") ? extensionKey + unique() : extensionKey;
        mockMvc.perform(extensionPost(batchKey, reviewer, body(commandKey, key, conclusion, minutes)))
                .andExpect(status().is(expected));
        return key;
    }

    private void confirmExtension(String batchKey, String extensionKey, String confirmer, String role,
                                  String commandKey, int expected) throws Exception {
        mockMvc.perform(confirmPost(batchKey, extensionKey, confirmer, role,
                        "{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private void submitAndConfirm(String batchKey, String extensionKey, int minutes) throws Exception {
        String key = submitExtension(batchKey, "rc-1", extensionKey, "合格", minutes,
                "CK-ES-" + unique(), 201);
        confirmExtension(batchKey, key, "cf-1", "QUALITY", "CK-EC-" + unique(), 201);
    }

    private JsonNode shelfLife(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/shelf-life"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode extensionHistory(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/extensions"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode extension(String batchKey, String extensionKey) throws Exception {
        JsonNode history = extensionHistory(batchKey);
        for (JsonNode node : history) {
            if (extensionKey.equals(node.path("extensionKey").asText())) {
                return node;
            }
        }
        throw new AssertionError("延期记录不存在: " + extensionKey);
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

    private List<String> expiredKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/expired"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        array.forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
    }

    private List<String> keysFrom(MvcResult ignored) {
        throw new UnsupportedOperationException();
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
}
