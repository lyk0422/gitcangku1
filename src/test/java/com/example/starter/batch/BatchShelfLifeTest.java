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
 * 批次有效期与复检延期测试：到期可用性、延期上限、双人复检、召回交互、
 * commandKey/extensionKey 幂等与并发裁决。时钟为可控 MutableClock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchShelfLifeTest {

    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch_extension");
        jdbc.update("DELETE FROM batch");
        clock.setInstant(T0);
    }

    // ---------- 到期可用性 ----------

    @Test
    void expiredBatch_excludedFromAvailable_andApproveSplitBlocked_statusUnchanged() throws Exception {
        // 批次一：走到 RELEASE_REVIEW 后到期
        String pending = "BK-EXP-P-" + unique();
        createBatch(pending, 60, 201);
        submitTestPass(pending, "t1", "insp-1");
        approve(pending, "qa-1", "QUALITY", "CK-A1-" + unique(), 201);
        assertEquals("RELEASE_REVIEW", currentStatus(pending));

        // 批次二：走到 RELEASED 后到期
        String released = "BK-EXP-R-" + unique();
        createBatch(released, 60, 201);
        releaseBatch(released, "insp-2");
        assertEquals("RELEASED", currentStatus(released));

        // 到期前：两批都在可用列表，有效期查询未到期
        assertTrue(availableKeys().contains(pending));
        assertTrue(availableKeys().contains(released));
        JsonNode shelfLife = shelfLife(pending);
        assertEquals(60, shelfLife.path("shelfLifeMinutes").asInt());
        assertEquals("2026-09-25T01:00:00Z", shelfLife.path("expiresAt").asText());
        assertEquals(60, shelfLife.path("remainingMinutes").asLong());
        assertFalse(shelfLife.path("expired").asBoolean());

        // 时钟推进到有效期之后：立即到期
        clock.advanceSeconds(61 * 60);
        assertFalse(availableKeys().contains(pending), "到期批次立即从可用查询排除");
        assertFalse(availableKeys().contains(released));
        JsonNode expiredList = expiredList();
        List<String> expiredKeys = new ArrayList<>();
        expiredList.forEach(n -> expiredKeys.add(n.path("batchKey").asText()));
        assertTrue(expiredKeys.contains(pending));
        assertTrue(expiredKeys.contains(released));
        JsonNode expiredShelfLife = shelfLife(pending);
        assertTrue(expiredShelfLife.path("expired").asBoolean());
        assertTrue(expiredShelfLife.path("remainingMinutes").asLong() < 0);

        // 到期禁止批准与拆分 → 422；批次状态不被改写
        approve(pending, "ops-1", "OPERATIONS", "CK-A2-" + unique(), 422);
        split(released, "CK-S-" + unique(), 422);
        assertEquals("RELEASE_REVIEW", currentStatus(pending));
        assertEquals("RELEASED", currentStatus(released));
        // 到期不自动召回：无召回记录
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM recall", Integer.class));
    }

    @Test
    void splitChildren_inheritShelfLifeMinutes_andRecomputeExpiryFromOwnProducedAt()
            throws Exception {
        String parent = "BK-SLH-P-" + unique();
        createBatch(parent, 120, 201);
        releaseBatch(parent, "insp-1");
        String child = "BK-SLH-C-" + unique();
        split(parent, "CK-S-" + unique(), child, "BK-SLH-C2-" + unique(), 201);

        // 子批继承父批保质分钟，按自身生产时间（继承父批生产时间）重新计算有效期
        JsonNode childShelfLife = shelfLife(child);
        assertEquals(120, childShelfLife.path("shelfLifeMinutes").asInt());
        assertEquals("2026-09-25T02:00:00Z", childShelfLife.path("expiresAt").asText());
        assertFalse(childShelfLife.path("expired").asBoolean());

        // 父批保质分钟不可改写：父批初始有效期保持生产时间+保质分钟
        JsonNode parentShelfLife = shelfLife(parent);
        assertEquals(120, parentShelfLife.path("shelfLifeMinutes").asInt());
        assertEquals("2026-09-25T02:00:00Z", parentShelfLife.path("expiresAt").asText());

        // 到期后子批同样从可用查询排除
        clock.advanceSeconds(121 * 60);
        assertFalse(availableKeys().contains(child));
    }

    // ---------- 延期主流程 ----------

    @Test
    void extensionSubmitConfirm_shiftsExpiryAndRestoresAvailability() throws Exception {
        String batchKey = "BK-EXT-" + unique();
        createBatch(batchKey, 60, 201);
        releaseBatch(batchKey, "insp-1");

        // 到期后不可用
        clock.advanceSeconds(61 * 60);
        assertFalse(availableKeys().contains(batchKey));

        // 提交延期：PENDING_CONFIRM，尚未生效，仍不可用
        MvcResult submit = submitExtension(batchKey, "rt-1", "EK-1", "PASS", 30,
                "CK-ES-" + unique(), 201);
        JsonNode submitBody = objectMapper.readTree(submit.getResponse().getContentAsString());
        assertEquals("PENDING_CONFIRM", submitBody.path("status").asText());
        assertEquals("rt-1", submitBody.path("retester").asText());
        assertFalse(availableKeys().contains(batchKey), "延期未确认前不恢复可用");
        assertTrue(shelfLife(batchKey).path("expired").asBoolean());

        // 另一名不同于复检人的批准角色确认后生效：有效期整体顺延 30 分钟，恢复可用
        MvcResult confirm = confirmExtension(batchKey, "EK-1", "cf-1", "QUALITY",
                "CK-EC-" + unique(), 200);
        JsonNode confirmBody = objectMapper.readTree(confirm.getResponse().getContentAsString());
        assertEquals("EFFECTIVE", confirmBody.path("status").asText());
        assertEquals("cf-1", confirmBody.path("confirmedBy").asText());
        assertEquals("QUALITY", confirmBody.path("confirmedRole").asText());
        assertEquals("2026-09-25T01:30:00Z", confirmBody.path("newExpiresAt").asText());

        JsonNode shelfLife = shelfLife(batchKey);
        assertEquals("2026-09-25T01:30:00Z", shelfLife.path("expiresAt").asText());
        assertFalse(shelfLife.path("expired").asBoolean());
        assertEquals(29, shelfLife.path("remainingMinutes").asLong());
        assertEquals(30, shelfLife.path("cumulativeExtendedMinutes").asInt());
        assertEquals(1, shelfLife.path("effectiveExtensions").asInt());
        assertTrue(availableKeys().contains(batchKey), "延期生效后恢复可用");

        // 批次状态与既有检验、批准记录不变
        assertEquals("RELEASED", currentStatus(batchKey));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE batch_key = ?", Integer.class, batchKey));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_result WHERE batch_key = ?", Integer.class, batchKey));

        // 延期历史：一条已生效记录，含确认人与生效后有效期
        JsonNode extensions = extensions(batchKey);
        assertEquals(1, extensions.size());
        assertEquals("EFFECTIVE", extensions.get(0).path("status").asText());
        assertEquals("cf-1", extensions.get(0).path("confirmedBy").asText());
        assertEquals("2026-09-25T01:30:00Z", extensions.get(0).path("newExpiresAt").asText());
    }

    // ---------- 延期上限 ----------

    @Test
    void extensionLimits_cumulativeMinutesAndMaxThree() throws Exception {
        // 累计顺延分钟不得超过原保质分钟两倍：保质 100 分钟，上限 200
        String batchKey = "BK-LIM-M-" + unique();
        createBatch(batchKey, 100, 201);
        submitTestPass(batchKey, "t1", "insp-1");
        submitAndConfirm(batchKey, "EK-1", 100, "rt-1");
        submitAndConfirm(batchKey, "EK-2", 100, "rt-2");
        assertEquals(200, shelfLife(batchKey).path("cumulativeExtendedMinutes").asInt());
        // 再顺延 1 分钟即超限 → 422，有效期不变（01:40 初始 + 两次各 100 分钟 = 05:00）
        submitExtension(batchKey, "rt-3", "EK-3", "PASS", 1, "CK-ES-" + unique(), 422);
        assertEquals("2026-09-25T05:00:00Z", shelfLife(batchKey).path("expiresAt").asText());

        // 最多三次：保质 10000 分钟，三次各 100 分钟生效后第四次 → 422
        String batchKey2 = "BK-LIM-C-" + unique();
        createBatch(batchKey2, 10_000, 201);
        submitTestPass(batchKey2, "t1", "insp-2");
        submitAndConfirm(batchKey2, "EK-1", 100, "rt-1");
        submitAndConfirm(batchKey2, "EK-2", 100, "rt-2");
        submitAndConfirm(batchKey2, "EK-3", 100, "rt-3");
        submitExtension(batchKey2, "rt-4", "EK-4", "PASS", 100, "CK-ES-" + unique(), 422);
        assertEquals(3, shelfLife(batchKey2).path("effectiveExtensions").asInt());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_extension WHERE batch_key = ? AND status = 'EFFECTIVE'",
                Integer.class, batchKey2));
    }

    // ---------- 双人复检与结论 ----------

    @Test
    void extensionPersonnelAndConclusionRules() throws Exception {
        String batchKey = "BK-PER-" + unique();
        createBatch(batchKey, 60, 201);
        submitTestPass(batchKey, "t1", "insp-1");
        approve(batchKey, "qa-1", "QUALITY", "CK-A1-" + unique(), 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-A2-" + unique(), 201);

        // 复检人不得为任一原批准人
        submitExtension(batchKey, "qa-1", "EK-A", "PASS", 10, "CK-ES-" + unique(), 422);
        submitExtension(batchKey, "ops-1", "EK-B", "PASS", 10, "CK-ES-" + unique(), 422);
        // 复检结论不合格 → 422
        submitExtension(batchKey, "rt-1", "EK-C", "FAIL", 10, "CK-ES-" + unique(), 422);
        // 正常提交后，确认人不得与复检人相同
        submitExtension(batchKey, "rt-1", "EK-1", "PASS", 10, "CK-ES-" + unique(), 201);
        confirmExtension(batchKey, "EK-1", "rt-1", "QUALITY", "CK-EC-" + unique(), 422);
        // 全部校验失败均不改变有效期与记录
        assertEquals("2026-09-25T01:00:00Z", shelfLife(batchKey).path("expiresAt").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_extension WHERE batch_key = ?", Integer.class,
                batchKey));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_extension WHERE batch_key = ? AND status = 'EFFECTIVE'",
                Integer.class, batchKey));

        // 必做检验未全部通过的批次不能提交延期
        String untested = "BK-PER-U-" + unique();
        createBatch(untested, 60, 201);
        submitExtension(untested, "rt-9", "EK-1", "PASS", 10, "CK-ES-" + unique(), 422);
    }

    @Test
    void extensionInvalidParams_return400() throws Exception {
        String batchKey = "BK-E400-" + unique();
        createBatch(batchKey, 60, 201);
        submitTestPass(batchKey, "t1", "insp-1");

        // 顺延分钟越界：0 与 43201
        submitExtension(batchKey, "rt-1", "EK-1", "PASS", 0, "CK-ES-" + unique(), 400);
        submitExtension(batchKey, "rt-1", "EK-2", "PASS", 43201, "CK-ES-" + unique(), 400);
        // 复检结论为空
        mockMvc.perform(post("/api/batches/" + batchKey + "/extensions")
                        .header("X-Actor-Id", "rt-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"c\",\"extensionKey\":\"EK-3\","
                                + "\"retestConclusion\":\"\",\"extensionMinutes\":10}"))
                .andExpect(status().isBadRequest());
        // 缺 X-Actor-Id
        mockMvc.perform(post("/api/batches/" + batchKey + "/extensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"c\",\"extensionKey\":\"EK-4\","
                                + "\"retestConclusion\":\"PASS\",\"extensionMinutes\":10}"))
                .andExpect(status().isBadRequest());
        // 确认缺角色头 / 非法角色
        submitExtension(batchKey, "rt-1", "EK-9", "PASS", 10, "CK-ES-" + unique(), 201);
        mockMvc.perform(post("/api/batches/" + batchKey + "/extensions/EK-9/confirm")
                        .header("X-Actor-Id", "cf-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"c\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/batches/" + batchKey + "/extensions/EK-9/confirm")
                        .header("X-Actor-Id", "cf-1").header("X-Approval-Role", "BOSS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"c\"}"))
                .andExpect(status().isBadRequest());
        // 未知批次 / 未知延期 → 404
        submitExtension("NO-SUCH-BATCH", "rt-1", "EK-1", "PASS", 10, "CK-ES-" + unique(), 404);
        confirmExtension(batchKey, "EK-NONE", "cf-1", "QUALITY", "CK-EC-" + unique(), 404);
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/shelf-life"))
                .andExpect(status().isNotFound());
    }

    // ---------- 召回交互 ----------

    @Test
    void ancestorRecall_blocksDescendantExtension_keepsEffectiveRecords() throws Exception {
        String root = "BK-RCX-R-" + unique();
        createBatch(root, 60, 201);
        releaseBatch(root, "insp-1");
        String child = "BK-RCX-C-" + unique();
        split(root, "CK-S-" + unique(), child, "BK-RCX-C2-" + unique(), 201);
        submitTestPass(child, "t1", "insp-2");

        // 召回前：子批一条延期已生效，一条待确认
        submitAndConfirm(child, "EK-1", 30, "rt-1");
        submitExtension(child, "rt-2", "EK-2", "PASS", 30, "CK-ES-" + unique(), 201);
        assertEquals("2026-09-25T01:30:00Z", shelfLife(child).path("expiresAt").asText());

        // 祖先召回提交后：后代延期一律 422
        recall(root, "u", "上游污染", "CK-R-" + unique(), 201);
        submitExtension(child, "rt-3", "EK-3", "PASS", 10, "CK-ES-" + unique(), 422);
        confirmExtension(child, "EK-2", "cf-2", "QUALITY", "CK-EC-" + unique(), 422);

        // 已生效延期记录保留，但不恢复可用；有效期保持已顺延值不再变化
        JsonNode extensions = extensions(child);
        assertEquals(2, extensions.size());
        assertEquals("EFFECTIVE", extensions.get(0).path("status").asText());
        assertEquals("PENDING_CONFIRM", extensions.get(1).path("status").asText());
        assertEquals("2026-09-25T01:30:00Z", shelfLife(child).path("expiresAt").asText());
        assertFalse(availableKeys().contains(child));
        // 后代自身状态不被改写
        assertEquals("PENDING_RELEASE", currentStatus(child));
    }

    // ---------- 幂等 ----------

    @Test
    void extensionCommandKey_replayConflictAndFailureDoesNotOccupyKey() throws Exception {
        String batchKey = "BK-EIDEM-" + unique();
        createBatch(batchKey, 60, 201);
        submitTestPass(batchKey, "t1", "insp-1");

        // 失败不占键：结论不合格 422 后，同 commandKey 修正参数可成功
        String body = extensionBody("CK-E1", "EK-1", "FAIL", 30);
        mockMvc.perform(post("/api/batches/" + batchKey + "/extensions")
                        .header("X-Actor-Id", "rt-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
        String fixed = extensionBody("CK-E1", "EK-1", "PASS", 30);
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey + "/extensions")
                        .header("X-Actor-Id", "rt-1")
                        .contentType(MediaType.APPLICATION_JSON).content(fixed))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放首次结果，不新增记录
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey + "/extensions")
                        .header("X-Actor-Id", "rt-1")
                        .contentType(MediaType.APPLICATION_JSON).content(fixed))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_extension WHERE batch_key = ?", Integer.class,
                batchKey));
        // 同 commandKey 改参 → 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/extensions")
                        .header("X-Actor-Id", "rt-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(extensionBody("CK-E1", "EK-1", "PASS", 31)))
                .andExpect(status().isConflict());
        // 同 extensionKey 换 commandKey → 409
        submitExtension(batchKey, "rt-2", "EK-1", "PASS", 30, "CK-ES-" + unique(), 409);

        // 确认幂等：同键同参重放首次结果，延期只生效一次
        MvcResult confirmFirst = confirmExtension(batchKey, "EK-1", "cf-1", "QUALITY",
                "CK-C1", 200);
        MvcResult confirmReplay = confirmExtension(batchKey, "EK-1", "cf-1", "QUALITY",
                "CK-C1", 200);
        assertEquals(confirmFirst.getResponse().getContentAsString(),
                confirmReplay.getResponse().getContentAsString());
        assertEquals("2026-09-25T01:30:00Z", shelfLife(batchKey).path("expiresAt").asText());
        assertEquals(1, shelfLife(batchKey).path("effectiveExtensions").asInt());
        // 同确认 commandKey 改参（换确认人）→ 409
        confirmExtension(batchKey, "EK-1", "cf-2", "QUALITY", "CK-C1", 409);
        // 已生效延期换 commandKey 重复确认 → 409
        confirmExtension(batchKey, "EK-1", "cf-2", "OPERATIONS", "CK-EC-" + unique(), 409);
    }

    // ---------- 并发 ----------

    @Test
    void concurrentConfirms_sameExtensionTakesEffectOnce() throws Exception {
        String batchKey = "BK-ECC-" + unique();
        createBatch(batchKey, 60, 201);
        submitTestPass(batchKey, "t1", "insp-1");
        submitExtension(batchKey, "rt-1", "EK-1", "PASS", 30, "CK-ES-" + unique(), 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/extensions/EK-1/confirm")
                        .header("X-Actor-Id", "cf-a").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-CC1\"}")),
                () -> callStatus(post("/api/batches/" + batchKey + "/extensions/EK-1/confirm")
                        .header("X-Actor-Id", "cf-b").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-CC2\"}"))
        );
        int first = results.get(0).get(30, TimeUnit.SECONDS);
        int second = results.get(1).get(30, TimeUnit.SECONDS);
        assertTrue((first == 200 && second == 409) || (first == 409 && second == 200),
                "同一 extensionKey 最多生效一次: " + first + "/" + second);
        // 有效期只顺延一次
        assertEquals("2026-09-25T01:30:00Z", shelfLife(batchKey).path("expiresAt").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_extension WHERE batch_key = ? AND status = 'EFFECTIVE'",
                Integer.class, batchKey));
    }

    @Test
    void concurrentExtensionConfirmAndAncestorRecall_commitOrderDecides() throws Exception {
        String root = "BK-ECR-R-" + unique();
        createBatch(root, 60, 201);
        releaseBatch(root, "insp-1");
        String child = "BK-ECR-C-" + unique();
        split(root, "CK-S-" + unique(), child, "BK-ECR-C2-" + unique(), 201);
        submitTestPass(child, "t1", "insp-2");
        submitExtension(child, "rt-1", "EK-1", "PASS", 30, "CK-ES-" + unique(), 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + child + "/extensions/EK-1/confirm")
                        .header("X-Actor-Id", "cf-1").header("X-Approval-Role", "QUALITY")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commandKey\":\"CK-EC1\"}")),
                () -> callStatus(post("/api/batches/" + root + "/recall")
                        .header("X-Actor-Id", "u")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RC1\",\"reason\":\"根批召回\"}"))
        );
        int confirm = results.get(0).get(30, TimeUnit.SECONDS);
        int recall = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, recall, "SPLIT 根批始终可召回");
        JsonNode extensions = extensions(child);
        if (confirm == 200) {
            // 确认先提交：延期已生效，记录保留；但祖先召回使后代不恢复可用
            assertEquals("EFFECTIVE", extensions.get(0).path("status").asText());
            assertEquals("2026-09-25T01:30:00Z", shelfLife(child).path("expiresAt").asText());
            assertFalse(availableKeys().contains(child));
        } else {
            // 召回先提交：确认被拦截 422，延期停留待确认，有效期不变
            assertEquals(422, confirm);
            assertEquals("PENDING_CONFIRM", extensions.get(0).path("status").asText());
            assertEquals("2026-09-25T01:00:00Z", shelfLife(child).path("expiresAt").asText());
        }
        // 无论顺序，生效记录数不超过 1 且后代均不可用
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_extension WHERE batch_key = ? AND status = 'EFFECTIVE'",
                Integer.class, child) <= 1);
        assertFalse(availableKeys().contains(child));
    }

    // ---------- 查询 ----------

    @Test
    void expiredList_stableCreationOrder_andShelfLifeUnknownBatch404() throws Exception {
        String b1 = "BK-EL-1-" + unique();
        String b2 = "BK-EL-2-" + unique();
        String b3 = "BK-EL-3-" + unique();
        createBatch(b1, 30, 201);
        createBatch(b2, 60, 201);
        createBatch(b3, 600, 201);

        clock.advanceSeconds(45 * 60);
        JsonNode first = expiredList();
        assertEquals(1, first.size());
        assertEquals(b1, first.get(0).path("batchKey").asText());
        assertEquals("QUARANTINED", first.get(0).path("status").asText(),
                "到期不改写批次状态");

        clock.advanceSeconds(20 * 60);
        JsonNode second = expiredList();
        assertEquals(2, second.size());
        assertEquals(b1, second.get(0).path("batchKey").asText());
        assertEquals(b2, second.get(1).path("batchKey").asText());

        mockMvc.perform(get("/api/batches/NO-SUCH/shelf-life"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH/extensions"))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, int shelfLifeMinutes, List<String> requiredTests) {
    }

    private void createBatch(String batchKey, int shelfLifeMinutes, int expected) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(),
                                batchKey, "PROD-1", "LOT-1", T0, shelfLifeMinutes, List.of("t1")))))
                .andExpect(status().is(expected));
    }

    private void submitTestPass(String batchKey, String item, String inspector) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TestCmd("CK-T-" + unique(), "TK-" + unique(), item, "PASS",
                                        inspector))))
                .andExpect(status().isCreated());
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private void releaseBatch(String batchKey, String inspector) throws Exception {
        submitTestPass(batchKey, "t1", inspector);
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-AQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-AO-" + unique(), 201);
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

    private void split(String parentKey, String commandKey, int expected) throws Exception {
        split(parentKey, commandKey, "BK-CH1-" + unique(), "BK-CH2-" + unique(), expected);
    }

    private void split(String parentKey, String commandKey, String child1, String child2,
                       int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + parentKey + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"children\":["
                                + "{\"batchKey\":\"" + child1 + "\",\"batchNo\":\"L1\"},"
                                + "{\"batchKey\":\"" + child2 + "\",\"batchNo\":\"L2\"}]}"))
                .andExpect(status().is(expected));
    }

    private String extensionBody(String commandKey, String extensionKey, String conclusion,
                                 int minutes) {
        return "{\"commandKey\":\"" + commandKey + "\",\"extensionKey\":\"" + extensionKey
                + "\",\"retestConclusion\":\"" + conclusion + "\",\"extensionMinutes\":" + minutes
                + "}";
    }

    private MvcResult submitExtension(String batchKey, String retester, String extensionKey,
                                      String conclusion, int minutes, String commandKey,
                                      int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/extensions")
                        .header("X-Actor-Id", retester)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(extensionBody(commandKey, extensionKey, conclusion, minutes)))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult confirmExtension(String batchKey, String extensionKey, String confirmer,
                                       String role, String commandKey, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/extensions/" + extensionKey
                        + "/confirm")
                        .header("X-Actor-Id", confirmer).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    /**
     * 提交并确认一次延期（复检人 rt 与确认人 cf 分离）。
     */
    private void submitAndConfirm(String batchKey, String extensionKey, int minutes, String retester)
            throws Exception {
        submitExtension(batchKey, retester, extensionKey, "PASS", minutes, "CK-ES-" + unique(),
                201);
        confirmExtension(batchKey, extensionKey, "cf-" + unique(), "QUALITY",
                "CK-EC-" + unique(), 200);
    }

    private JsonNode shelfLife(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/shelf-life"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode extensions(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/extensions"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode expiredList() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/expired"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
