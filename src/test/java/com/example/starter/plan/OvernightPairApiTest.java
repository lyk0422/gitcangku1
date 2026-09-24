package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 跨零点夜间时隙与双运营日联合发布的端到端测试（H2 内存库，MODE=MySQL）。
 * 覆盖夜间窗口校验、计划对联合发布/整体回滚、跨日占用裁剪判定、单成员取消、
 * 幂等边界与夜间改签保持 nightPairKey。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OvernightPairApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    private static final LocalDate NEXT_DAY = DAY.plusDays(1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 夜间区间校验 ----------

    @Test
    void overnightDraftAcceptsWindowAndRejectsOutOfWindow() throws Exception {
        // 22:00 至次日 06:00 整 8 小时，边界合法
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(nightCreateBody(key("REQ"), key("SCH"), key("PAIR"), DAY,
                                occ("G1", key("SEC"), t(0, 22), t(1, 6)))))
                .andExpect(status().isCreated());

        // 23:00 跨零点至 02:00 合法，响应回显 overnight 与 nightPairKey
        String scheduleKey = key("SCH");
        String pairKey = key("PAIR");
        MvcResult created = mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(nightCreateBody(key("REQ"), scheduleKey, pairKey, DAY,
                                occ("G2", key("SEC"), t(0, 23), t(1, 2)))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("overnight").asBoolean()).isTrue();
        assertThat(body.get("nightPairKey").asText()).isEqualTo(pairKey);

        // 开始早于 22:00 → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(nightCreateBody(key("REQ"), key("SCH"), key("PAIR"), DAY,
                                occ("G1", key("SEC"), t(0, 21, 59), t(1, 1)))))
                .andExpect(status().isBadRequest());
        // 结束晚于次日 06:00 → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(nightCreateBody(key("REQ"), key("SCH"), key("PAIR"), DAY,
                                occ("G1", key("SEC"), t(0, 22), t(1, 6, 1)))))
                .andExpect(status().isBadRequest());
        // 21:00-06:00 共 9 小时，超过 8 小时上限 → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(nightCreateBody(key("REQ"), key("SCH"), key("PAIR"), DAY,
                                occ("G1", key("SEC"), t(0, 21), t(1, 6)))))
                .andExpect(status().isBadRequest());
        // overnight 但缺少 nightPairKey → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(nightCreateBody(key("REQ"), key("SCH"), null, DAY,
                                occ("G1", key("SEC"), t(0, 22), t(1, 6)))))
                .andExpect(status().isBadRequest());
        // 非夜间草稿跨零点仍按旧规则拒绝 → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"), DAY,
                                occ("G1", key("SEC"), t(0, 23), t(1, 1)))))
                .andExpect(status().isBadRequest());
    }

    // ---------- 联合发布主流程与跨日裁剪 ----------

    @Test
    void pairPublishMakesBothPublishedAndClipsAcrossDays() throws Exception {
        String section = key("SEC");
        String pairKey = key("PAIR");
        String sameDayKey = key("SCH");
        String nextDayKey = key("SCH");
        createNightDraft(sameDayKey, pairKey, DAY, true, occ("G1", section, t(0, 23), t(1, 5)));
        createNightDraft(nextDayKey, pairKey, NEXT_DAY, false,
                occ("G2", section, t(1, 7), t(1, 8)));

        MvcResult result = mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey, sameDayKey, nextDayKey, 1, 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode pair = read(result);
        assertThat(pair.get("nightPairKey").asText()).isEqualTo(pairKey);
        assertThat(pair.get("sameDayPlan").get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(pair.get("nextDayPlan").get("status").asText()).isEqualTo("PUBLISHED");

        // 不可变计划对记录已写入
        Integer pairs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_night_pair WHERE night_pair_key = ?",
                Integer.class, pairKey);
        assertThat(pairs).isEqualTo(1);

        // 当日查询只看到落在当日的部分 23:00-24:00
        JsonNode daySlots = querySlots(DAY, section);
        assertThat(daySlots).hasSize(1);
        assertThat(daySlots.get(0).get("scheduleKey").asText()).isEqualTo(sameDayKey);
        assertThat(daySlots.get(0).get("startUtc").asText()).isEqualTo(t(0, 23));
        assertThat(daySlots.get(0).get("endUtc").asText()).isEqualTo(t(1, 0));

        // 次日查询看到跨零点裁剪部分 00:00-05:00 与次日计划 07:00-08:00
        JsonNode nextSlots = querySlots(NEXT_DAY, section);
        assertThat(nextSlots).hasSize(2);
        assertThat(nextSlots.get(0).get("scheduleKey").asText()).isEqualTo(sameDayKey);
        assertThat(nextSlots.get(0).get("startUtc").asText()).isEqualTo(t(1, 0));
        assertThat(nextSlots.get(0).get("endUtc").asText()).isEqualTo(t(1, 5));
        assertThat(nextSlots.get(1).get("scheduleKey").asText()).isEqualTo(nextDayKey);
        assertThat(nextSlots.get(1).get("startUtc").asText()).isEqualTo(t(1, 7));
        assertThat(nextSlots.get(1).get("endUtc").asText()).isEqualTo(t(1, 8));
    }

    @Test
    void overnightSinglePublishRejected() throws Exception {
        String pairKey = key("PAIR");
        String scheduleKey = key("SCH");
        String nextDayKey = key("SCH");
        createNightDraft(scheduleKey, pairKey, DAY, true,
                occ("G1", key("SEC"), t(0, 22), t(1, 6)));
        createNightDraft(nextDayKey, pairKey, NEXT_DAY, false,
                occ("G2", key("SEC"), t(1, 7), t(1, 8)));
        // 当日跨零点成员与次日普通成员都不允许单独发布
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/plans/{key}/publish", nextDayKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());
        assertThat(statusOf(scheduleKey)).isEqualTo("DRAFT");
        assertThat(statusOf(nextDayKey)).isEqualTo("DRAFT");
    }

    // ---------- 联合回滚与冲突明细 ----------

    @Test
    void pairPublishRollsBackBothOnNextDayConflict() throws Exception {
        String section = key("SEC");
        // 第三方已发布计划占用次日 03:00-04:00
        String thirdParty = key("SCH");
        createNormalDraft(thirdParty, NEXT_DAY, occ("G9", section, t(1, 3), t(1, 4)));
        publishPlan(thirdParty, key("REQ"));

        String pairKey = key("PAIR");
        String sameDayKey = key("SCH");
        String nextDayKey = key("SCH");
        String pairRequestKey = key("REQ");
        createNightDraft(sameDayKey, pairKey, DAY, true, occ("G1", section, t(0, 22), t(1, 5)));
        createNightDraft(nextDayKey, pairKey, NEXT_DAY, false,
                occ("G2", section, t(1, 7), t(1, 8)));

        MvcResult result = mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(pairRequestKey, pairKey, sameDayKey, nextDayKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(detail.get("opDate").asText()).isEqualTo(NEXT_DAY.toString());
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(thirdParty);

        // 两张都保持草稿，本键无计划对记录
        assertThat(statusOf(sameDayKey)).isEqualTo("DRAFT");
        assertThat(statusOf(nextDayKey)).isEqualTo("DRAFT");
        Integer ownPairs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_night_pair WHERE night_pair_key = ?",
                Integer.class, pairKey);
        assertThat(ownPairs).isZero();

        // 失败不占键：把当日夜间占用缩短到 00:30 结束（版本升 2），同 requestKey 以新参重试成功
        mvc.perform(put("/api/v1/plans/{key}/occupancies", sameDayKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, occ("G1", section, t(0, 22), t(1, 0, 30)))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(pairRequestKey, pairKey, sameDayKey, nextDayKey, 2, 1)))
                .andExpect(status().isOk());
        assertThat(statusOf(sameDayKey)).isEqualTo("PUBLISHED");
        assertThat(statusOf(nextDayKey)).isEqualTo("PUBLISHED");
    }

    @Test
    void pairPublishDetectsConflictBetweenTheTwoDrafts() throws Exception {
        String section = key("SEC");
        String pairKey = key("PAIR");
        String sameDayKey = key("SCH");
        String nextDayKey = key("SCH");
        // 当日跨零点 23:00-02:00 与次日草稿 01:00-03:00 在次日相互重叠
        createNightDraft(sameDayKey, pairKey, DAY, true, occ("G1", section, t(0, 23), t(1, 2)));
        createNightDraft(nextDayKey, pairKey, NEXT_DAY, false,
                occ("G2", section, t(1, 1), t(1, 3)));

        MvcResult result = mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey, sameDayKey, nextDayKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode details = read(result).get("details");
        assertThat(details.get(0).get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(details.get(0).get("opDate").asText()).isEqualTo(NEXT_DAY.toString());
        assertThat(statusOf(sameDayKey)).isEqualTo("DRAFT");
        assertThat(statusOf(nextDayKey)).isEqualTo("DRAFT");
    }

    @Test
    void endpointTouchingIsNotConflict() throws Exception {
        String section = key("SEC");
        // 当日 20:00-22:00 与夜间 22:00 起端点相接；次日 01:00-02:00 与夜间 00:00-01:00 端点相接
        String dayNeighbor = key("SCH");
        createNormalDraft(dayNeighbor, DAY, occ("G8", section, t(0, 20), t(0, 22)));
        publishPlan(dayNeighbor, key("REQ"));
        String nextNeighbor = key("SCH");
        createNormalDraft(nextNeighbor, NEXT_DAY, occ("G9", section, t(1, 1), t(1, 2)));
        publishPlan(nextNeighbor, key("REQ"));

        String pairKey = key("PAIR");
        String sameDayKey = key("SCH");
        String nextDayKey = key("SCH");
        createNightDraft(sameDayKey, pairKey, DAY, true, occ("G1", section, t(0, 22), t(1, 1)));
        createNightDraft(nextDayKey, pairKey, NEXT_DAY, false,
                occ("G2", section, t(1, 2), t(1, 3)));

        mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey, sameDayKey, nextDayKey, 1, 1)))
                .andExpect(status().isOk());
    }

    // ---------- 版本/形态/状态冲突 409 与 404 ----------

    @Test
    void pairPublishRejectsShapeVersionAndStateConflicts() throws Exception {
        String pairKey = key("PAIR");
        String sameDayKey = key("SCH");
        String nextDayKey = key("SCH");
        createNightDraft(sameDayKey, pairKey, DAY, true, occ("G1", key("SEC"), t(0, 22), t(1, 6)));
        createNightDraft(nextDayKey, pairKey, NEXT_DAY, false,
                occ("G2", key("SEC"), t(1, 7), t(1, 8)));

        // 当日版本不符
        mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey, sameDayKey, nextDayKey, 7, 1)))
                .andExpect(status().isConflict());
        // 次日版本不符
        mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey, sameDayKey, nextDayKey, 1, 7)))
                .andExpect(status().isConflict());
        // nightPairKey 不一致
        mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), key("OTHER"), sameDayKey, nextDayKey, 1, 1)))
                .andExpect(status().isConflict());
        // 运营日不相邻：用一张 D+2 的草稿冒次日
        String farKey = key("SCH");
        createNightDraft(farKey, pairKey, NEXT_DAY.plusDays(1), false,
                occ("G3", key("SEC"), t(2, 7), t(2, 8)));
        mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey, sameDayKey, farKey, 1, 1)))
                .andExpect(status().isConflict());

        // 任一草稿不存在 → 404
        mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey, key("MISSING"), nextDayKey, 1, 1)))
                .andExpect(status().isNotFound());

        // 成功发布后重复联合发布 → 409 状态冲突
        mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey, sameDayKey, nextDayKey, 1, 1)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ2"), pairKey, sameDayKey, nextDayKey, 1, 1)))
                .andExpect(status().isConflict());
    }

    @Test
    void pairPublishRejectsSameDayDraftNotOvernight() throws Exception {
        String pairKey = key("PAIR");
        String sameDayKey = key("SCH");
        String nextDayKey = key("SCH");
        // 当日草稿是携带 pairKey 的普通草稿
        createNightDraft(sameDayKey, pairKey, DAY, false, occ("G1", key("SEC"), t(0, 10), t(0, 11)));
        createNightDraft(nextDayKey, pairKey, NEXT_DAY, false,
                occ("G2", key("SEC"), t(1, 7), t(1, 8)));
        mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey, sameDayKey, nextDayKey, 1, 1)))
                .andExpect(status().isConflict());
    }

    // ---------- nightPairKey 全局唯一 ----------

    @Test
    void nightPairKeyGloballyUnique() throws Exception {
        String pairKey = key("PAIR");
        String firstSame = key("SCH");
        String firstNext = key("SCH");
        createNightDraft(firstSame, pairKey, DAY, true,
                occ("G1", key("SEC"), t(0, 22), t(1, 0, 30)));
        createNightDraft(firstNext, pairKey, NEXT_DAY, false,
                occ("G2", key("SEC"), t(1, 7), t(1, 7, 30)));
        pairPublish(pairKey, firstSame, firstNext);

        // 另一对草稿复用同一 nightPairKey → 联合发布 409，两张保持草稿
        String secondSame = key("SCH");
        String secondNext = key("SCH");
        createNightDraft(secondSame, pairKey, DAY.plusDays(2), true,
                occ("G3", key("SEC"), t(2, 22), t(3, 0, 30)));
        createNightDraft(secondNext, pairKey, NEXT_DAY.plusDays(2), false,
                occ("G4", key("SEC"), t(3, 7), t(3, 7, 30)));
        mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey, secondSame, secondNext, 1, 1)))
                .andExpect(status().isConflict());
        assertThat(statusOf(secondSame)).isEqualTo("DRAFT");
        assertThat(statusOf(secondNext)).isEqualTo("DRAFT");
    }

    // ---------- 单成员取消 ----------

    @Test
    void cancelOnePairMemberReleasesOnlyItsOwnSlots() throws Exception {
        String section = key("SEC");
        String pairKey = key("PAIR");
        String sameDayKey = key("SCH");
        String nextDayKey = key("SCH");
        createNightDraft(sameDayKey, pairKey, DAY, true, occ("G1", section, t(0, 23), t(1, 5)));
        createNightDraft(nextDayKey, pairKey, NEXT_DAY, false,
                occ("G2", section, t(1, 7), t(1, 8)));
        pairPublish(pairKey, sameDayKey, nextDayKey);

        // 取消当日成员：只释放其占用（当日与次日 00-05 部分），次日成员保持已发布
        mvc.perform(post("/api/v1/night-pairs/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairCancelBody(key("REQ"), sameDayKey)))
                .andExpect(status().isOk());
        assertThat(statusOf(sameDayKey)).isEqualTo("CANCELLED");
        assertThat(statusOf(nextDayKey)).isEqualTo("PUBLISHED");
        assertThat(querySlots(DAY, section)).isEmpty();
        JsonNode nextSlots = querySlots(NEXT_DAY, section);
        assertThat(nextSlots).hasSize(1);
        assertThat(nextSlots.get(0).get("scheduleKey").asText()).isEqualTo(nextDayKey);

        // 不可变计划对记录保留
        Integer pairs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_night_pair WHERE night_pair_key = ?",
                Integer.class, pairKey);
        assertThat(pairs).isEqualTo(1);

        // 释放出的凌晨窗口可被新的普通计划占用（02:00-03:00）
        String replacement = key("SCH");
        createNormalDraft(replacement, NEXT_DAY, occ("G5", section, t(1, 2), t(1, 3)));
        publishPlan(replacement, key("REQ"));

        // 已取消成员不能重复取消
        mvc.perform(post("/api/v1/night-pairs/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairCancelBody(key("REQ2"), sameDayKey)))
                .andExpect(status().isConflict());

        // 取消次日成员：当日成员已取消，夜间窗口当日部分仍为空；次日 07-08 也释放
        mvc.perform(post("/api/v1/night-pairs/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairCancelBody(key("REQ3"), nextDayKey)))
                .andExpect(status().isOk());
        assertThat(statusOf(nextDayKey)).isEqualTo("CANCELLED");
        // 02-03 的替代计划仍在，故次日查询剩 1 条
        assertThat(querySlots(NEXT_DAY, section)).hasSize(1);
        assertThat(querySlots(NEXT_DAY, section).get(0).get("scheduleKey").asText())
                .isEqualTo(replacement);
    }

    @Test
    void pairCancelRejectsNonMemberAndDraft() throws Exception {
        String pairKey = key("PAIR");
        String sameDayKey = key("SCH");
        String nextDayKey = key("SCH");
        createNightDraft(sameDayKey, pairKey, DAY, true, occ("G1", key("SEC"), t(0, 23), t(1, 5)));
        createNightDraft(nextDayKey, pairKey, NEXT_DAY, false,
                occ("G2", key("SEC"), t(1, 7), t(1, 8)));

        // 尚未联合发布，按计划对取消 → 409
        mvc.perform(post("/api/v1/night-pairs/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairCancelBody(key("REQ"), sameDayKey)))
                .andExpect(status().isConflict());

        pairPublish(pairKey, sameDayKey, nextDayKey);

        // 不属于任何计划对的普通已发布计划走计划对取消 → 409
        String outsider = key("SCH");
        createNormalDraft(outsider, NEXT_DAY, occ("G6", key("SEC"), t(1, 9), t(1, 10)));
        publishPlan(outsider, key("REQ"));
        mvc.perform(post("/api/v1/night-pairs/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairCancelBody(key("REQ2"), outsider)))
                .andExpect(status().isConflict());

        // 不存在 → 404
        mvc.perform(post("/api/v1/night-pairs/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairCancelBody(key("REQ3"), key("MISSING"))))
                .andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void pairPublishIdempotentReplayAndParamConflict() throws Exception {
        String pairKey = key("PAIR");
        String sameDayKey = key("SCH");
        String nextDayKey = key("SCH");
        createNightDraft(sameDayKey, pairKey, DAY, true, occ("G1", key("SEC"), t(0, 23), t(1, 2)));
        createNightDraft(nextDayKey, pairKey, NEXT_DAY, false,
                occ("G2", key("SEC"), t(1, 7), t(1, 8)));
        String requestKey = key("REQ");
        String body = pairPublishBody(requestKey, pairKey, sameDayKey, nextDayKey, 1, 1);

        MvcResult first = mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键改参 → 409
        mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(requestKey, pairKey, sameDayKey, nextDayKey, 2, 1)))
                .andExpect(status().isConflict());

        // 取消同样幂等：同键重放返回 CANCELLED 快照
        String cancelKey = key("REQ");
        String cancelBody = pairCancelBody(cancelKey, sameDayKey);
        MvcResult cancelFirst = mvc.perform(post("/api/v1/night-pairs/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content(cancelBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult cancelReplay = mvc.perform(post("/api/v1/night-pairs/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content(cancelBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(cancelReplay)).isEqualTo(read(cancelFirst));
    }

    // ---------- 夜间改签保持 nightPairKey ----------

    @Test
    void rescheduleOvernightMemberKeepsNightPairKey() throws Exception {
        String section = key("SEC");
        String pairKey = key("PAIR");
        String oldSame = key("SCH");
        String nextMember = key("SCH");
        createNightDraft(oldSame, pairKey, DAY, true, occ("G1", section, t(0, 22), t(0, 23)));
        createNightDraft(nextMember, pairKey, NEXT_DAY, false,
                occ("G2", section, t(1, 7), t(1, 8)));
        pairPublish(pairKey, oldSame, nextMember);

        // 新夜间草稿保持同一 nightPairKey、同一运营日与跨零点角色
        String newSame = key("SCH");
        createNightDraft(newSame, pairKey, DAY, true, occ("G3", section, t(0, 22, 30), t(0, 23, 30)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldSame)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newSame, 1, 1)))
                .andExpect(status().isOk());
        assertThat(statusOf(oldSame)).isEqualTo("CANCELLED");
        assertThat(statusOf(newSame)).isEqualTo("PUBLISHED");
        // 计划对另一张不受影响
        assertThat(statusOf(nextMember)).isEqualTo("PUBLISHED");
        // 当日查询切换到新计划 22:30-23:30
        JsonNode daySlots = querySlots(DAY, section);
        assertThat(daySlots).hasSize(1);
        assertThat(daySlots.get(0).get("scheduleKey").asText()).isEqualTo(newSame);
        assertThat(daySlots.get(0).get("startUtc").asText()).isEqualTo(t(0, 22, 30));

        // 丢失 nightPairKey 的新草稿改签 → 409 且无副作用
        String badDraft = key("SCH");
        createNormalDraft(badDraft, DAY, occ("G4", section, t(0, 10), t(0, 11)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", newSame)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ2"), badDraft, 1, 1)))
                .andExpect(status().isConflict());
        assertThat(statusOf(badDraft)).isEqualTo("DRAFT");
        assertThat(statusOf(newSame)).isEqualTo("PUBLISHED");
    }

    @Test
    void rescheduleNextDayMemberKeepsNightPairKey() throws Exception {
        String section = key("SEC");
        String pairKey = key("PAIR");
        String sameMember = key("SCH");
        String oldNext = key("SCH");
        createNightDraft(sameMember, pairKey, DAY, true, occ("G1", section, t(0, 23), t(1, 0, 30)));
        createNightDraft(oldNext, pairKey, NEXT_DAY, false, occ("G2", section, t(1, 7), t(1, 8)));
        pairPublish(pairKey, sameMember, oldNext);

        // 次日成员改签：新草稿必须携带同一 pairKey（普通角色）
        String newNext = key("SCH");
        createNightDraft(newNext, pairKey, NEXT_DAY, false, occ("G3", section, t(1, 9), t(1, 10)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldNext)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newNext, 1, 1)))
                .andExpect(status().isOk());
        assertThat(statusOf(oldNext)).isEqualTo("CANCELLED");
        assertThat(statusOf(newNext)).isEqualTo("PUBLISHED");
        assertThat(statusOf(sameMember)).isEqualTo("PUBLISHED");

        // 未携带 pairKey 的普通草稿不能改签为次日成员
        String badDraft = key("SCH");
        createNormalDraft(badDraft, NEXT_DAY, occ("G5", section, t(1, 11), t(1, 12)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", newNext)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ2"), badDraft, 1, 1)))
                .andExpect(status().isConflict());
        assertThat(statusOf(badDraft)).isEqualTo("DRAFT");
    }

    @Test
    void rescheduledMemberCanStillBePairCancelled() throws Exception {
        String section = key("SEC");
        String pairKey = key("PAIR");
        String oldSame = key("SCH");
        String nextMember = key("SCH");
        createNightDraft(oldSame, pairKey, DAY, true, occ("G1", section, t(0, 22), t(0, 23)));
        createNightDraft(nextMember, pairKey, NEXT_DAY, false,
                occ("G2", section, t(1, 7), t(1, 8)));
        pairPublish(pairKey, oldSame, nextMember);

        String newSame = key("SCH");
        createNightDraft(newSame, pairKey, DAY, true, occ("G3", section, t(0, 22, 30), t(0, 23, 30)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldSame)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newSame, 1, 1)))
                .andExpect(status().isOk());

        // 改签后的新计划仍是计划对成员，可通过计划对取消入口释放，另一张保持已发布
        mvc.perform(post("/api/v1/night-pairs/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairCancelBody(key("REQ"), newSame)))
                .andExpect(status().isOk());
        assertThat(statusOf(newSame)).isEqualTo("CANCELLED");
        assertThat(statusOf(nextMember)).isEqualTo("PUBLISHED");
        assertThat(querySlots(DAY, section)).isEmpty();
    }

    // ---------- 辅助 ----------

    private void createNightDraft(String scheduleKey, String pairKey, LocalDate opDate,
                                  boolean overnight, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(nightCreateBody(key("REQ"), scheduleKey, pairKey, opDate,
                                overnight, occupancies)))
                .andExpect(status().isCreated());
    }

    private void createNormalDraft(String scheduleKey, LocalDate opDate, String... occupancies)
            throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, opDate, occupancies)))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private void pairPublish(String pairKey, String sameDayKey, String nextDayKey) throws Exception {
        mvc.perform(post("/api/v1/night-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey, sameDayKey, nextDayKey, 1, 1)))
                .andExpect(status().isOk());
    }

    private String statusOf(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("status").asText();
    }

    private JsonNode querySlots(LocalDate opDate, String section) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/published-slots")
                        .param("date", opDate.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        return read(result);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** DAY 加 dayOffset 天 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String t(int dayOffset, int hour) {
        return t(dayOffset, hour, 0);
    }

    /** DAY 加 dayOffset 天 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String t(int dayOffset, int hour, int minute) {
        Instant instant = DAY.plusDays(dayOffset).atTime(hour, minute).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String createBody(String requestKey, String scheduleKey, LocalDate opDate,
                                     String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + opDate + "\",\"occupancies\":[" + String.join(",", occupancies)
                + "]}";
    }

    private static String nightCreateBody(String requestKey, String scheduleKey, String pairKey,
                                          LocalDate opDate, String... occupancies) {
        return nightCreateBody(requestKey, scheduleKey, pairKey, opDate, true, occupancies);
    }

    private static String nightCreateBody(String requestKey, String scheduleKey, String pairKey,
                                          LocalDate opDate, boolean overnight,
                                          String... occupancies) {
        String pairJson = pairKey == null ? "null" : "\"" + pairKey + "\"";
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey
                + "\",\"opDate\":\"" + opDate + "\",\"overnight\":" + overnight
                + ",\"nightPairKey\":" + pairJson
                + ",\"occupancies\":[" + String.join(",", occupancies) + "]}";
    }

    private static String pairPublishBody(String requestKey, String pairKey,
                                          String sameDayKey, String nextDayKey,
                                          int sameVersion, int nextVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"nightPairKey\":\"" + pairKey
                + "\",\"sameDayScheduleKey\":\"" + sameDayKey + "\",\"nextDayScheduleKey\":\""
                + nextDayKey + "\",\"expectedSameDayVersion\":" + sameVersion
                + ",\"expectedNextDayVersion\":" + nextVersion + "}";
    }

    private static String pairCancelBody(String requestKey, String scheduleKey) {
        return "{\"requestKey\":\"" + requestKey + "\",\"scheduleKey\":\"" + scheduleKey + "\"}";
    }

    private static String updateBody(String requestKey, int expectedVersion, String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"occupancies\":[" + String.join(",", occupancies) + "]}";
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
}
