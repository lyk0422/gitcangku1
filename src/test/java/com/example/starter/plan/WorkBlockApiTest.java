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
 * 区段维护封锁 API 端到端测试（真实 H2 内存库，MODE=MySQL）：
 * 题干场景、登记失败回滚、撤销/改签处理、封锁生效后的发布/恢复/改签/批量改签 422、
 * 相邻端点合法、查询与历史保留、幂等重放与同键换参 409。
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

    // ---------- 题干主场景 ----------

    @Test
    void blockFailsUntilConflictingPlanRescheduledWholeAfterWindow() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        // 区段 R 10:00-11:00 封锁，原计划 10:30-10:45
        createPlan(oldKey, occ("G1", section, iso(10, 30), iso(10, 45)));
        publishPlan(oldKey, key("REQ"));

        String blockKey = key("BLK");
        String blockBody = registerBody(key("REQ"), blockKey, section, iso(10), iso(11));

        // 未撤销/改签相交计划时登记失败：422，封锁不生效，计划仍发布
        MvcResult fail = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON).content(blockBody))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(fail);
        assertThat(error.get("code").asText()).isEqualTo("BLOCK_CLEARANCE_REQUIRED");
        assertThat(error.get("details").get(0).get("scheduleKey").asText()).isEqualTo(oldKey);
        mvc.perform(get("/api/v1/work-blocks/{key}", blockKey))
                .andExpect(status().isNotFound());
        assertThat(getPlan(oldKey).get("status").asText()).isEqualTo("PUBLISHED");

        // 准备整单改签的新草稿：11:00 开始，与封锁窗口相邻但不相交
        String newKey = key("SCH");
        createPlan(newKey, occ("G2", section, iso(11), iso(11, 30)));

        // 整单改签到 11:00 开始后封锁才生效
        MvcResult ok = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), blockKey, section, iso(10), iso(11),
                                rescheduleClearance(oldKey, newKey, 1, 1))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(ok);
        assertThat(body.get("block").get("blockKey").asText()).isEqualTo(blockKey);
        assertThat(body.get("block").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("clearances")).hasSize(1);
        assertThat(body.get("clearances").get(0).get("action").asText()).isEqualTo("RESCHEDULED");
        assertThat(body.get("clearances").get(0).get("newScheduleKey").asText()).isEqualTo(newKey);

        // 旧计划已取消且历史保留原区段与 10:30-10:45 时段
        JsonNode oldDetail = getPlan(oldKey);
        assertThat(oldDetail.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(oldDetail.get("occupancies")).hasSize(1);
        assertThat(oldDetail.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(10, 30));
        assertThat(oldDetail.get("occupancies").get(0).get("endUtc").asText())
                .isEqualTo(iso(10, 45));
        assertThat(getPlan(newKey).get("status").asText()).isEqualTo("PUBLISHED");

        // 封锁详情列出封锁、受影响计划与处理结果
        MvcResult detail = mvc.perform(get("/api/v1/work-blocks/{key}", blockKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode detailBody = read(detail);
        assertThat(detailBody.get("block").get("sectionId").asText()).isEqualTo(section);
        assertThat(detailBody.get("clearances")).hasSize(1);
        JsonNode clearance = detailBody.get("clearances").get(0);
        assertThat(clearance.get("scheduleKey").asText()).isEqualTo(oldKey);
        assertThat(clearance.get("action").asText()).isEqualTo("RESCHEDULED");
        assertThat(clearance.get("oldPlan").get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(10, 30));
        assertThat(clearance.get("newPlan").get("scheduleKey").asText()).isEqualTo(newKey);
    }

    @Test
    void registerBlockByCancellingIntersectingPlan() throws Exception {
        String section = key("SEC");
        String planKey = key("SCH");
        createPlan(planKey, occ("G1", section, iso(10, 30), iso(10, 45)));
        publishPlan(planKey, key("REQ"));

        String blockKey = key("BLK");
        MvcResult ok = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), blockKey, section, iso(10), iso(11),
                                cancelClearance(planKey))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(ok);
        assertThat(body.get("clearances").get(0).get("action").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("clearances").get(0).get("newScheduleKey").isNull()).isTrue();
        assertThat(getPlan(planKey).get("status").asText()).isEqualTo("CANCELLED");

        // 列表查询与详情一致
        MvcResult list = mvc.perform(get("/api/v1/work-blocks")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode blocks = read(list);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).get("blockKey").asText()).isEqualTo(blockKey);
        assertThat(blocks.get(0).get("startUtc").asText()).isEqualTo(iso(10));

        // 撤销后时隙释放：published-slots 不再返回该计划
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).isEmpty();
    }

    @Test
    void adjacentWindowIsNotConflictAndEmptyClearanceRegistersBlock() throws Exception {
        String section = key("SEC");
        // 已发布计划 11:00-12:00，与封锁 10:00-11:00 仅端点相邻
        String adjacent = key("SCH");
        createPlan(adjacent, occ("G1", section, iso(11), iso(12)));
        publishPlan(adjacent, key("REQ"));

        String blockKey = key("BLK");
        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), blockKey, section, iso(10), iso(11))))
                .andExpect(status().isCreated());

        // 相邻计划不受影响，仍发布；把不相交计划列入处理指令反而 422
        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("BLK"), section, iso(12), iso(13),
                                cancelClearance(adjacent))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(getPlan(adjacent).get("status").asText()).isEqualTo("PUBLISHED");
    }

    // ---------- 登记失败分支 ----------

    @Test
    void registerFailsWhenOnlySomeIntersectingPlansHandled() throws Exception {
        String section = key("SEC");
        String planA = key("SCH");
        String planB = key("SCH");
        createPlan(planA, occ("G1", section, iso(10, 15), iso(10, 30)));
        createPlan(planB, occ("G2", section, iso(10, 30), iso(10, 45)));
        publishPlan(planA, key("REQ"));
        publishPlan(planB, key("REQ"));

        // 只撤销 A，B 未处理 → 422，整体回滚：A 仍发布、封锁不存在
        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("BLK"), section, iso(10), iso(11),
                                cancelClearance(planA))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(getPlan(planA).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(planB).get("status").asText()).isEqualTo("PUBLISHED");
        mvc.perform(get("/api/v1/work-blocks")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0]").doesNotExist());
    }

    @Test
    void registerRollsBackWhenRescheduledPlanStillInWindow() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(10, 30), iso(10, 45)));
        publishPlan(oldKey, key("REQ"));
        // 新草稿仍落在封锁窗口内
        String newKey = key("SCH");
        createPlan(newKey, occ("G2", section, iso(10, 45), iso(10, 50)));

        MvcResult fail = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("BLK"), section, iso(10), iso(11),
                                rescheduleClearance(oldKey, newKey, 1, 1))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(fail).get("code").asText()).isEqualTo("SLOT_CONFLICT");
        assertThat(read(fail).get("details").get(0).get("type").asText())
                .isEqualTo("BLOCK_CONFLICT");

        // 整体回滚：封锁不存在、旧仍发布、新仍草稿、无改签关联
        mvc.perform(get("/api/v1/work-blocks/{key}", key("BLK")))
                .andExpect(status().isNotFound());
        assertThat(getPlan(oldKey).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(newKey).get("status").asText()).isEqualTo("DRAFT");
        MvcResult chain = mvc.perform(get("/api/v1/plans/{key}/reschedule-chain", oldKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(chain).get("chain")).hasSize(1);
    }

    @Test
    void registerRollsBackWhenNewDraftsConflictEachOther() throws Exception {
        String section = key("SEC");
        String oldA = key("SCH");
        String oldB = key("SCH");
        createPlan(oldA, occ("G1", section, iso(10, 15), iso(10, 30)));
        createPlan(oldB, occ("G2", section, iso(10, 30), iso(10, 45)));
        publishPlan(oldA, key("REQ"));
        publishPlan(oldB, key("REQ"));
        // 两份后继草稿改到封锁窗口后但彼此区段时隙重叠
        String newA = key("SCH");
        String newB = key("SCH");
        createPlan(newA, occ("G3", section, iso(11), iso(11, 30)));
        createPlan(newB, occ("G4", section, iso(11, 15), iso(11, 45)));

        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("BLK"), section, iso(10), iso(11),
                                rescheduleClearance(oldA, newA, 1, 1),
                                rescheduleClearance(oldB, newB, 1, 1))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(getPlan(oldA).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(oldB).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(newA).get("status").asText()).isEqualTo("DRAFT");
        assertThat(getPlan(newB).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void registerRejectsBadWindow() throws Exception {
        String section = key("SEC");
        // 结束早于开始
        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("BLK"), section, iso(11), iso(10))))
                .andExpect(status().isBadRequest());
        // 窗口超出运营日（次日 00:30 结束，越过当日 24:00 上界）
        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("BLK"), section,
                                iso(23), iso(0, 30, 1))))
                .andExpect(status().isBadRequest());
    }

    // ---------- 封锁生效后的发布 / 恢复 / 改签 ----------

    @Test
    void publishIntoActiveBlockReturns422ButAdjacentAllowed() throws Exception {
        String section = key("SEC");
        String blockKey = registerEmptyBlock(section, 10, 11);

        // 仍占用该区段交叉窗口 → 422
        String inside = key("SCH");
        createPlan(inside, occ("G1", section, iso(10, 30), iso(10, 45)));
        MvcResult fail = mvc.perform(post("/api/v1/plans/{key}/publish", inside)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(fail).get("details").get(0).get("type").asText())
                .isEqualTo("BLOCK_CONFLICT");
        assertThat(read(fail).get("details").get(0).get("blockKey").asText()).isEqualTo(blockKey);
        assertThat(getPlan(inside).get("status").asText()).isEqualTo("DRAFT");

        // 相邻端点 09:00-10:00 与 11:00-12:00 均可发布
        String before = key("SCH");
        createPlan(before, occ("G2", section, iso(9), iso(10)));
        publishPlan(before, key("REQ"));
        String after = key("SCH");
        createPlan(after, occ("G3", section, iso(11), iso(12)));
        publishPlan(after, key("REQ"));

        // 不同区段不受影响
        String other = key("SCH");
        createPlan(other, occ("G4", key("SEC"), iso(10, 30), iso(10, 45)));
        publishPlan(other, key("REQ"));
    }

    @Test
    void restorePlanInActiveBlockReturns422AndOutsideSucceeds() throws Exception {
        String section = key("SEC");
        // 封锁登记时撤销的计划，原时段在封锁内
        String inside = key("SCH");
        createPlan(inside, occ("G1", section, iso(10, 30), iso(10, 45)));
        publishPlan(inside, key("REQ"));
        registerBlock(key("BLK"), section, 10, 11, cancelClearance(inside));

        mvc.perform(post("/api/v1/plans/{key}/restore", inside)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(getPlan(inside).get("status").asText()).isEqualTo("CANCELLED");

        // 与封锁不相交的已取消计划（08:00-09:00）可以恢复
        String outside = key("SCH");
        createPlan(outside, occ("G2", section, iso(8), iso(9)));
        publishPlan(outside, key("REQ"));
        cancelPlan(outside, key("REQ"));
        MvcResult restored = mvc.perform(post("/api/v1/plans/{key}/restore", outside)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(restored).get("status").asText()).isEqualTo("PUBLISHED");

        // 草稿不可恢复 → 409
        String draft = key("SCH");
        createPlan(draft, occ("G3", section, iso(7), iso(8)));
        mvc.perform(post("/api/v1/plans/{key}/restore", draft)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());
    }

    @Test
    void rescheduleIntoActiveBlockReturns422() throws Exception {
        String section = key("SEC");
        registerEmptyBlock(section, 10, 11);

        String oldKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(oldKey, key("REQ"));
        String newKey = key("SCH");
        createPlan(newKey, occ("G2", section, iso(10, 30), iso(10, 45)));

        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(getPlan(oldKey).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(newKey).get("status").asText()).isEqualTo("DRAFT");

        // 改签到窗口外（相邻）成功
        String adjacentKey = key("SCH");
        createPlan(adjacentKey, occ("G3", section, iso(11), iso(11, 30)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), adjacentKey, 1, 1)))
                .andExpect(status().isOk());
    }

    // ---------- 批量改签 ----------

    @Test
    void batchRescheduleIsAtomicAndIdempotent() throws Exception {
        String section = key("SEC");
        String blockKey = registerEmptyBlock(section, 10, 11);
        // 两个封锁窗口外的已发布计划（可作为批量改签旧计划）
        String planA = key("SCH");
        String planB = key("SCH");
        createPlan(planA, occ("G1", section, iso(7), iso(7, 30)));
        createPlan(planB, occ("G2", section, iso(7, 30), iso(8)));
        publishPlan(planA, key("REQ"));
        publishPlan(planB, key("REQ"));

        // 新草稿：A' 11:00-11:30 合法（与封锁相邻）；B' 14:00-14:30 合法
        String newA = key("SCH");
        String newB = key("SCH");
        createPlan(newA, occ("G3", section, iso(11), iso(11, 30)));
        createPlan(newB, occ("G4", section, iso(14), iso(14, 30)));

        // 另一份落入封锁窗口的草稿 badB：整批含它必须 422 回滚
        String badB = key("SCH");
        createPlan(badB, occ("G5", section, iso(10, 20), iso(10, 40)));
        String requestKey = key("REQ");
        String badBody = batchBody(requestKey,
                rescheduleClearance(planA, newA, 1, 1),
                rescheduleClearance(planB, badB, 1, 1));
        mvc.perform(post("/api/v1/work-blocks/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(badBody))
                .andExpect(status().isUnprocessableEntity());

        // 全部保持原样：A/B 仍发布、各草稿仍 DRAFT、封锁仍 ACTIVE
        assertThat(getPlan(planA).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(planB).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(newA).get("status").asText()).isEqualTo("DRAFT");
        assertThat(getPlan(badB).get("status").asText()).isEqualTo("DRAFT");
        mvc.perform(get("/api/v1/work-blocks/{key}", blockKey))
                .andExpect(status().isOk());

        // 失败不占键：修正计划集合后同键成功
        String goodBody = batchBody(requestKey,
                rescheduleClearance(planA, newA, 1, 1),
                rescheduleClearance(planB, newB, 1, 1));
        MvcResult ok = mvc.perform(post("/api/v1/work-blocks/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(goodBody))
                .andExpect(status().isOk()).andReturn();
        JsonNode batch = read(ok);
        assertThat(batch.get("results")).hasSize(2);
        assertThat(batch.get("results").get(0).get("newPlan").get("scheduleKey").asText())
                .isEqualTo(newA);
        assertThat(getPlan(newA).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(newB).get("status").asText()).isEqualTo("PUBLISHED");

        // 同键同参重放返回首次结果
        MvcResult replay = mvc.perform(post("/api/v1/work-blocks/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON).content(goodBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(batch);

        // 同键换计划集合 → 409
        mvc.perform(post("/api/v1/work-blocks/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(requestKey, rescheduleClearance(planA, newA, 1, 1))))
                .andExpect(status().isConflict());
    }

    @Test
    void batchRescheduleRejectsWhenAnyItemStillConflicts() throws Exception {
        String section = key("SEC");
        registerEmptyBlock(section, 10, 11);
        // 两个封锁外已发布计划
        String planA = key("SCH");
        String planB = key("SCH");
        createPlan(planA, occ("G1", section, iso(7), iso(7, 30)));
        createPlan(planB, occ("G2", section, iso(7, 30), iso(8)));
        publishPlan(planA, key("REQ"));
        publishPlan(planB, key("REQ"));
        String newA = key("SCH");
        String newB = key("SCH");
        // A' 合法改到 11:00；B' 改入封锁窗口 10:20
        createPlan(newA, occ("G3", section, iso(11), iso(11, 30)));
        createPlan(newB, occ("G4", section, iso(10, 20), iso(10, 40)));

        mvc.perform(post("/api/v1/work-blocks/batch-reschedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody(key("REQ"),
                                rescheduleClearance(planA, newA, 1, 1),
                                rescheduleClearance(planB, newB, 1, 1))))
                .andExpect(status().isUnprocessableEntity());
        // 全部保持原样
        assertThat(getPlan(planA).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(planB).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(getPlan(newA).get("status").asText()).isEqualTo("DRAFT");
        assertThat(getPlan(newB).get("status").asText()).isEqualTo("DRAFT");
    }

    // ---------- 幂等 ----------

    @Test
    void registerIdempotentReplayAndParamConflict() throws Exception {
        String section = key("SEC");
        String blockKey = key("BLK");
        String requestKey = key("REQ");
        String body = registerBody(requestKey, blockKey, section, iso(10), iso(11));

        MvcResult first = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键换窗口 → 409
        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, blockKey, section, iso(14), iso(15))))
                .andExpect(status().isConflict());
        // 同键换区段 → 409
        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, blockKey, key("SEC"),
                                iso(10), iso(11))))
                .andExpect(status().isConflict());
    }

    @Test
    void registerFailureDoesNotConsumeRequestKey() throws Exception {
        String section = key("SEC");
        String planKey = key("SCH");
        createPlan(planKey, occ("G1", section, iso(10, 30), iso(10, 45)));
        publishPlan(planKey, key("REQ"));
        String blockKey = key("BLK");
        String requestKey = key("REQ");

        // 缺少处理指令 → 422
        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, blockKey, section, iso(10), iso(11))))
                .andExpect(status().isUnprocessableEntity());
        // 同键补撤销指令后成功
        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, blockKey, section, iso(10), iso(11),
                                cancelClearance(planKey))))
                .andExpect(status().isCreated());
    }

    @Test
    void restoreIdempotentReplayAndParamConflict() throws Exception {
        String section = key("SEC");
        // 用窗口外的取消计划保证恢复成功
        String planKey = key("SCH");
        createPlan(planKey, occ("G1", section, iso(8), iso(9)));
        publishPlan(planKey, key("REQ"));
        cancelPlan(planKey, key("REQ"));
        registerEmptyBlock(section, 10, 11);

        String requestKey = key("REQ");
        String body = actionBody(requestKey);
        MvcResult first = mvc.perform(post("/api/v1/plans/{key}/restore", planKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/plans/{key}/restore", planKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
    }

    // ---------- 历史保留 ----------

    @Test
    void cancelledPlanHistoryKeepsOriginalWindowAfterLaterReschedules() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        createPlan(oldKey, occ("G1", section, iso(10, 30), iso(10, 45)));
        publishPlan(oldKey, key("REQ"));
        String midKey = key("SCH");
        createPlan(midKey, occ("G2", section, iso(11), iso(11, 30)));
        String blockKey = key("BLK");
        registerBlock(blockKey, section, 10, 11,
                rescheduleClearance(oldKey, midKey, 1, 1));

        // 后继再次改签：midKey → finalKey（封锁结束后更远的窗口）
        String finalKey = key("SCH");
        createPlan(finalKey, occ("G3", section, iso(12), iso(12, 30)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", midKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), finalKey, 1, 1)))
                .andExpect(status().isOk());

        // 原计划历史不随后续改签变化
        JsonNode oldDetail = getPlan(oldKey);
        assertThat(oldDetail.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(oldDetail.get("occupancies")).hasSize(1);
        assertThat(oldDetail.get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(10, 30));
        assertThat(oldDetail.get("occupancies").get(0).get("endUtc").asText())
                .isEqualTo(iso(10, 45));

        // 封锁处理结果仍指向第一次改签，oldPlan 快照为原始区段时段
        MvcResult detail = mvc.perform(get("/api/v1/work-blocks/{key}", blockKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode clearance = read(detail).get("clearances").get(0);
        assertThat(clearance.get("newScheduleKey").asText()).isEqualTo(midKey);
        assertThat(clearance.get("oldPlan").get("occupancies").get(0).get("startUtc").asText())
                .isEqualTo(iso(10, 30));
    }

    // ---------- 辅助 ----------

    private String registerEmptyBlock(String section, int startHour, int endHour) throws Exception {
        String blockKey = key("BLK");
        registerBlock(blockKey, section, startHour, endHour);
        return blockKey;
    }

    private void registerBlock(String blockKey, String section, int startHour, int endHour,
                               String... clearances) throws Exception {
        mvc.perform(post("/api/v1/work-blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), blockKey, section,
                                iso(startHour), iso(endHour), clearances)))
                .andExpect(status().isCreated());
    }

    private JsonNode getPlan(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result);
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

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String iso(int hour, int minute, int dayOffset) {
        Instant instant = DAY.plusDays(dayOffset).atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
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

    private static String cancelClearance(String scheduleKey) {
        return "{\"scheduleKey\":\"" + scheduleKey + "\"}";
    }

    private static String rescheduleClearance(String oldKey, String newKey,
                                              int oldVersion, int newVersion) {
        return "{\"scheduleKey\":\"" + oldKey + "\",\"newScheduleKey\":\"" + newKey
                + "\",\"expectedOldVersion\":" + oldVersion
                + ",\"expectedNewVersion\":" + newVersion + "}";
    }

    private static String registerBody(String requestKey, String blockKey, String section,
                                       String startIso, String endIso, String... clearances) {
        return "{\"requestKey\":\"" + requestKey + "\",\"blockKey\":\"" + blockKey
                + "\",\"opDate\":\"" + DAY + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso
                + "\",\"clearances\":[" + String.join(",", clearances) + "]}";
    }

    private static String batchBody(String requestKey, String... clearances) {
        return "{\"requestKey\":\"" + requestKey + "\",\"clearances\":["
                + String.join(",", clearances) + "]}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }
}
