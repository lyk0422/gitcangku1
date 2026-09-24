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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 夜间跨零点计划对 API 端到端测试（H2 内存库）：夜间窗口校验、双计划联合发布与回滚、
 * 跨日占用判定、计划对取消、改签保持 nightPairKey、幂等与错误语义。
 * 各用例使用唯一 scheduleKey/requestKey/nightPairKey/区段 ID，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanPairApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 23);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 夜间窗口参数校验 ----------

    @Test
    void overnightDraftValidation() throws Exception {
        // 夜间草稿缺 nightPairKey → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"), DAY, true, null,
                                occ("G1", key("SEC"), iso(22), iso(6, 1)))))
                .andExpect(status().isBadRequest());
        // nightPairKey 空串 → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + key("SCH") + "\",\"opDate\":\"" + DAY
                                + "\",\"overnight\":true,\"nightPairKey\":\"  \",\"occupancies\":["
                                + occ("G1", key("SEC"), iso(22), iso(6, 1)) + "]}"))
                .andExpect(status().isBadRequest());
        // 占用早于 22:00 → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"), DAY, true, key("NP"),
                                occ("G1", key("SEC"), iso(21), iso(6, 1)))))
                .andExpect(status().isBadRequest());
        // 占用晚于次日 06:00 → 400
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"), DAY, true, key("NP"),
                                occ("G1", key("SEC"), iso(22), iso(7, 1)))))
                .andExpect(status().isBadRequest());
        // 恰为 22:00 → 次日 06:00（8 小时整）合法
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"), DAY, true, key("NP"),
                                occ("G1", key("SEC"), iso(22), iso(6, 1)))))
                .andExpect(status().isCreated());
        // 窗口内不跨零点的占用（23:00-23:30）合法
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"), DAY, true, key("NP"),
                                occ("G1", key("SEC"), iso(23), isoMinutes(23, 30)))))
                .andExpect(status().isCreated());
        // 非夜间草稿声明 nightPairKey（计划对次日成员）合法
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"), DAY.plusDays(1), false,
                                key("NP"), occ("G1", key("SEC"), iso(6, 1), iso(8, 1)))))
                .andExpect(status().isCreated());
    }

    @Test
    void overnightUpdateValidatesNightWindow() throws Exception {
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, DAY, true, key("NP"), occ("G1", key("SEC"), iso(22), iso(6, 1)));
        // 替换为窗口外占用 → 400，版本不变
        mvc.perform(put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                occ("G1", key("SEC"), iso(20), iso(6, 1)))))
                .andExpect(status().isBadRequest());
        // 替换为窗口内占用 → 版本加一
        mvc.perform(put("/api/v1/plans/{key}/occupancies", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                occ("G1", key("SEC"), iso(23), iso(5, 1)))))
                .andExpect(status().isOk());
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(detail);
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("overnight").asBoolean()).isTrue();
    }

    // ---------- 联合发布主流程与跨日查询 ----------

    @Test
    void jointPublishMainFlowAndCrossDayQuery() throws Exception {
        String section = key("SEC");
        String pairKey = key("NP");
        String first = key("SCH");
        String second = key("SCH");
        createPlan(first, DAY, true, pairKey, occ("G1", section, iso(22), iso(6, 1)));
        createPlan(second, DAY.plusDays(1), false, pairKey,
                occ("G2", section, iso(6, 1), iso(8, 1)));

        MvcResult published = mvc.perform(post("/api/v1/plan-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey, 1, 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(published);
        assertThat(body.get("nightPairKey").asText()).isEqualTo(pairKey);
        assertThat(body.get("firstPlan").get("scheduleKey").asText()).isEqualTo(first);
        assertThat(body.get("firstPlan").get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(body.get("firstPlan").get("overnight").asBoolean()).isTrue();
        assertThat(body.get("secondPlan").get("scheduleKey").asText()).isEqualTo(second);
        assertThat(body.get("secondPlan").get("status").asText()).isEqualTo("PUBLISHED");

        // 计划对明细：两张均已发布
        MvcResult pair = mvc.perform(get("/api/v1/plan-pairs/{key}", pairKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(pair).get("firstPlan").get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(read(pair).get("secondPlan").get("status").asText()).isEqualTo("PUBLISHED");

        // 当日查询：跨零点占用落在当日的部分（22:00 → 24:00）
        MvcResult daySlots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode dayList = read(daySlots);
        assertThat(dayList).hasSize(1);
        assertThat(dayList.get(0).get("scheduleKey").asText()).isEqualTo(first);
        assertThat(dayList.get(0).get("startUtc").asText()).isEqualTo(iso(22));
        assertThat(dayList.get(0).get("endUtc").asText()).isEqualTo(iso(0, 1));

        // 次日查询：跨零点占用落在次日的部分（00:00 → 06:00）+ 次日计划自身占用
        MvcResult nextSlots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.plusDays(1).toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode nextList = read(nextSlots);
        assertThat(nextList).hasSize(2);
        assertThat(nextList.get(0).get("scheduleKey").asText()).isEqualTo(first);
        assertThat(nextList.get(0).get("startUtc").asText()).isEqualTo(iso(0, 1));
        assertThat(nextList.get(0).get("endUtc").asText()).isEqualTo(iso(6, 1));
        assertThat(nextList.get(1).get("scheduleKey").asText()).isEqualTo(second);
        assertThat(nextList.get(1).get("startUtc").asText()).isEqualTo(iso(6, 1));
        assertThat(nextList.get(1).get("endUtc").asText()).isEqualTo(iso(8, 1));
    }

    // ---------- 联合发布回滚 ----------

    @Test
    void jointPublishRollsBackBothOnCrossDaySectionConflict() throws Exception {
        String section = key("SEC");
        // 次日凌晨 02:00-03:00 已有其他已发布计划
        String existing = key("SCH");
        createPlan(existing, DAY.plusDays(1), false, null,
                occ("G9", section, iso(2, 1), iso(3, 1)));
        publishPlan(existing, key("REQ"));

        String pairKey = key("NP");
        String first = key("SCH");
        String second = key("SCH");
        String publishKey = key("REQ");
        createPlan(first, DAY, true, pairKey, occ("G1", section, iso(22), iso(6, 1)));
        createPlan(second, DAY.plusDays(1), false, pairKey,
                occ("G2", section, iso(6, 1), iso(8, 1)));

        // 跨零点占用次日部分与已发布计划冲突 → 422，携带冲突区段、冲突计划与运营日
        MvcResult conflict = mvc.perform(post("/api/v1/plan-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(publishKey, pairKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(conflict);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("scheduleKey").asText()).isEqualTo(first);
        assertThat(detail.get("opDate").asText()).isEqualTo(DAY.toString());
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(existing);
        assertThat(detail.get("conflictingOpDate").asText())
                .isEqualTo(DAY.plusDays(1).toString());

        // 两张都保持草稿，计划对记录未写入
        assertThat(planStatus(first)).isEqualTo("DRAFT");
        assertThat(planStatus(second)).isEqualTo("DRAFT");
        mvc.perform(get("/api/v1/plan-pairs/{key}", pairKey))
                .andExpect(status().isNotFound());

        // 失败不占键：修正首计划占用避开冲突后，同一 requestKey 重发成功
        mvc.perform(put("/api/v1/plans/{key}/occupancies", first)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                occ("G1", section, iso(22), iso(1, 1)))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plan-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(publishKey, pairKey, 2, 1)))
                .andExpect(status().isOk());
        assertThat(planStatus(first)).isEqualTo("PUBLISHED");
        assertThat(planStatus(second)).isEqualTo("PUBLISHED");
    }

    @Test
    void jointPublishRejectsInterMemberAndTrainOverlap() throws Exception {
        // 成员之间跨零点部分重叠（次日成员 05:00 开始 < 夜间成员 06:00 结束）→ 422
        String section = key("SEC");
        String pairKey = key("NP");
        String first = key("SCH");
        String second = key("SCH");
        createPlan(first, DAY, true, pairKey, occ("G1", section, iso(22), iso(6, 1)));
        createPlan(second, DAY.plusDays(1), false, pairKey,
                occ("G2", section, iso(5, 1), iso(7, 1)));
        MvcResult result = mvc.perform(post("/api/v1/plan-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode detail = read(result).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("SECTION_CONFLICT");
        assertThat(detail.get("conflictingScheduleKey").asText()).isEqualTo(second);
        assertThat(planStatus(first)).isEqualTo("DRAFT");
        assertThat(planStatus(second)).isEqualTo("DRAFT");

        // 首计划内部同一列车重叠 → 422，两张都保持草稿
        String pairKey2 = key("NP");
        String first2 = key("SCH");
        String second2 = key("SCH");
        String section2 = key("SEC");
        createPlan(first2, DAY, true, pairKey2,
                occ("G1", section2, iso(22), iso(6, 1)),
                occ("G1", section2, iso(23), iso(5, 1)));
        createPlan(second2, DAY.plusDays(1), false, pairKey2,
                occ("G2", section2, iso(6, 1), iso(8, 1)));
        MvcResult result2 = mvc.perform(post("/api/v1/plan-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey2, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result2).get("details").get(0).get("type").asText())
                .isEqualTo("TRAIN_OVERLAP");
        assertThat(planStatus(first2)).isEqualTo("DRAFT");
        assertThat(planStatus(second2)).isEqualTo("DRAFT");
    }

    @Test
    void jointPublishRejectsVersionStateAndShapeConflicts() throws Exception {
        // 版本不符 → 409，两张保持草稿
        String pairKey = key("NP");
        String first = key("SCH");
        String second = key("SCH");
        createPlan(first, DAY, true, pairKey, occ("G1", key("SEC"), iso(22), iso(6, 1)));
        createPlan(second, DAY.plusDays(1), false, pairKey,
                occ("G2", key("SEC"), iso(6, 1), iso(8, 1)));
        mvc.perform(post("/api/v1/plan-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey, 9, 1)))
                .andExpect(status().isConflict());
        assertThat(planStatus(first)).isEqualTo("DRAFT");
        assertThat(planStatus(second)).isEqualTo("DRAFT");

        // 不存在的计划对 → 404
        mvc.perform(post("/api/v1/plan-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), key("NP"), 1, 1)))
                .andExpect(status().isNotFound());

        // 仅一张草稿 → 409
        String singlePair = key("NP");
        createPlan(key("SCH"), DAY, true, singlePair,
                occ("G1", key("SEC"), iso(22), iso(6, 1)));
        mvc.perform(post("/api/v1/plan-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), singlePair, 1, 1)))
                .andExpect(status().isConflict());

        // 运营日不相邻 → 409
        String gapPair = key("NP");
        createPlan(key("SCH"), DAY, true, gapPair,
                occ("G1", key("SEC"), iso(22), iso(6, 1)));
        createPlan(key("SCH"), DAY.plusDays(2), false, gapPair,
                occ("G2", key("SEC"), iso(6, 2), iso(8, 2)));
        mvc.perform(post("/api/v1/plan-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), gapPair, 1, 1)))
                .andExpect(status().isConflict());

        // 首计划非夜间 → 409
        String dayPair = key("NP");
        createPlan(key("SCH"), DAY, false, dayPair,
                occ("G1", key("SEC"), iso(8), iso(9)));
        createPlan(key("SCH"), DAY.plusDays(1), false, dayPair,
                occ("G2", key("SEC"), iso(6, 1), iso(8, 1)));
        mvc.perform(post("/api/v1/plan-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), dayPair, 1, 1)))
                .andExpect(status().isConflict());

        // 计划对成员不可走单计划发布 → 409
        mvc.perform(post("/api/v1/plans/{key}/publish", first)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());
    }

    @Test
    void nightPairKeyUniquenessAndDraftCap() throws Exception {
        // 同一 nightPairKey 第三张草稿 → 409
        String pairKey = key("NP");
        createPlan(key("SCH"), DAY, true, pairKey, occ("G1", key("SEC"), iso(22), iso(6, 1)));
        createPlan(key("SCH"), DAY.plusDays(1), false, pairKey,
                occ("G2", key("SEC"), iso(6, 1), iso(8, 1)));
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), key("SCH"), DAY.plusDays(1), false,
                                pairKey, occ("G3", key("SEC"), iso(9, 1), iso(10, 1)))))
                .andExpect(status().isConflict());

        // 联合发布成功后 nightPairKey 全局唯一：新草稿可声明同键，但联合发布 → 409
        String reused = key("NP");
        String first = key("SCH");
        String second = key("SCH");
        createPlan(first, DAY, true, reused, occ("G1", key("SEC"), iso(22), iso(6, 1)));
        createPlan(second, DAY.plusDays(1), false, reused,
                occ("G2", key("SEC"), iso(6, 1), iso(8, 1)));
        publishPair(reused, 1, 1);

        String first2 = key("SCH");
        String second2 = key("SCH");
        createPlan(first2, DAY.plusDays(2), true, reused,
                occ("G3", key("SEC"), iso(22, 2), iso(6, 3)));
        createPlan(second2, DAY.plusDays(3), false, reused,
                occ("G4", key("SEC"), iso(6, 3), iso(8, 3)));
        mvc.perform(post("/api/v1/plan-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), reused, 1, 1)))
                .andExpect(status().isConflict());
        assertThat(planStatus(first2)).isEqualTo("DRAFT");
        assertThat(planStatus(second2)).isEqualTo("DRAFT");
    }

    // ---------- 幂等 ----------

    @Test
    void pairPublishIdempotentReplayAndParamConflict() throws Exception {
        String pairKey = key("NP");
        String first = key("SCH");
        String second = key("SCH");
        createPlan(first, DAY, true, pairKey, occ("G1", key("SEC"), iso(22), iso(6, 1)));
        createPlan(second, DAY.plusDays(1), false, pairKey,
                occ("G2", key("SEC"), iso(6, 1), iso(8, 1)));
        String requestKey = key("REQ");
        String body = pairPublishBody(requestKey, pairKey, 1, 1);

        MvcResult firstResult = mvc.perform(post("/api/v1/plan-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放返回首次快照
        MvcResult replay = mvc.perform(post("/api/v1/plan-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(firstResult));
        // 同键不同参 → 409
        mvc.perform(post("/api/v1/plan-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(requestKey, pairKey, 2, 1)))
                .andExpect(status().isConflict());
    }

    // ---------- 取消 ----------

    @Test
    void cancelPairMemberReleasesOnlyOwnSlots() throws Exception {
        String section = key("SEC");
        String pairKey = key("NP");
        String first = key("SCH");
        String second = key("SCH");
        createPlan(first, DAY, true, pairKey, occ("G1", section, iso(22), iso(6, 1)));
        createPlan(second, DAY.plusDays(1), false, pairKey,
                occ("G2", section, iso(6, 1), iso(8, 1)));
        publishPair(pairKey, 1, 1);

        // 取消首计划：仅其自身占用释放，次日计划保持已发布
        mvc.perform(post("/api/v1/plans/{key}/cancel", first)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        assertThat(planStatus(first)).isEqualTo("CANCELLED");
        assertThat(planStatus(second)).isEqualTo("PUBLISHED");

        // 当日时隙已释放；次日仅剩次日计划自身占用（跨零点部分随之释放）
        MvcResult daySlots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(daySlots)).isEmpty();
        MvcResult nextSlots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.plusDays(1).toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode nextList = read(nextSlots);
        assertThat(nextList).hasSize(1);
        assertThat(nextList.get(0).get("scheduleKey").asText()).isEqualTo(second);
        assertThat(nextList.get(0).get("startUtc").asText()).isEqualTo(iso(6, 1));
    }

    @Test
    void cancelPairEndpointCancelsPublishedMembers() throws Exception {
        String section = key("SEC");
        String pairKey = key("NP");
        String first = key("SCH");
        String second = key("SCH");
        createPlan(first, DAY, true, pairKey, occ("G1", section, iso(22), iso(6, 1)));
        createPlan(second, DAY.plusDays(1), false, pairKey,
                occ("G2", section, iso(6, 1), iso(8, 1)));
        publishPair(pairKey, 1, 1);

        String cancelKey = key("REQ");
        MvcResult cancelled = mvc.perform(post("/api/v1/plan-pairs/{key}/cancel", pairKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(cancelKey)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(cancelled);
        assertThat(body.get("firstPlan").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("secondPlan").get("status").asText()).isEqualTo("CANCELLED");

        // 同键重放返回首次快照
        MvcResult replay = mvc.perform(post("/api/v1/plan-pairs/{key}/cancel", pairKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(cancelKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(body);

        // 两个运营日的时隙均已释放
        MvcResult daySlots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(daySlots)).isEmpty();
        MvcResult nextSlots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.plusDays(1).toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(nextSlots)).isEmpty();

        // 无可取消成员 → 409；不存在的计划对 → 404
        mvc.perform(post("/api/v1/plan-pairs/{key}/cancel", pairKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/plan-pairs/{key}/cancel", key("NP"))
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plan-pairs/{key}", key("NP")))
                .andExpect(status().isNotFound());
    }

    // ---------- 改签保持 nightPairKey ----------

    @Test
    void rescheduleNightPlanKeepsNightPairKey() throws Exception {
        String section = key("SEC");
        String pairKey = key("NP");
        String first = key("SCH");
        String second = key("SCH");
        createPlan(first, DAY, true, pairKey, occ("G1", section, iso(22), iso(6, 1)));
        createPlan(second, DAY.plusDays(1), false, pairKey,
                occ("G2", section, iso(6, 1), iso(8, 1)));
        publishPair(pairKey, 1, 1);

        // 新草稿 nightPairKey 不一致 → 409，旧计划保持已发布
        String mismatched = key("SCH");
        createPlan(mismatched, DAY, true, key("NP"), occ("G3", section, iso(22), iso(6, 1)));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", first)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), mismatched, 1, 1)))
                .andExpect(status().isConflict());
        assertThat(planStatus(first)).isEqualTo("PUBLISHED");
        assertThat(planStatus(mismatched)).isEqualTo("DRAFT");

        // 同一 nightPairKey 的新草稿 → 改签成功：旧计划取消，新计划发布
        String successor = key("SCH");
        createPlan(successor, DAY, true, pairKey, occ("G4", section, iso(23), iso(5, 1)));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/reschedule", first)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), successor, 1, 1)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("oldPlan").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("newPlan").get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(body.get("newPlan").get("nightPairKey").asText()).isEqualTo(pairKey);
        assertThat(body.get("newPlan").get("overnight").asBoolean()).isTrue();

        // 改签后跨零点时隙切换为新计划占用（23:00 → 次日 05:00）
        MvcResult daySlots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode dayList = read(daySlots);
        assertThat(dayList).hasSize(1);
        assertThat(dayList.get(0).get("scheduleKey").asText()).isEqualTo(successor);
        assertThat(dayList.get(0).get("startUtc").asText()).isEqualTo(iso(23));
    }

    // ---------- 辅助 ----------

    private void createPlan(String scheduleKey, LocalDate opDate, boolean overnight,
                            String nightPairKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(key("REQ"), scheduleKey, opDate, overnight,
                                nightPairKey, occupancies)))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(requestKey)))
                .andExpect(status().isOk());
    }

    private void publishPair(String pairKey, int firstVersion, int secondVersion)
            throws Exception {
        mvc.perform(post("/api/v1/plan-pairs/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pairPublishBody(key("REQ"), pairKey, firstVersion, secondVersion)))
                .andExpect(status().isOk());
    }

    private String planStatus(String scheduleKey) throws Exception {
        MvcResult detail = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(detail).get("status").asText();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour) {
        return iso(hour, 0);
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String isoMinutes(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    /** 运营日加 dayOffset 天后 hour 点（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int dayOffset) {
        Instant instant = DAY.plusDays(dayOffset).atTime(hour, 0).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    private static String createBody(String requestKey, String scheduleKey, LocalDate opDate,
                                     boolean overnight, String nightPairKey,
                                     String... occupancies) {
        StringBuilder sb = new StringBuilder("{\"requestKey\":\"").append(requestKey)
                .append("\",\"scheduleKey\":\"").append(scheduleKey)
                .append("\",\"opDate\":\"").append(opDate).append('"');
        if (overnight) {
            sb.append(",\"overnight\":true");
        }
        if (nightPairKey != null) {
            sb.append(",\"nightPairKey\":\"").append(nightPairKey).append('"');
        }
        sb.append(",\"occupancies\":[").append(String.join(",", occupancies)).append("]}");
        return sb.toString();
    }

    private static String updateBody(String requestKey, int expectedVersion,
                                     String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"occupancies\":[" + String.join(",", occupancies) + "]}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String pairPublishBody(String requestKey, String nightPairKey,
                                          int expectedFirstVersion, int expectedSecondVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"nightPairKey\":\"" + nightPairKey
                + "\",\"expectedFirstVersion\":" + expectedFirstVersion
                + ",\"expectedSecondVersion\":" + expectedSecondVersion + "}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }
}
