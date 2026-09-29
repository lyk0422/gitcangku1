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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 区段维护封锁 API 的端到端测试（真实 H2 内存库，MODE=MySQL）：
 * 登记主流程（题干 10:00-11:00 / 10:30-10:45 场景）、撤销与整单改签、
 * 相邻端点、422 失败回滚、400/404/409 分支、封锁生效后的发布/恢复/改签 422、
 * 幂等重放与同键换参 409、受影响计划与原占用快照查询。
 * 各用例使用唯一键，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkBlockApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程 ----------

    @Test
    void registerBlockWithoutIntersectionsBecomesEffective() throws Exception {
        String blockKey = key("BLK");
        String section = key("SEC");
        MvcResult created = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), blockKey, section, iso(10), iso(11), "[]")))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("blockKey").asText()).isEqualTo(blockKey);
        assertThat(body.get("status").asText()).isEqualTo("EFFECTIVE");
        assertThat(body.get("startUtc").asText()).isEqualTo(iso(10));
        assertThat(body.get("affectedPlans")).isEmpty();

        MvcResult detail = mvc.perform(get("/api/v1/work-blocks/{blockKey}", blockKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);

        MvcResult list = mvc.perform(get("/api/v1/work-blocks")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(list)).hasSize(1);
        assertThat(read(list).get(0).get("blockKey").asText()).isEqualTo(blockKey);

        // 按日期（不限区段）也能查到
        MvcResult listByDate = mvc.perform(get("/api/v1/work-blocks")
                        .param("date", DAY.toString()))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(listByDate).findValuesAsText("blockKey")).contains(blockKey);
    }

    @Test
    void specScenarioUnclearedFailsThenWholePlanRescheduleAfterElevenSucceeds() throws Exception {
        String section = key("SEC-R");
        String blockKey = key("BLK");
        String oldKey = key("SCH-P1");
        String requestKey = key("REQ");
        createPlan(oldKey, occ("G30", section, iso(10, 30), iso(10, 45)));
        publishPlan(oldKey, key("REQ"));

        // 未撤销/改签相交计划：登记失败，封锁不生效，计划保持发布
        String firstAttempt = blockBody(requestKey, blockKey, section, iso(10), iso(11), "[]");
        MvcResult fail = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON).content(firstAttempt))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(fail);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("WORK_BLOCK_UNCLEARED");
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(oldKey);
        mvc.perform(get("/api/v1/work-blocks/{blockKey}", blockKey))
                .andExpect(status().isNotFound());
        assertThat(planStatus(oldKey)).isEqualTo("PUBLISHED");

        // 整单改签到 11:00 开始（窗口端点相邻）的新草稿
        String newKey = key("SCH-P2");
        createPlan(newKey, occ("G31", section, iso(11), iso(11, 30)));
        String plans = "[" + rescheduleAction(oldKey, newKey) + "]";
        // 失败不占幂等键：同键补全处理清单后登记成功
        MvcResult ok = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(requestKey, blockKey, section, iso(10), iso(11), plans)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode block = read(ok);
        assertThat(block.get("status").asText()).isEqualTo("EFFECTIVE");
        JsonNode affected = block.get("affectedPlans").get(0);
        assertThat(affected.get("scheduleKey").asText()).isEqualTo(oldKey);
        assertThat(affected.get("action").asText()).isEqualTo("RESCHEDULED");
        assertThat(affected.get("successorScheduleKey").asText()).isEqualTo(newKey);
        // 相交原占用快照保留原区段、原时段 10:30-10:45
        JsonNode slot = affected.get("slots").get(0);
        assertThat(slot.get("sectionId").asText()).isEqualTo(section);
        assertThat(slot.get("startUtc").asText()).isEqualTo(iso(10, 30));
        assertThat(slot.get("endUtc").asText()).isEqualTo(iso(10, 45));

        assertThat(planStatus(oldKey)).isEqualTo("CANCELLED");
        assertThat(planStatus(newKey)).isEqualTo("PUBLISHED");

        // 生效时隙只剩 11:00 开始的后继计划
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode slotList = read(slots);
        assertThat(slotList).hasSize(1);
        assertThat(slotList.get(0).get("scheduleKey").asText()).isEqualTo(newKey);
        assertThat(slotList.get(0).get("startUtc").asText()).isEqualTo(iso(11));
    }

    @Test
    void cancelActionCancelsPlanAndKeepsOriginalSnapshot() throws Exception {
        String section = key("SEC-R");
        String blockKey = key("BLK");
        String oldKey = key("SCH-P1");
        createPlan(oldKey, occ("G30", section, iso(10, 30), iso(10, 45)));
        publishPlan(oldKey, key("REQ"));

        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), blockKey, section, iso(10), iso(11),
                                "[" + cancelAction(oldKey) + "]")))
                .andExpect(status().isCreated());

        assertThat(planStatus(oldKey)).isEqualTo("CANCELLED");
        MvcResult detail = mvc.perform(get("/api/v1/work-blocks/{blockKey}", blockKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode affected = read(detail).get("affectedPlans").get(0);
        assertThat(affected.get("action").asText()).isEqualTo("CANCELLED");
        assertThat(affected.get("successorScheduleKey").isNull()).isTrue();
        assertThat(affected.get("slots")).hasSize(1);
        assertThat(affected.get("slots").get(0).get("sectionId").asText()).isEqualTo(section);
    }

    @Test
    void adjacentEndpointsDoNotConflict() throws Exception {
        String section = key("SEC-R");
        // 09:00-10:00 与 11:00-12:00 均与 [10:00,11:00) 仅端点相邻
        String before = key("SCH-BEFORE");
        String after = key("SCH-AFTER");
        createPlan(before, occ("G1", section, iso(9), iso(10)));
        publishPlan(before, key("REQ"));
        createPlan(after, occ("G2", section, iso(11), iso(12)));
        publishPlan(after, key("REQ"));

        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), key("BLK"), section, iso(10), iso(11), "[]")))
                .andExpect(status().isCreated());

        // 相邻计划不受影响，时隙仍在
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).hasSize(2);
        assertThat(planStatus(before)).isEqualTo("PUBLISHED");
        assertThat(planStatus(after)).isEqualTo("PUBLISHED");
    }

    // ---------- 封锁生效后的发布/恢复/改签 422 ----------

    @Test
    void publishRestoreRescheduleIntoEffectiveBlockReturn422() throws Exception {
        String section = key("SEC-R");
        // 封锁登记时以 CANCEL 清掉相交计划 cancelled，制造“已撤销且占用仍落在封锁窗口内”的状态
        String cancelled = key("SCH-CAN");
        createPlan(cancelled, occ("G3", section, iso(10, 15), iso(10, 45)));
        publishPlan(cancelled, key("REQ"));
        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), key("BLK"), section, iso(10), iso(11),
                                "[" + cancelAction(cancelled) + "]")))
                .andExpect(status().isCreated());

        // 发布仍占用该区段交叉窗口的草稿 → 422，保持草稿
        String inside = key("SCH-IN");
        createPlan(inside, occ("G1", section, iso(10, 30), iso(10, 45)));
        MvcResult publishFail = mvc.perform(post("/api/v1/plans/{key}/publish", inside)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(publishFail).get("details").get(0).get("type").asText())
                .isEqualTo("WORK_BLOCK_CONFLICT");
        assertThat(planStatus(inside)).isEqualTo("DRAFT");

        // 端点相邻的新计划可发布
        String adjacent = key("SCH-ADJ");
        createPlan(adjacent, occ("G2", section, iso(11), iso(11, 30)));
        publishPlan(adjacent, key("REQ"));

        // 恢复被封锁撤销的计划 → 422，保持 CANCELLED
        MvcResult restoreFail = mvc.perform(post("/api/v1/plans/{key}/restore", cancelled)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(restoreFail).get("code").asText()).isEqualTo("SLOT_CONFLICT");
        assertThat(planStatus(cancelled)).isEqualTo("CANCELLED");

        // 改签进入封锁窗口 → 422 回滚
        String oldKey = key("SCH-OLD");
        String draftKey = key("SCH-DRAFT");
        createPlan(oldKey, occ("G4", key("SEC-OTHER"), iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        createPlan(draftKey, occ("G5", section, iso(10, 30), iso(10, 50)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), draftKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(planStatus(oldKey)).isEqualTo("PUBLISHED");
        assertThat(planStatus(draftKey)).isEqualTo("DRAFT");

        // 恢复窗口外的已撤销计划成功
        String outside = key("SCH-OUT");
        createPlan(outside, occ("G6", section, iso(8), iso(9)));
        publishPlan(outside, key("REQ"));
        cancelPlan(outside, key("REQ"));
        mvc.perform(post("/api/v1/plans/{key}/restore", outside)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        assertThat(planStatus(outside)).isEqualTo("PUBLISHED");
    }

    @Test
    void restoreRejectsRescheduledCancelledPlan() throws Exception {
        String section = key("SEC-R");
        String oldKey = key("SCH-OLD");
        String newKey = key("SCH-NEW");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        createPlan(newKey, occ("G2", section, iso(9), iso(10)));
        reschedule(oldKey, newKey, 1, 1);

        // 已改签取消的计划存在直接后继，不可恢复
        mvc.perform(post("/api/v1/plans/{key}/restore", oldKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());
        // 草稿/已发布不可恢复
        mvc.perform(post("/api/v1/plans/{key}/restore", newKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());
    }

    // ---------- 登记失败回滚 ----------

    @Test
    void registerRollsBackWhenRescheduleStillConflictsBlock() throws Exception {
        String section = key("SEC-R");
        String blockKey = key("BLK");
        String oldKey = key("SCH-P1");
        String newKey = key("SCH-P2");
        createPlan(oldKey, occ("G1", section, iso(10, 30), iso(10, 45)));
        publishPlan(oldKey, key("REQ"));
        // 后继草稿整单仍在封锁窗口内
        createPlan(newKey, occ("G2", section, iso(10, 40), iso(10, 50)));

        MvcResult fail = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), blockKey, section, iso(10), iso(11),
                                "[" + rescheduleAction(oldKey, newKey) + "]")))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(fail).get("details").get(0).get("type").asText())
                .isEqualTo("WORK_BLOCK_CONFLICT");

        // 整单回滚：封锁不存在、旧计划仍发布、后继仍草稿、无改签关联
        mvc.perform(get("/api/v1/work-blocks/{blockKey}", blockKey))
                .andExpect(status().isNotFound());
        assertThat(planStatus(oldKey)).isEqualTo("PUBLISHED");
        assertThat(planStatus(newKey)).isEqualTo("DRAFT");
        assertChainKeys(oldKey, oldKey);
    }

    @Test
    void registerRollsBackWhenSuccessorsConflictEachOther() throws Exception {
        String section = key("SEC-R");
        String blockKey = key("BLK");
        String p1 = key("SCH-P1");
        String p2 = key("SCH-P2");
        String s1 = key("SCH-S1");
        String s2 = key("SCH-S2");
        createPlan(p1, occ("G1", section, iso(10, 10), iso(10, 20)));
        publishPlan(p1, key("REQ"));
        createPlan(p2, occ("G2", section, iso(10, 40), iso(10, 50)));
        publishPlan(p2, key("REQ"));
        // 两个后继都改到窗外但彼此相交
        createPlan(s1, occ("G3", section, iso(11), iso(11, 30)));
        createPlan(s2, occ("G4", section, iso(11, 15), iso(11, 45)));

        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), blockKey, section, iso(10), iso(11),
                                "[" + rescheduleAction(p1, s1) + "," + rescheduleAction(p2, s2) + "]")))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(get("/api/v1/work-blocks/{blockKey}", blockKey))
                .andExpect(status().isNotFound());
        assertThat(planStatus(p1)).isEqualTo("PUBLISHED");
        assertThat(planStatus(p2)).isEqualTo("PUBLISHED");
        assertThat(planStatus(s1)).isEqualTo("DRAFT");
        assertThat(planStatus(s2)).isEqualTo("DRAFT");
    }

    // ---------- 400 / 404 / 409 分支 ----------

    @Test
    void registerRejectsInvalidRequests() throws Exception {
        String section = key("SEC-R");
        // 结束不晚于开始
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), key("BLK"), section, iso(10), iso(10), "[]")))
                .andExpect(status().isBadRequest());
        // 非法 action
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), key("BLK"), section, iso(10), iso(11),
                                "[" + planAction(key("SCH"), "HOLD", null) + "]")))
                .andExpect(status().isBadRequest());
        // RESCHEDULE 缺 newScheduleKey
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), key("BLK"), section, iso(10), iso(11),
                                "[" + planAction(key("SCH"), "RESCHEDULE", null) + "]")))
                .andExpect(status().isBadRequest());
        // CANCEL 携带 newScheduleKey
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), key("BLK"), section, iso(10), iso(11),
                                "[" + planAction(key("SCH"), "CANCEL", key("SCH2")) + "]")))
                .andExpect(status().isBadRequest());
        // 清单中计划重复
        String dup = key("SCH");
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), key("BLK"), section, iso(10), iso(11),
                                "[" + cancelAction(dup) + "," + cancelAction(dup) + "]")))
                .andExpect(status().isBadRequest());
        // 清单含不存在的计划
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), key("BLK"), section, iso(10), iso(11),
                                "[" + cancelAction(key("SCH-MISSING")) + "]")))
                .andExpect(status().isBadRequest());
        // 清单含与窗口不相交的已发布计划
        String unrelated = key("SCH");
        createPlan(unrelated, occ("G9", section, iso(8), iso(9)));
        publishPlan(unrelated, key("REQ"));
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), key("BLK"), section, iso(10), iso(11),
                                "[" + cancelAction(unrelated) + "]")))
                .andExpect(status().isBadRequest());
        // 查询不存在的封锁
        mvc.perform(get("/api/v1/work-blocks/{blockKey}", key("BLK")))
                .andExpect(status().isNotFound());
    }

    @Test
    void registerRejectsDraftPlanInClearList() throws Exception {
        String section = key("SEC-R");
        String draftKey = key("SCH-D");
        // 草稿占用与窗口相交，但只有已发布计划才需要清；草稿出现在清单中 → 422
        createPlan(draftKey, occ("G1", section, iso(10, 30), iso(10, 45)));
        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), key("BLK"), section, iso(10), iso(11),
                                "[" + cancelAction(draftKey) + "]")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(planStatus(draftKey)).isEqualTo("DRAFT");

        // 草稿不阻止封锁生效：空清单登记成功
        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), key("BLK2"), section, iso(10), iso(11), "[]")))
                .andExpect(status().isCreated());
    }

    @Test
    void duplicateBlockKeyRejected() throws Exception {
        String section = key("SEC-R");
        String blockKey = key("BLK");
        String body = blockBody(key("REQ"), blockKey, section, iso(10), iso(11), "[]");
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        // 新幂等键、重复封锁键 → 409
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), blockKey, section, iso(10), iso(11), "[]")))
                .andExpect(status().isConflict());
    }

    // ---------- 幂等 ----------

    @Test
    void workBlockIdempotentReplayAndParamConflict() throws Exception {
        String section = key("SEC-R");
        String blockKey = key("BLK");
        String requestKey = key("REQ");
        String body = blockBody(requestKey, blockKey, section, iso(10), iso(11), "[]");

        MvcResult first = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键换窗口 → 409
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(requestKey, blockKey, section, iso(10), iso(10, 30), "[]")))
                .andExpect(status().isConflict());
        // 同键换区段 → 409
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(requestKey, blockKey, key("SEC-OTHER"),
                                iso(10), iso(11), "[]")))
                .andExpect(status().isConflict());
        // 同键换封锁键 → 409
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(requestKey, key("BLK-OTHER"), section,
                                iso(10), iso(11), "[]")))
                .andExpect(status().isConflict());
    }

    @Test
    void workBlockIdempotentConflictsOnChangedPlanSet() throws Exception {
        String section = key("SEC-R");
        String blockKey = key("BLK");
        String p1 = key("SCH-P1");
        String p2 = key("SCH-P2");
        String s1 = key("SCH-S1");
        createPlan(p1, occ("G1", section, iso(10, 10), iso(10, 20)));
        publishPlan(p1, key("REQ"));
        createPlan(p2, occ("G2", section, iso(10, 40), iso(10, 50)));
        publishPlan(p2, key("REQ"));
        createPlan(s1, occ("G3", section, iso(11), iso(11, 30)));

        String requestKey = key("REQ");
        String cancelBoth = blockBody(requestKey, blockKey, section, iso(10), iso(11),
                "[" + cancelAction(p1) + "," + cancelAction(p2) + "]");
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(cancelBoth))
                .andExpect(status().isCreated());

        // 同键、计划集合不同（一个改签收尾） → 409
        mvc.perform(post("/api/v1/work-blocks").contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(requestKey, blockKey, section, iso(10), iso(11),
                                "[" + cancelAction(p1) + "]")))
                .andExpect(status().isConflict());
    }

    @Test
    void restoreIdempotentReplay() throws Exception {
        String section = key("SEC-R");
        String planKey = key("SCH");
        createPlan(planKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(planKey, key("REQ"));
        cancelPlan(planKey, key("REQ"));
        String requestKey = key("REQ");
        String body = actionBody(requestKey);

        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/restore", planKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/restore", planKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        assertThat(read(replay).get("status").asText()).isEqualTo("PUBLISHED");
    }

    // ---------- 历史快照不随后续改签变化 ----------

    @Test
    void cancelledPlanHistoryAndBlockSnapshotSurviveLaterReschedule() throws Exception {
        String section = key("SEC-R");
        String blockKey = key("BLK");
        String p1 = key("SCH-P1");
        String p2 = key("SCH-P2");
        String p3 = key("SCH-P3");
        createPlan(p1, occ("G1", section, iso(10, 30), iso(10, 45)));
        publishPlan(p1, key("REQ"));
        createPlan(p2, occ("G2", section, iso(11), iso(11, 30)));
        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockBody(key("REQ"), blockKey, section, iso(10), iso(11),
                                "[" + rescheduleAction(p1, p2) + "]")))
                .andExpect(status().isCreated());

        // 后继 P2 再改签到另一区段的 P3
        String otherSection = key("SEC-S");
        createPlan(p3, occ("G3", otherSection, iso(11), iso(11, 30)));
        reschedule(p2, p3, 1, 1);

        // 封锁处理结果快照不变：原区段 R、原时段 10:30-10:45，后继键仍为 P2
        MvcResult detail = mvc.perform(get("/api/v1/work-blocks/{blockKey}", blockKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode affected = read(detail).get("affectedPlans").get(0);
        assertThat(affected.get("scheduleKey").asText()).isEqualTo(p1);
        assertThat(affected.get("successorScheduleKey").asText()).isEqualTo(p2);
        JsonNode slot = affected.get("slots").get(0);
        assertThat(slot.get("sectionId").asText()).isEqualTo(section);
        assertThat(slot.get("startUtc").asText()).isEqualTo(iso(10, 30));
        assertThat(slot.get("endUtc").asText()).isEqualTo(iso(10, 45));

        // 已撤销旧计划明细保留原区段与时段
        MvcResult oldDetail = mvc.perform(get("/api/v1/plans/{key}", p1))
                .andExpect(status().isOk()).andReturn();
        JsonNode oldOcc = read(oldDetail).get("occupancies").get(0);
        assertThat(oldOcc.get("sectionId").asText()).isEqualTo(section);
        assertThat(oldOcc.get("startUtc").asText()).isEqualTo(iso(10, 30));
        assertThat(oldOcc.get("endUtc").asText()).isEqualTo(iso(10, 45));
    }

    // ---------- 辅助 ----------

    private String planStatus(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("status").asText();
    }

    private void assertChainKeys(String queryKey, String... expectedKeys) throws Exception {
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", queryKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(chain).get("chain");
        assertThat(items).hasSize(expectedKeys.length);
        for (int i = 0; i < expectedKeys.length; i++) {
            assertThat(items.get(i).get("scheduleKey").asText()).isEqualTo(expectedKeys[i]);
        }
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, occupancies)))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private void cancelPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private void reschedule(String oldKey, String newKey, int oldVersion, int newVersion)
            throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, oldVersion, newVersion)))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        Instant instant = DAY.atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String createBody(String requestKey, String scheduleKey, String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "]}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }

    private static String cancelAction(String scheduleKey) {
        return planAction(scheduleKey, "CANCEL", null);
    }

    private static String rescheduleAction(String scheduleKey, String newScheduleKey) {
        return planAction(scheduleKey, "RESCHEDULE", newScheduleKey);
    }

    private static String planAction(String scheduleKey, String action, String newScheduleKey) {
        String suffix = newScheduleKey == null ? ""
                : ",\"newScheduleKey\":\"" + newScheduleKey + "\"";
        return "{\"scheduleKey\":\"" + scheduleKey + "\",\"action\":\"" + action + "\"" + suffix + "}";
    }

    private static String blockBody(String requestKey, String blockKey, String section,
                                    String startIso, String endIso, String plansJson) {
        return "{\"requestKey\":\"" + requestKey + "\",\"blockKey\":\"" + blockKey
                + "\",\"sectionId\":\"" + section + "\",\"startUtc\":\"" + startIso
                + "\",\"endUtc\":\"" + endIso + "\",\"plans\":" + plansJson + "}";
    }
}
