package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 容量交换单 API 主流程、闭环交换、外部冲突、整体回滚与幂等边界的端到端测试（H2 内存库）。
 * 各用例使用唯一 scheduleKey/swapKey/requestKey/区段 ID，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanSwapApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 闭环交换主流程 ----------

    @Test
    void closedLoopSwapOfThreePlansActivatesAtomically() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        String planC = key("SCH");
        // A [08,09) B [09,10) C [10,11)，闭环：A→B 的段、B→C 的段、C→A 的段
        createPublished(planA, "G1", section, iso(8), iso(9));
        createPublished(planB, "G2", section, iso(9), iso(10));
        createPublished(planC, "G3", section, iso(10), iso(11));

        String swapKey = key("SWAP");
        // 交换项故意乱序提交，且段列表乱序，验证顺序不影响语义
        String body = swapBody(swapKey,
                item(planC, 1, seg(section, iso(10), iso(11)), seg(section, iso(8), iso(9))),
                item(planA, 1, seg(section, iso(8), iso(9)), seg(section, iso(9), iso(10))),
                item(planB, 1, seg(section, iso(9), iso(10)), seg(section, iso(10), iso(11))));

        MvcResult preview = mvc.perform(post("/api/v1/capacity-swaps")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode previewBody = read(preview);
        assertThat(previewBody.get("status").asText()).isEqualTo("PREVIEW");
        assertThat(previewBody.get("conflicts")).isEmpty();
        // 预览返回全部计划版本与交换前占用，且交换项按计划键稳定升序（与提交顺序无关）
        JsonNode items = previewBody.get("items");
        assertThat(items).hasSize(3);
        List<String> sortedKeys = java.util.stream.Stream.of(planA, planB, planC).sorted().toList();
        for (int i = 0; i < 3; i++) {
            assertThat(items.get(i).get("scheduleKey").asText()).isEqualTo(sortedKeys.get(i));
            assertThat(items.get(i).get("version").asInt()).isEqualTo(1);
        }
        // 交换前后占用随各自计划正确关联（A: 8→9，B: 9→10，C: 10→8）
        assertItemSegment(items, planA, iso(8), iso(9));
        assertItemSegment(items, planB, iso(9), iso(10));
        assertItemSegment(items, planC, iso(10), iso(8));

        // 预览不改任何占用
        assertPlanOccupancy(planA, 1, "PUBLISHED", iso(8), iso(9));

        String requestKey = key("REQ");
        MvcResult activated = mvc.perform(post("/api/v1/capacity-swaps/{key}/activate", swapKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk()).andReturn();
        JsonNode activatedBody = read(activated);
        assertThat(activatedBody.get("status").asText()).isEqualTo("ACTIVATED");
        assertThat(activatedBody.get("activatedAt").isNull()).isFalse();
        assertThat(activatedBody.get("conflicts")).isEmpty();
        JsonNode activatedItems = activatedBody.get("items");
        assertThat(activatedItems).hasSize(3);
        for (JsonNode item : activatedItems) {
            assertThat(item.get("version").asInt()).isEqualTo(2);
        }

        // 占用整体旋转：A→[09,10) B→[10,11) C→[08,09)，计划仍 PUBLISHED 且版本各递增一次
        assertPlanOccupancy(planA, 2, "PUBLISHED", iso(9), iso(10));
        assertPlanOccupancy(planB, 2, "PUBLISHED", iso(10), iso(11));
        assertPlanOccupancy(planC, 2, "PUBLISHED", iso(8), iso(9));

        // 生效时隙仍完整覆盖 08:00-11:00，仅归属轮换
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode slotList = read(slots);
        assertThat(slotList).hasSize(3);
        assertThat(slotList.get(0).get("scheduleKey").asText()).isEqualTo(planC);
        assertThat(slotList.get(1).get("scheduleKey").asText()).isEqualTo(planA);
        assertThat(slotList.get(2).get("scheduleKey").asText()).isEqualTo(planB);

        // 证据只读且稳定：两次查询完全一致，前后快照与交换一致
        MvcResult evidence1 = mvc.perform(get("/api/v1/capacity-swaps/{key}", swapKey))
                .andExpect(status().isOk()).andReturn();
        MvcResult evidence2 = mvc.perform(get("/api/v1/capacity-swaps/{key}", swapKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(evidence1)).isEqualTo(read(evidence2));
        JsonNode evidenceItems = read(evidence1).get("items");
        assertItemSegment(evidenceItems, planA, iso(8), iso(9));
        assertItemSegment(evidenceItems, planB, iso(9), iso(10));
        assertItemSegment(evidenceItems, planC, iso(10), iso(8));
    }

    // ---------- 外部冲突：预览报告 + 激活 422 整体回滚 ----------

    @Test
    void previewReportsExternalConflictAndActivateRejectsWithRollback() throws Exception {
        String section = key("SEC");
        String thirdParty = key("SCH");
        createPublished(thirdParty, "G9", section, iso(12), iso(13));

        String planA = key("SCH");
        String planB = key("SCH");
        createPublished(planA, "G1", section, iso(8), iso(9));
        createPublished(planB, "G2", section, iso(9), iso(10));

        String swapKey = key("SWAP");
        // A 的目标段与未参与的第三方已发布计划冲突
        String body = swapBody(swapKey,
                item(planA, 1, seg(section, iso(8), iso(9)), seg(section, iso(12), iso(13))),
                item(planB, 1, seg(section, iso(9), iso(10)), seg(section, iso(8), iso(9))));

        MvcResult preview = mvc.perform(post("/api/v1/capacity-swaps")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode conflicts = read(preview).get("conflicts");
        assertThat(conflicts).isNotEmpty();
        JsonNode conflict = conflicts.get(0);
        assertThat(conflict.get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(conflict.get("conflictingScheduleKey").asText()).isEqualTo(thirdParty);

        // 激活：外部冲突 → 422，整体回滚不释放任何旧占用
        MvcResult result = mvc.perform(post("/api/v1/capacity-swaps/{key}/activate", swapKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("SLOT_CONFLICT");

        assertPlanOccupancy(planA, 1, "PUBLISHED", iso(8), iso(9));
        assertPlanOccupancy(planB, 1, "PUBLISHED", iso(9), iso(10));
        // 交换单仍为 PREVIEW
        MvcResult evidence = mvc.perform(get("/api/v1/capacity-swaps/{key}", swapKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(evidence).get("status").asText()).isEqualTo("PREVIEW");
    }

    // ---------- 版本变化：409 且整体回滚 ----------

    @Test
    void activateRejectsVersionChangeFromConcurrentSwapAndRollsBack() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createPublished(planA, "G1", section, iso(8), iso(9));
        createPublished(planB, "G2", section, iso(9), iso(10));

        // 交换单 1：A、B 互换
        String swap1 = key("SWAP");
        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapBody(swap1,
                                item(planA, 1, seg(section, iso(8), iso(9)),
                                        seg(section, iso(9), iso(10))),
                                item(planB, 1, seg(section, iso(9), iso(10)),
                                        seg(section, iso(8), iso(9))))))
                .andExpect(status().isCreated());
        // 交换单 2：同一对计划，不同目标（A→[10,11) B→[08,09)）
        String swap2 = key("SWAP");
        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapBody(swap2,
                                item(planA, 1, seg(section, iso(8), iso(9)),
                                        seg(section, iso(10), iso(11))),
                                item(planB, 1, seg(section, iso(9), iso(10)),
                                        seg(section, iso(8), iso(9))))))
                .andExpect(status().isCreated());

        // 交换单 1 先激活成功
        mvc.perform(post("/api/v1/capacity-swaps/{key}/activate", swap1)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());

        // 交换单 2 冻结的版本已过期 → 409，且不产生任何部分变更
        MvcResult result = mvc.perform(post("/api/v1/capacity-swaps/{key}/activate", swap2)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("VERSION_CONFLICT");

        assertPlanOccupancy(planA, 2, "PUBLISHED", iso(9), iso(10));
        assertPlanOccupancy(planB, 2, "PUBLISHED", iso(8), iso(9));
        MvcResult evidence = mvc.perform(get("/api/v1/capacity-swaps/{key}", swap2))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(evidence).get("status").asText()).isEqualTo("PREVIEW");
    }

    // ---------- 目标占用重复：409 ----------

    @Test
    void activateRejectsDuplicateTargets() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createPublished(planA, "G1", section, iso(8), iso(9));
        createPublished(planB, "G2", section, iso(9), iso(10));

        String swapKey = key("SWAP");
        // 两个交换项目标段完全相同
        MvcResult preview = mvc.perform(post("/api/v1/capacity-swaps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(swapBody(swapKey,
                                item(planA, 1, seg(section, iso(8), iso(9)),
                                        seg(section, iso(14), iso(15))),
                                item(planB, 1, seg(section, iso(9), iso(10)),
                                        seg(section, iso(14), iso(15))))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode conflicts = read(preview).get("conflicts");
        assertThat(conflicts).isNotEmpty();
        assertThat(conflicts.get(0).get("type").asText()).isEqualTo("TARGET_DUPLICATE");

        MvcResult result = mvc.perform(post("/api/v1/capacity-swaps/{key}/activate", swapKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("TARGET_DUPLICATE");
        assertPlanOccupancy(planA, 1, "PUBLISHED", iso(8), iso(9));
        assertPlanOccupancy(planB, 1, "PUBLISHED", iso(9), iso(10));
    }

    // ---------- 参与计划状态变化：409 且回滚 ----------

    @Test
    void activateRejectsCancelledParticipantAndRollsBack() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createPublished(planA, "G1", section, iso(8), iso(9));
        createPublished(planB, "G2", section, iso(9), iso(10));

        String swapKey = key("SWAP");
        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapBody(swapKey,
                                item(planA, 1, seg(section, iso(8), iso(9)),
                                        seg(section, iso(9), iso(10))),
                                item(planB, 1, seg(section, iso(9), iso(10)),
                                        seg(section, iso(8), iso(9))))))
                .andExpect(status().isCreated());

        // 预览后 A 被取消
        mvc.perform(post("/api/v1/plans/{key}/cancel", planA)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(post("/api/v1/capacity-swaps/{key}/activate", swapKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("PLAN_STATE_CONFLICT");
        // B 不受任何影响
        assertPlanOccupancy(planB, 1, "PUBLISHED", iso(9), iso(10));
    }

    // ---------- 预览参数与状态校验 ----------

    @Test
    void previewRejectsDraftPlan() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createPublished(planA, "G1", section, iso(8), iso(9));
        createPlan(planB, occ("G2", section, iso(9), iso(10)));

        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapBody(key("SWAP"),
                                item(planA, 1, seg(section, iso(8), iso(9)),
                                        seg(section, iso(9), iso(10))),
                                item(planB, 1, seg(section, iso(9), iso(10)),
                                        seg(section, iso(8), iso(9))))))
                .andExpect(status().isConflict());
    }

    @Test
    void previewRejectsMissingPlan() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        createPublished(planA, "G1", section, iso(8), iso(9));

        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapBody(key("SWAP"),
                                item(planA, 1, seg(section, iso(8), iso(9)),
                                        seg(section, iso(9), iso(10))),
                                item(key("SCH"), 1, seg(section, iso(9), iso(10)),
                                        seg(section, iso(8), iso(9))))))
                .andExpect(status().isNotFound());
    }

    @Test
    void previewRejectsDuplicateItemAndBadSegment() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createPublished(planA, "G1", section, iso(8), iso(9));
        createPublished(planB, "G2", section, iso(9), iso(10));

        // 同一计划出现两次
        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapBody(key("SWAP"),
                                item(planA, 1, seg(section, iso(8), iso(9)),
                                        seg(section, iso(9), iso(10))),
                                item(planA, 1, seg(section, iso(8), iso(9)),
                                        seg(section, iso(10), iso(11))))))
                .andExpect(status().isBadRequest());
        // 结束不晚于开始
        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapBody(key("SWAP"),
                                item(planA, 1, seg(section, iso(8), iso(9)),
                                        seg(section, iso(9), iso(9))),
                                item(planB, 1, seg(section, iso(9), iso(10)),
                                        seg(section, iso(8), iso(9))))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void previewReportsVersionAndCurrentOccupancyMismatch() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createPublished(planA, "G1", section, iso(8), iso(9));
        createPublished(planB, "G2", section, iso(9), iso(10));

        String swapKey = key("SWAP");
        MvcResult preview = mvc.perform(post("/api/v1/capacity-swaps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(swapBody(swapKey,
                                // A 版本与当前占用均不符
                                item(planA, 5, seg(section, iso(8), iso(10)),
                                        seg(section, iso(9), iso(10))),
                                item(planB, 1, seg(section, iso(9), iso(10)),
                                        seg(section, iso(8), iso(9))))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode conflicts = read(preview).get("conflicts");
        assertThat(conflicts).hasSize(2);
        assertThat(conflicts.get(0).get("type").asText()).isEqualTo("VERSION_MISMATCH");
        assertThat(conflicts.get(0).get("actualVersion").asInt()).isEqualTo(1);
        assertThat(conflicts.get(1).get("type").asText()).isEqualTo("CURRENT_OCCUPANCY_MISMATCH");

        // 激活必然 409（版本不符先裁决）
        mvc.perform(post("/api/v1/capacity-swaps/{key}/activate", swapKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());
    }

    // ---------- swapKey 唯一与幂等 ----------

    @Test
    void swapKeyIsUniqueAcrossRequests() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createPublished(planA, "G1", section, iso(8), iso(9));
        createPublished(planB, "G2", section, iso(9), iso(10));

        String swapKey = key("SWAP");
        String body = swapBody(swapKey,
                item(planA, 1, seg(section, iso(8), iso(9)), seg(section, iso(9), iso(10))),
                item(planB, 1, seg(section, iso(9), iso(10)), seg(section, iso(8), iso(9))));
        mvc.perform(post("/api/v1/capacity-swaps")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        // 同 swapKey 再次创建 → 409
        mvc.perform(post("/api/v1/capacity-swaps")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void activateIdempotentReplayAndKeyReuseConflict() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createPublished(planA, "G1", section, iso(8), iso(9));
        createPublished(planB, "G2", section, iso(9), iso(10));

        String swapKey = key("SWAP");
        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapBody(swapKey,
                                item(planA, 1, seg(section, iso(8), iso(9)),
                                        seg(section, iso(9), iso(10))),
                                item(planB, 1, seg(section, iso(9), iso(10)),
                                        seg(section, iso(8), iso(9))))))
                .andExpect(status().isCreated());

        String requestKey = key("REQ");
        MvcResult first = mvc.perform(post("/api/v1/capacity-swaps/{key}/activate", swapKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk()).andReturn();

        // 同键同参重放首次响应，且不重复递增版本
        MvcResult replay = mvc.perform(post("/api/v1/capacity-swaps/{key}/activate", swapKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        assertPlanOccupancy(planA, 2, "PUBLISHED", iso(9), iso(10));

        // 已激活交换单换幂等键再激活：返回既有证据，不重复生效
        MvcResult again = mvc.perform(post("/api/v1/capacity-swaps/{key}/activate", swapKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(again).get("status").asText()).isEqualTo("ACTIVATED");
        assertPlanOccupancy(planA, 2, "PUBLISHED", iso(9), iso(10));

        // 同幂等键用于另一交换单 → 409
        String swap2 = key("SWAP");
        String planC = key("SCH");
        String planD = key("SCH");
        createPublished(planC, "G3", section, iso(14), iso(15));
        createPublished(planD, "G4", section, iso(15), iso(16));
        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapBody(swap2,
                                item(planC, 1, seg(section, iso(14), iso(15)),
                                        seg(section, iso(15), iso(16))),
                                item(planD, 1, seg(section, iso(15), iso(16)),
                                        seg(section, iso(14), iso(15))))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/capacity-swaps/{key}/activate", swap2)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isConflict());
    }

    @Test
    void activateFailureDoesNotConsumeRequestKey() throws Exception {
        String section = key("SEC");
        String thirdParty = key("SCH");
        createPublished(thirdParty, "G9", section, iso(12), iso(13));
        String planA = key("SCH");
        String planB = key("SCH");
        createPublished(planA, "G1", section, iso(8), iso(9));
        createPublished(planB, "G2", section, iso(9), iso(10));

        String swapKey = key("SWAP");
        mvc.perform(post("/api/v1/capacity-swaps").contentType(MediaType.APPLICATION_JSON)
                        .content(swapBody(swapKey,
                                item(planA, 1, seg(section, iso(8), iso(9)),
                                        seg(section, iso(12), iso(13))),
                                item(planB, 1, seg(section, iso(9), iso(10)),
                                        seg(section, iso(8), iso(9))))))
                .andExpect(status().isCreated());

        String requestKey = key("REQ");
        // 外部冲突 → 422，失败不占键
        mvc.perform(post("/api/v1/capacity-swaps/{key}/activate", swapKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isUnprocessableEntity());

        // 第三方取消后冲突消失，同键重试成功
        mvc.perform(post("/api/v1/plans/{key}/cancel", thirdParty)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/capacity-swaps/{key}/activate", swapKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
        assertPlanOccupancy(planA, 2, "PUBLISHED", iso(12), iso(13));
    }

    @Test
    void getSwapReturns404ForMissingSwap() throws Exception {
        mvc.perform(get("/api/v1/capacity-swaps/{key}", key("SWAP")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/capacity-swaps/{key}/activate", key("SWAP"))
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound());
    }

    // ---------- 辅助 ----------

    /**
     * 在交换项数组中按计划键定位，并断言其交换前/后唯一段的起始时刻。
     */
    private static void assertItemSegment(JsonNode items, String scheduleKey,
                                          String beforeStart, String afterStart) {
        JsonNode found = null;
        for (JsonNode item : items) {
            if (item.get("scheduleKey").asText().equals(scheduleKey)) {
                found = item;
                break;
            }
        }
        assertThat(found).as("交换项包含计划 " + scheduleKey).isNotNull();
        assertThat(found.get("before").get(0).get("startUtc").asText()).isEqualTo(beforeStart);
        assertThat(found.get("after").get(0).get("startUtc").asText()).isEqualTo(afterStart);
    }

    private void assertPlanOccupancy(String scheduleKey, int version, String status,
                                     String startIso, String endIso) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(detail);
        assertThat(body.get("version").asInt()).isEqualTo(version);
        assertThat(body.get("status").asText()).isEqualTo(status);
        assertThat(body.get("occupancies")).hasSize(1);
        assertThat(body.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(startIso);
        assertThat(body.get("occupancies").get(0).get("endUtc").asText()).isEqualTo(endIso);
    }

    private void createPublished(String scheduleKey, String trainNo, String section,
                                 String startIso, String endIso) throws Exception {
        createPlan(scheduleKey, occ(trainNo, section, startIso, endIso));
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String seg(String section, String startIso, String endIso) {
        return "{\"sectionId\":\"" + section + "\",\"startUtc\":\"" + startIso
                + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String item(String scheduleKey, int expectedVersion,
                               String current, String target) {
        return "{\"scheduleKey\":\"" + scheduleKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"currentOccupancies\":[" + current + "],\"targetOccupancies\":[" + target
                + "]}";
    }

    private static String swapBody(String swapKey, String... items) {
        return "{\"swapKey\":\"" + swapKey + "\",\"opDate\":\"" + DAY + "\",\"items\":["
                + String.join(",", items) + "]}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }
}
