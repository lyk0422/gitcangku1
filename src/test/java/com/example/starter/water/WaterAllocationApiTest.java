package com.example.starter.water;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 灌区配水配额与限供 API 的端到端单测（H2 MySQL 兼容模式 + MockMvc）。
 * 覆盖主流程、失败分支、幂等重放与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WaterAllocationApiTest {

    private static final String T0 = "2026-01-01T00:00:00Z";
    private static final String T1 = "2026-01-01T06:00:00Z";
    private static final String T2 = "2026-01-01T12:00:00Z";
    private static final String T3 = "2026-01-01T18:00:00Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM water_command");
        jdbc.update("DELETE FROM water_allocation");
        jdbc.update("DELETE FROM water_restriction");
        jdbc.update("DELETE FROM water_window");
        jdbc.update("DELETE FROM water_channel");
    }

    // ---------- 窗口创建 ----------

    @Test
    void createWindowReturnsCreatedWindow() throws Exception {
        createWindow("w-1", "ch-1", T0, T1, "100.5", "ck-w-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.windowKey").value("w-1"))
                .andExpect(jsonPath("$.channelId").value("ch-1"))
                .andExpect(jsonPath("$.startUtc").value(T0))
                .andExpect(jsonPath("$.endUtc").value(T1))
                .andExpect(jsonPath("$.plannedVolume").value("100.500"));
    }

    @Test
    void createWindowRejectsInvalidParams() throws Exception {
        // 结束不晚于开始
        createWindow("w-bad-1", "ch-1", T1, T0, "10", "ck-bad-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        // 水量超过 3 位小数
        createWindow("w-bad-2", "ch-1", T0, T1, "10.0001", "ck-bad-2")
                .andExpect(status().isBadRequest());
        // 水量为零
        createWindow("w-bad-3", "ch-1", T0, T1, "0", "ck-bad-3")
                .andExpect(status().isBadRequest());
        // 水量非数字
        createWindow("w-bad-4", "ch-1", T0, T1, "abc", "ck-bad-4")
                .andExpect(status().isBadRequest());
        // 渠道为空
        createWindow("w-bad-5", " ", T0, T1, "10", "ck-bad-5")
                .andExpect(status().isBadRequest());
        // 时间格式非法
        createWindow("w-bad-6", "ch-1", "2026-01-01", T1, "10", "ck-bad-6")
                .andExpect(status().isBadRequest());
        // commandKey 缺失
        createWindow("w-bad-7", "ch-1", T0, T1, "10", null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWindowRejectsOverlapButAllowsAdjacentAndOtherChannel() throws Exception {
        createWindow("w-ov-1", "ch-ov", T0, T2, "10", "ck-ov-1").andExpect(status().isCreated());
        // 相邻（首尾相接）合法
        createWindow("w-ov-2", "ch-ov", T2, T3, "10", "ck-ov-2").andExpect(status().isCreated());
        // 与第一个窗口重叠 -> 409
        createWindow("w-ov-3", "ch-ov", T1, T3, "10", "ck-ov-3")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WINDOW_OVERLAP"));
        // 包含已有窗口 -> 409
        createWindow("w-ov-4", "ch-ov", T0, T3, "10", "ck-ov-4").andExpect(status().isConflict());
        // 其他渠道同时间段合法
        createWindow("w-ov-5", "ch-other", T0, T2, "10", "ck-ov-5").andExpect(status().isCreated());
    }

    @Test
    void createWindowRejectsDuplicateWindowKey() throws Exception {
        createWindow("w-dup", "ch-1", T0, T1, "10", "ck-dup-1").andExpect(status().isCreated());
        createWindow("w-dup", "ch-2", T0, T1, "10", "ck-dup-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_KEY"));
    }

    @Test
    void createWindowIdempotentReplayAndConflictOnChangedParams() throws Exception {
        MvcResult first = createWindow("w-idem", "ch-1", T0, T1, "10", "ck-idem-1")
                .andExpect(status().isCreated()).andReturn();
        String firstBody = first.getResponse().getContentAsString();
        // 同键同参重放：返回首次结果（相同窗口 ID）
        createWindow("w-idem", "ch-1", T0, T1, "10", "ck-idem-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(jsonOf(firstBody, "id").asLong()));
        // 同键改参 -> 409
        createWindow("w-idem-2", "ch-1", T0, T1, "10", "ck-idem-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMAND_CONFLICT"));
        // 重放不新增窗口
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM water_window", Integer.class)).isEqualTo(1);
    }

    // ---------- 申请提交 / 批准 / 取消 ----------

    @Test
    void submitApproveAndCapacityFlow() throws Exception {
        long windowId = windowIdOf(createWindow("w-a", "ch-a", T0, T1, "10", "ck-a-1").andReturn());

        submitAllocation("al-1", windowId, "user-1", "4", "actor-1", "ck-al-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.applicant").value("actor-1"));

        capacity(windowId)
                .andExpect(jsonPath("$.plannedVolume").value("10.000"))
                .andExpect(jsonPath("$.activeLimitVolume").doesNotExist())
                .andExpect(jsonPath("$.effectiveVolume").value("10.000"))
                .andExpect(jsonPath("$.approvedVolume").value("0.000"))
                .andExpect(jsonPath("$.availableVolume").value("10.000"));

        approve("al-1", "ck-ap-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        capacity(windowId)
                .andExpect(jsonPath("$.approvedVolume").value("4.000"))
                .andExpect(jsonPath("$.availableVolume").value("6.000"));
    }

    @Test
    void submitAllocationToMissingWindowReturns404() throws Exception {
        submitAllocation("al-404", 999999L, "user-1", "1", "actor-1", "ck-al-404")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void submitAllocationRejectsInvalidParams() throws Exception {
        long windowId = windowIdOf(createWindow("w-val", "ch-val", T0, T1, "10", "ck-val-1").andReturn());
        submitAllocation("al-v-1", windowId, "user-1", "1.0001", "actor-1", "ck-al-v1")
                .andExpect(status().isBadRequest());
        submitAllocation("al-v-2", windowId, "user-1", "-1", "actor-1", "ck-al-v2")
                .andExpect(status().isBadRequest());
        submitAllocation("al-v-3", windowId, " ", "1", "actor-1", "ck-al-v3")
                .andExpect(status().isBadRequest());
        // 缺少 X-Actor-Id 请求头
        mvc.perform(post("/api/water/allocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("commandKey", "ck-al-v4", "allocationKey", "al-v-4",
                                "windowId", windowId, "userId", "user-1", "volume", "1"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approveBeyondPlannedVolumeReturns422() throws Exception {
        long windowId = windowIdOf(createWindow("w-q", "ch-q", T0, T1, "10", "ck-q-1").andReturn());
        submitAllocation("al-q-1", windowId, "u1", "6", "a1", "ck-q-al1").andExpect(status().isCreated());
        submitAllocation("al-q-2", windowId, "u2", "6", "a2", "ck-q-al2").andExpect(status().isCreated());

        approve("al-q-1", "ck-q-ap1").andExpect(status().isOk());
        approve("al-q-2", "ck-q-ap2")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));

        capacity(windowId).andExpect(jsonPath("$.approvedVolume").value("6.000"));
    }

    @Test
    void approveUsesExactDecimalArithmetic() throws Exception {
        long windowId = windowIdOf(createWindow("w-dec", "ch-dec", T0, T1, "0.003", "ck-dec-1").andReturn());
        submitAllocation("al-dec-1", windowId, "u1", "0.001", "a1", "ck-dec-al1").andExpect(status().isCreated());
        submitAllocation("al-dec-2", windowId, "u2", "0.001", "a2", "ck-dec-al2").andExpect(status().isCreated());
        submitAllocation("al-dec-3", windowId, "u3", "0.001", "a3", "ck-dec-al3").andExpect(status().isCreated());
        submitAllocation("al-dec-4", windowId, "u4", "0.001", "a4", "ck-dec-al4").andExpect(status().isCreated());

        approve("al-dec-1", "ck-dec-ap1").andExpect(status().isOk());
        approve("al-dec-2", "ck-dec-ap2").andExpect(status().isOk());
        approve("al-dec-3", "ck-dec-ap3").andExpect(status().isOk());
        // 0.001 * 4 > 0.003，精确十进制下必须拒绝
        approve("al-dec-4", "ck-dec-ap4").andExpect(status().isUnprocessableEntity());
        capacity(windowId).andExpect(jsonPath("$.availableVolume").value("0.000"));
    }

    @Test
    void approveStateConflicts() throws Exception {
        long windowId = windowIdOf(createWindow("w-st", "ch-st", T0, T1, "10", "ck-st-1").andReturn());
        submitAllocation("al-st-1", windowId, "u1", "1", "actor-1", "ck-st-al1").andExpect(status().isCreated());

        approve("al-st-1", "ck-st-ap1").andExpect(status().isOk());
        // 重复批准（不同命令键）-> 409
        approve("al-st-1", "ck-st-ap2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        // 取消后不能再批准
        cancel("al-st-1", "actor-1", "ck-st-c1").andExpect(status().isOk());
        approve("al-st-1", "ck-st-ap3")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        // 批准不存在的申请 -> 404
        approve("al-missing", "ck-st-ap4")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void cancelOnlyByApplicantAndReleasesVolume() throws Exception {
        long windowId = windowIdOf(createWindow("w-c", "ch-c", T0, T1, "10", "ck-c-1").andReturn());
        submitAllocation("al-c-1", windowId, "u1", "4", "actor-1", "ck-c-al1").andExpect(status().isCreated());
        approve("al-c-1", "ck-c-ap1").andExpect(status().isOk());
        capacity(windowId).andExpect(jsonPath("$.availableVolume").value("6.000"));

        // 非申请人取消 -> 409 ACTOR_MISMATCH（与 404 可区分）
        cancel("al-c-1", "actor-2", "ck-c-x1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTOR_MISMATCH"));
        // 取消不存在的申请 -> 404
        cancel("al-missing", "actor-1", "ck-c-x2").andExpect(status().isNotFound());

        // 申请人取消已批准申请 -> 立即释放水量
        cancel("al-c-1", "actor-1", "ck-c-x3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        capacity(windowId).andExpect(jsonPath("$.availableVolume").value("10.000"));

        // 重复取消 -> 409
        cancel("al-c-1", "actor-1", "ck-c-x4").andExpect(status().isConflict());
    }

    @Test
    void cancelRequestedAllocationIsAllowed() throws Exception {
        long windowId = windowIdOf(createWindow("w-cr", "ch-cr", T0, T1, "10", "ck-cr-1").andReturn());
        submitAllocation("al-cr-1", windowId, "u1", "4", "actor-1", "ck-cr-al1").andExpect(status().isCreated());
        cancel("al-cr-1", "actor-1", "ck-cr-c1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // ---------- 限供 ----------

    @Test
    void restrictionReducesEffectiveVolumeAndCancelRestores() throws Exception {
        long windowId = windowIdOf(createWindow("w-r", "ch-r", T0, T1, "100", "ck-r-1").andReturn());

        createRestriction(windowId, "50", "ck-r-r1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.limitVolume").value("50.000"));

        capacity(windowId)
                .andExpect(jsonPath("$.activeLimitVolume").value("50.000"))
                .andExpect(jsonPath("$.effectiveVolume").value("50.000"));

        // 已有生效限供时再次创建 -> 409
        createRestriction(windowId, "60", "ck-r-r2").andExpect(status().isConflict());

        // 取消限供 -> 恢复计划水量
        cancelRestriction(windowId, "ck-r-c1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        capacity(windowId)
                .andExpect(jsonPath("$.activeLimitVolume").doesNotExist())
                .andExpect(jsonPath("$.effectiveVolume").value("100.000"));
        // 再次取消 -> 409
        cancelRestriction(windowId, "ck-r-c2").andExpect(status().isConflict());
    }

    @Test
    void restrictionValidationAndQuotaGuards() throws Exception {
        long windowId = windowIdOf(createWindow("w-rv", "ch-rv", T0, T1, "100", "ck-rv-1").andReturn());
        // 限供超过计划水量 -> 400
        createRestriction(windowId, "101", "ck-rv-r1").andExpect(status().isBadRequest());
        // 限供为零 -> 400
        createRestriction(windowId, "0", "ck-rv-r2").andExpect(status().isBadRequest());

        submitAllocation("al-rv-1", windowId, "u1", "60", "a1", "ck-rv-al1").andExpect(status().isCreated());
        approve("al-rv-1", "ck-rv-ap1").andExpect(status().isOk());

        // 已批准 60，拟定限供 50 -> 422
        createRestriction(windowId, "50", "ck-rv-r3")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));
        // 限供等于已批准总量 -> 允许
        createRestriction(windowId, "60", "ck-rv-r4").andExpect(status().isCreated());

        // 限供 60 生效后，再批准任何正水量都会超限 -> 422
        submitAllocation("al-rv-2", windowId, "u2", "0.001", "a2", "ck-rv-al2").andExpect(status().isCreated());
        approve("al-rv-2", "ck-rv-ap2").andExpect(status().isUnprocessableEntity());

        // 窗口不存在 -> 404
        createRestriction(999999L, "10", "ck-rv-r5").andExpect(status().isNotFound());
        cancelRestriction(999999L, "ck-rv-c9").andExpect(status().isNotFound());
    }

    // ---------- 查询 ----------

    @Test
    void historyReturnsWindowRestrictionsAndAllocations() throws Exception {
        long windowId = windowIdOf(createWindow("w-h", "ch-h", T0, T1, "100", "ck-h-1").andReturn());
        submitAllocation("al-h-1", windowId, "u1", "10", "a1", "ck-h-al1").andExpect(status().isCreated());
        approve("al-h-1", "ck-h-ap1").andExpect(status().isOk());
        submitAllocation("al-h-2", windowId, "u2", "5", "a2", "ck-h-al2").andExpect(status().isCreated());
        cancel("al-h-2", "a2", "ck-h-c1").andExpect(status().isOk());
        createRestriction(windowId, "80", "ck-h-r1").andExpect(status().isCreated());
        cancelRestriction(windowId, "ck-h-rc1").andExpect(status().isOk());

        mvc.perform(get("/api/water/windows/{id}/history", windowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window.windowKey").value("w-h"))
                .andExpect(jsonPath("$.restrictions.length()").value(1))
                .andExpect(jsonPath("$.restrictions[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.restrictions[0].cancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.allocations.length()").value(2))
                .andExpect(jsonPath("$.allocations[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.allocations[1].status").value("CANCELLED"));

        mvc.perform(get("/api/water/windows/{id}/history", 999999L)).andExpect(status().isNotFound());
        mvc.perform(get("/api/water/windows/{id}/capacity", 999999L)).andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void failedCommandDoesNotConsumeCommandKey() throws Exception {
        // 首次执行失败（窗口不存在 -> 404），命令键不应被占用
        submitAllocation("al-retry", 888888L, "u1", "1", "a1", "ck-retry").andExpect(status().isNotFound());
        long windowId = windowIdOf(createWindow("w-retry", "ch-retry", T0, T1, "10", "ck-retry-w").andReturn());
        // 修正参数后用同一命令键重试：应正常执行而非 409
        submitAllocation("al-retry", windowId, "u1", "1", "a1", "ck-retry").andExpect(status().isCreated());
        // 成功后再改参重放同键 -> 409
        submitAllocation("al-retry-2", windowId, "u1", "1", "a1", "ck-retry")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMAND_CONFLICT"));
    }

    @Test
    void approveAndCancelAreIdempotentPerCommandKey() throws Exception {
        long windowId = windowIdOf(createWindow("w-i2", "ch-i2", T0, T1, "10", "ck-i2-w").andReturn());
        submitAllocation("al-i2", windowId, "u1", "3", "actor-1", "ck-i2-al").andExpect(status().isCreated());

        approve("al-i2", "ck-i2-ap").andExpect(status().isOk());
        // 同键重放批准 -> 返回首次结果而非 409
        approve("al-i2", "ck-i2-ap")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM water_allocation WHERE status = 'APPROVED'", Integer.class)).isEqualTo(1);

        cancel("al-i2", "actor-1", "ck-i2-c").andExpect(status().isOk());
        cancel("al-i2", "actor-1", "ck-i2-c")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // 同键但换操作人 -> 409
        cancel("al-i2", "actor-2", "ck-i2-c").andExpect(status().isConflict());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentApprovalsNeverExceedEffectiveVolume() throws Exception {
        long windowId = windowIdOf(createWindow("w-cc", "ch-cc", T0, T1, "5", "ck-cc-w").andReturn());
        int total = 10;
        for (int i = 0; i < total; i++) {
            submitAllocation("al-cc-" + i, windowId, "u" + i, "1", "a" + i, "ck-cc-al" + i)
                    .andExpect(status().isCreated());
        }

        List<Integer> statuses = runConcurrently(total, i -> approve("al-cc-" + i, "ck-cc-ap" + i)
                .andReturn().getResponse().getStatus());

        long ok = statuses.stream().filter(s -> s == 200).count();
        long quota = statuses.stream().filter(s -> s == 422).count();
        assertThat(ok).isEqualTo(5);
        assertThat(quota).isEqualTo(5);
        capacity(windowId)
                .andExpect(jsonPath("$.approvedVolume").value("5.000"))
                .andExpect(jsonPath("$.availableVolume").value("0.000"));
    }

    @Test
    void concurrentRestrictionCreationKeepsAtMostOneActive() throws Exception {
        long windowId = windowIdOf(createWindow("w-cr2", "ch-cr2", T0, T1, "100", "ck-cr2-w").andReturn());

        List<Integer> statuses = runConcurrently(2,
                i -> createRestriction(windowId, i == 0 ? "50" : "60", "ck-cr2-r" + i)
                        .andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM water_restriction WHERE status = 'ACTIVE'", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameCommandKeyReplayYieldsSingleEffect() throws Exception {
        long windowId = windowIdOf(createWindow("w-ck", "ch-ck", T0, T1, "10", "ck-ck-w").andReturn());
        submitAllocation("al-ck", windowId, "u1", "3", "a1", "ck-ck-al").andExpect(status().isCreated());

        // 多线程使用同一 commandKey 批准同一申请：全部成功且只批准一次
        List<Integer> statuses = runConcurrently(4, i -> approve("al-ck", "ck-ck-ap")
                .andReturn().getResponse().getStatus());

        assertThat(statuses).containsOnly(200);
        capacity(windowId).andExpect(jsonPath("$.approvedVolume").value("3.000"));
    }

    // ---------- 测试辅助 ----------

    private ResultActions createWindow(String windowKey, String channelId, String start, String end,
            String planned, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("windowKey", windowKey);
        body.put("channelId", channelId);
        body.put("startUtc", start);
        body.put("endUtc", end);
        body.put("plannedVolume", planned);
        return mvc.perform(post("/api/water/windows")
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions submitAllocation(String allocationKey, long windowId, String userId,
            String volume, String actor, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("allocationKey", allocationKey);
        body.put("windowId", windowId);
        body.put("userId", userId);
        body.put("volume", volume);
        return mvc.perform(post("/api/water/allocations")
                .header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions approve(String allocationKey, String commandKey) throws Exception {
        return mvc.perform(post("/api/water/allocations/{key}/approve", allocationKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("commandKey", commandKey))));
    }

    private ResultActions cancel(String allocationKey, String actor, String commandKey) throws Exception {
        return mvc.perform(post("/api/water/allocations/{key}/cancel", allocationKey)
                .header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("commandKey", commandKey))));
    }

    private ResultActions createRestriction(long windowId, String limit, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("limitVolume", limit);
        return mvc.perform(post("/api/water/windows/{id}/restrictions", windowId)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions cancelRestriction(long windowId, String commandKey) throws Exception {
        return mvc.perform(post("/api/water/windows/{id}/restrictions/cancel", windowId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("commandKey", commandKey))));
    }

    private ResultActions capacity(long windowId) throws Exception {
        return mvc.perform(get("/api/water/windows/{id}/capacity", windowId))
                .andExpect(status().isOk());
    }

    /** 并发执行 n 个操作并收集结果；所有线程在同一栅栏后同时出发。 */
    private List<Integer> runConcurrently(int n, ThrowingIntAction action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return action.run(index);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingIntAction {
        Integer run(int index) throws Exception;
    }

    private long windowIdOf(MvcResult result) throws Exception {
        return jsonOf(result.getResponse().getContentAsString(), "id").asLong();
    }

    private JsonNode jsonOf(String body, String field) throws Exception {
        return objectMapper.readTree(body).get(field);
    }

    private String json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }
}
