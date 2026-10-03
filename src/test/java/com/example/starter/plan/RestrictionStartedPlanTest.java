package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 已开始运行的计划不可重排（可控时钟）：限速令仍可登记，但只影响后续发布或改签。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestrictionStartedPlanTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2099, 1, 1);

    /** 运营日 08:30（Asia/Shanghai）对应的 UTC 时刻。 */
    private static final Instant INSIDE_WINDOW = Instant.parse("2099-01-01T00:30:00Z");

    /** 运营日 07:30（Asia/Shanghai）对应的 UTC 时刻，早于计划开始。 */
    private static final Instant BEFORE_WINDOW = Instant.parse("2098-12-31T23:30:00Z");

    @TestConfiguration
    static class ClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(INSIDE_WINDOW, ZoneId.of("UTC"));
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Test
    void startedPlanCannotBeRearrangedButRestrictionStillRegistered() throws Exception {
        String section = key("SEC");
        // 限速令可正常登记（只影响后续发布或改签）
        String restrictionKey = key("RST");
        createRestriction(restrictionKey, section, iso(8, 0), iso(9, 0), 100);
        MvcResult restriction = mvc.perform(get("/api/v1/restrictions/{key}", restrictionKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(restriction).get("status").asText()).isEqualTo("ACTIVE");

        // 计划 08:00-09:00，当前 08:30 已开始运行，违反限速但不可重排
        String scheduleKey = key("SCH");
        createPlan(scheduleKey, occ("G1", section, iso(8, 0), iso(9, 0)));

        MvcResult impact = mvc.perform(get("/api/v1/plans/{key}/restriction-impact", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(impact).get("alreadyStarted").asBoolean()).isTrue();

        String publishKey = key("REQ");
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionJson(publishKey)))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("PLAN_ALREADY_STARTED");
        assertThat(error.get("details").get(0).get("scheduleKey").asText())
                .isEqualTo(scheduleKey);
        assertThat(error.get("details").get(0).get("firstStartUtc").asText())
                .isEqualTo(iso(8, 0));

        // 计划保持草稿，无重排记录
        assertThat(getPlanStatus(scheduleKey)).isEqualTo("DRAFT");
        MvcResult rearranges = mvc.perform(get("/api/v1/plans/{key}/rearranges", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(rearranges)).isEmpty();

        // 时钟回拨到计划开始前：同一计划可发布并整体顺延（失败不占键，同键重试成功）
        clock.setInstant(BEFORE_WINDOW);
        MvcResult published = mvc.perform(post("/api/v1/plans/{key}/publish", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionJson(publishKey)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(published);
        assertThat(body.get("status").asText()).isEqualTo("PUBLISHED");
        // 限速窗口 08:00-09:00，需顺延 60 分钟
        assertThat(body.get("occupancies").get(0).get("startUtc").asText()).isEqualTo(iso(9, 0));
        assertThat(body.get("occupancies").get(0).get("endUtc").asText()).isEqualTo(iso(10, 0));
    }

    // ---------- 辅助 ----------

    private void createPlan(String scheduleKey, String occupancy) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("requestKey", key("REQ"));
        node.put("scheduleKey", scheduleKey);
        node.put("opDate", DAY.toString());
        node.putArray("occupancies").add(objectMapper.readTree(occupancy));
        mvc.perform(post("/api/v1/plans").contentType(MediaType.APPLICATION_JSON)
                        .content(node.toString()))
                .andExpect(status().isCreated());
    }

    private void createRestriction(String restrictionKey, String section,
                                   String startIso, String endIso, int speed) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("requestKey", key("REQ"));
        node.put("restrictionKey", restrictionKey);
        node.put("sectionId", section);
        node.put("startUtc", startIso);
        node.put("endUtc", endIso);
        node.put("maxSpeedKmh", speed);
        node.put("operator", "op-test");
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(node.toString()))
                .andExpect(status().isCreated());
    }

    private String getPlanStatus(String scheduleKey) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/plans/{key}", scheduleKey))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("status").asText();
    }

    private String actionJson(String requestKey) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("requestKey", requestKey);
        node.put("operator", "op-test");
        return node.toString();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String occ(String train, String section, String startIso, String endIso) {
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"}";
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }
}