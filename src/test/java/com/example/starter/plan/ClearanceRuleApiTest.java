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
 * 单线区段换向间隔裁决测试（H2 内存库）：合成清空 60 秒场景、同向互斥、
 * 候选内部检查、方向缺失、发布冻结与改签全量相邻对重验。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClearanceRuleApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 合成场景：清空 60 秒 ----------

    @Test
    void syntheticClearance60SecondsBoundary() throws Exception {
        String section = key("SEC");
        putConfig(section, null, 60);

        // 正向 09:00-10:00 发布
        String forward = key("SCH");
        createPlan(forward, occ("G1", section, iso(9, 0, 0), iso(10, 0, 0), "FORWARD"));
        publishPlan(forward);

        // 反向 10:00:59 开始：实际间隔 59 秒 < 60 → 422，返回实际秒数与要求值
        String tooEarly = key("SCH");
        createPlan(tooEarly, occ("G2", section, iso(10, 0, 59), iso(11, 0, 0), "REVERSE"));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", tooEarly)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("CLEARANCE_INSUFFICIENT");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("actualSeconds").asLong()).isEqualTo(59);
        assertThat(detail.get("requiredSeconds").asInt()).isEqualTo(60);

        // 发布失败不写任何占用：计划保持草稿，区段上仍只有正向计划的时隙
        MvcResult after = mvc.perform(get("/api/v1/plans/{key}", tooEarly))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after).get("status").asText()).isEqualTo("DRAFT");
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).hasSize(1);

        // 反向 10:01:00 开始：间隔恰好 60 秒 → 允许
        String exact = key("SCH");
        createPlan(exact, occ("G3", section, iso(10, 1, 0), iso(11, 0, 0), "REVERSE"));
        publishPlan(exact);
    }

    @Test
    void sameDirectionAdjacentNeedsNoClearance() throws Exception {
        String section = key("SEC");
        putConfig(section, null, 600);

        String first = key("SCH");
        createPlan(first, occ("G1", section, iso(9, 0, 0), iso(10, 0, 0), "FORWARD"));
        publishPlan(first);

        // 同向紧邻（10:00:00 开始）只需遵守原互斥 → 允许
        String adjacent = key("SCH");
        createPlan(adjacent, occ("G2", section, iso(10, 0, 0), iso(11, 0, 0), "FORWARD"));
        publishPlan(adjacent);
    }

    @Test
    void overlapOnConfiguredSectionStillRejected() throws Exception {
        String section = key("SEC");
        putConfig(section, null, 60);

        String first = key("SCH");
        createPlan(first, occ("G1", section, iso(9, 0, 0), iso(10, 0, 0), "FORWARD"));
        publishPlan(first);

        // 反向重叠占用仍属互斥冲突
        String overlapping = key("SCH");
        createPlan(overlapping, occ("G2", section, iso(9, 30, 0), iso(10, 30, 0), "REVERSE"));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", overlapping)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("details").get(0).get("type").asText())
                .isEqualTo("SECTION_CONFLICT");
    }

    @Test
    void candidateInternalReversePairChecked() throws Exception {
        String section = key("SEC");
        putConfig(section, null, 60);

        // 同一候选计划内部：正向 09:00-10:00，反向 10:00:30 开始，间隔 30 秒不足
        String scheduleKey = key("SCH");
        createPlan(scheduleKey,
                occ("G1", section, iso(9, 0, 0), iso(10, 0, 0), "FORWARD"),
                occ("G2", section, iso(10, 0, 30), iso(11, 0, 0), "REVERSE"));
        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode detail = read(result).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("CLEARANCE_INSUFFICIENT");
        assertThat(detail.get("actualSeconds").asLong()).isEqualTo(30);
        assertThat(detail.get("requiredSeconds").asInt()).isEqualTo(60);
    }

    @Test
    void missingDirectionOnConfiguredSectionRejected() throws Exception {
        String section = key("SEC");
        // 草稿创建于配置之前，不带方向
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(9, 0, 0), iso(10, 0, 0), null));
        putConfig(section, null, 60);

        MvcResult result = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(result).get("details").get(0).get("type").asText())
                .isEqualTo("DIRECTION_MISSING");
    }

    // ---------- 冻结与历史 ----------

    @Test
    void publishFreezesDirectionAndConfigVersion() throws Exception {
        String section = key("SEC");
        putConfig(section, null, 60);

        String first = key("SCH");
        createPlan(first, occ("G1", section, iso(9, 0, 0), iso(10, 0, 0), "FORWARD"));
        publishPlan(first);
        JsonNode firstDetail = read(mvc.perform(get("/api/v1/plans/{key}", first))
                .andExpect(status().isOk()).andReturn());
        JsonNode firstOcc = firstDetail.get("occupancies").get(0);
        assertThat(firstOcc.get("direction").asText()).isEqualTo("FORWARD");
        assertThat(firstOcc.get("clearanceVersion").asInt()).isEqualTo(1);

        // 配置升级到 90 秒后，新发布冻结新版本；历史计划保持原冻结值
        putConfig(section, 1, 90);
        String second = key("SCH");
        createPlan(second, occ("G2", section, iso(10, 1, 30), iso(11, 0, 0), "REVERSE"));
        publishPlan(second);
        JsonNode secondDetail = read(mvc.perform(get("/api/v1/plans/{key}", second))
                .andExpect(status().isOk()).andReturn());
        assertThat(secondDetail.get("occupancies").get(0).get("clearanceVersion").asInt())
                .isEqualTo(2);

        // 取消只释放占用：历史查询保持原方向与冻结版本
        mvc.perform(post("/api/v1/plans/{key}/cancel", first)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        JsonNode cancelled = read(mvc.perform(get("/api/v1/plans/{key}", first))
                .andExpect(status().isOk()).andReturn());
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        JsonNode cancelledOcc = cancelled.get("occupancies").get(0);
        assertThat(cancelledOcc.get("direction").asText()).isEqualTo("FORWARD");
        assertThat(cancelledOcc.get("clearanceVersion").asInt()).isEqualTo(1);
    }

    // ---------- 改签：移除旧计划后重验全部相邻对 ----------

    @Test
    void rescheduleRevalidatesAllAdjacentPairsAfterRemovingOld() throws Exception {
        String section = key("SEC");
        putConfig(section, null, 60);

        String oldPlan = key("SCH");
        createPlan(oldPlan, occ("G1", section, iso(9, 0, 0), iso(10, 0, 0), "FORWARD"));
        publishPlan(oldPlan);
        String thirdParty = key("SCH");
        createPlan(thirdParty, occ("G2", section, iso(10, 1, 0), iso(11, 0, 0), "REVERSE"));
        publishPlan(thirdParty);

        // 新草稿正向 09:30-10:00:30：移除旧计划后与第三方反向相邻，间隔 30 秒不足 → 改签 422
        String newPlan = key("SCH");
        createPlan(newPlan, occ("G3", section, iso(9, 30, 0), iso(10, 0, 30), "FORWARD"));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newPlan, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode detail = read(rejected).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("CLEARANCE_INSUFFICIENT");
        assertThat(detail.get("actualSeconds").asLong()).isEqualTo(30);

        // 改签未生效：旧计划仍发布，新计划仍草稿
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", oldPlan))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("PUBLISHED");
        assertThat(read(mvc.perform(get("/api/v1/plans/{key}", newPlan))
                .andExpect(status().isOk()).andReturn()).get("status").asText())
                .isEqualTo("DRAFT");

        // 修正新草稿为正向 09:00-10:00（与第三方间隔 60 秒）→ 改签成功
        mvc.perform(put("/api/v1/plans/{key}/occupancies", newPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1,
                                occ("G3", section, iso(9, 0, 0), iso(10, 0, 0), "FORWARD"))))
                .andExpect(status().isOk());
        MvcResult rescheduled = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newPlan, 1, 2)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(rescheduled);
        assertThat(body.get("oldPlan").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(body.get("newPlan").get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(body.get("newPlan").get("occupancies").get(0).get("clearanceVersion").asInt())
                .isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private void putConfig(String section, Integer expectedVersion, int clearanceSeconds)
            throws Exception {
        String versionField = expectedVersion == null ? ""
                : ",\"expectedVersion\":" + expectedVersion;
        mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("REQ") + "\",\"operator\":\"op\""
                                + versionField + ",\"clearanceSeconds\":" + clearanceSeconds + "}"))
                .andExpect(status().isOk());
    }

    private void createPlan(String scheduleKey, String... occupancies) throws Exception {
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"scheduleKey\":\""
                                + scheduleKey + "\",\"opDate\":\"" + DAY + "\",\"occupancies\":["
                                + String.join(",", occupancies) + "]}"))
                .andExpect(status().isCreated());
    }

    private void publishPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日指定时分秒（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute, int second) {
        Instant instant = DAY.atTime(hour, minute, second).atZone(SH).toInstant();
        return instant.toString();
    }

    private static String occ(String train, String section, String startIso, String endIso,
                              String direction) {
        String directionField = direction == null ? "" : ",\"direction\":\"" + direction + "\"";
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\""
                + directionField + "}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\"}";
    }

    private static String updateBody(String requestKey, int expectedVersion,
                                     String... occupancies) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"occupancies\":[" + String.join(",", occupancies) + "]}";
    }

    private static String rescheduleBody(String requestKey, String newScheduleKey,
                                         int expectedOldVersion, int expectedNewVersion) {
        return "{\"requestKey\":\"" + requestKey + "\",\"newScheduleKey\":\"" + newScheduleKey
                + "\",\"expectedOldVersion\":" + expectedOldVersion
                + ",\"expectedNewVersion\":" + expectedNewVersion + "}";
    }
}
