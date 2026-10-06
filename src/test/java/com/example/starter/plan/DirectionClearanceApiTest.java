package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 单线换向间隔端到端测试（H2 内存库）：合成清空 60 秒场景、同向相邻、
 * 候选内部相邻对、方向预校验、发布冻结与取消后历史保持、改签全部相邻对验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DirectionClearanceApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void syntheticClearanceScenario() throws Exception {
        String section = key("SEC");
        createConfig(section, 60);
        // 正向占用 09:00-10:00 发布
        String up = key("SCH");
        createPlan(up, occ("G1", section, iso(9, 0, 0), iso(10, 0, 0), "UP"));
        publishPlan(up);

        // 反向 10:00:59 开始：间隔 59 秒不足 60 → 422，返回实际秒数与要求值
        String tooEarly = key("SCH");
        createPlan(tooEarly, occ("G2", section, iso(10, 0, 59), iso(11, 0, 0), "DOWN"));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", tooEarly)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("SLOT_CONFLICT");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("CLEARANCE_CONFLICT");
        assertThat(detail.get("sectionId").asText()).isEqualTo(section);
        assertThat(detail.get("actualGapSeconds").asLong()).isEqualTo(59);
        assertThat(detail.get("requiredClearanceSeconds").asInt()).isEqualTo(60);

        // 发布失败不写任何占用：计划保持草稿，区段上仍只有正向计划生效
        MvcResult after = mvc.perform(get("/api/v1/plans/{key}", tooEarly))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after).get("status").asText()).isEqualTo("DRAFT");
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).hasSize(1);

        // 反向 10:01:00 开始：间隔恰为 60 秒 → 允许
        String exact = key("SCH");
        createPlan(exact, occ("G3", section, iso(10, 1, 0), iso(11, 0, 0), "DOWN"));
        publishPlan(exact);
        MvcResult slotsAfter = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slotsAfter)).hasSize(2);
    }

    @Test
    void sameDirectionAdjacentAllowed() throws Exception {
        String section = key("SEC");
        createConfig(section, 60);
        String first = key("SCH");
        createPlan(first, occ("G1", section, iso(9, 0, 0), iso(10, 0, 0), "UP"));
        publishPlan(first);
        // 同向紧邻（间隔 0）只遵守互斥，左闭右开相邻合法
        String second = key("SCH");
        createPlan(second, occ("G2", section, iso(10, 0, 0), iso(11, 0, 0), "UP"));
        publishPlan(second);
    }

    @Test
    void candidateInternalPairsChecked() throws Exception {
        String section = key("SEC");
        createConfig(section, 60);
        // 候选内部反向相邻间隔 30 秒不足 → 422
        String internalReverse = key("SCH");
        createPlan(internalReverse,
                occ("G1", section, iso(12, 0, 0), iso(12, 30, 0), "UP"),
                occ("G2", section, iso(12, 30, 30), iso(13, 0, 0), "DOWN"));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/publish", internalReverse)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode detail = read(rejected).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("CLEARANCE_CONFLICT");
        assertThat(detail.get("actualGapSeconds").asLong()).isEqualTo(30);

        // 候选内部同区段重叠（不同列车）→ 422 互斥
        String internalOverlap = key("SCH");
        createPlan(internalOverlap,
                occ("G3", section, iso(14, 0, 0), iso(15, 0, 0), "UP"),
                occ("G4", section, iso(14, 30, 0), iso(15, 30, 0), "UP"));
        MvcResult overlap = mvc.perform(post("/api/v1/plans/{key}/publish", internalOverlap)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(overlap).get("details").get(0).get("type").asText())
                .isEqualTo("SECTION_CONFLICT");
    }

    @Test
    void directionRequiredAndUnknownRejected() throws Exception {
        String section = key("SEC");
        createConfig(section, 60);
        // 有配置区段缺方向 → 422
        String noDirection = key("SCH");
        createPlan(noDirection, occ("G1", section, iso(9, 0, 0), iso(10, 0, 0), null));
        MvcResult missing = mvc.perform(post("/api/v1/plans/{key}/publish", noDirection)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(missing).get("details").get(0).get("type").asText())
                .isEqualTo("DIRECTION_REQUIRED");

        // 方向不属于配置正/反方向 → 422
        String unknown = key("SCH");
        createPlan(unknown, occ("G2", section, iso(9, 0, 0), iso(10, 0, 0), "SIDEWAYS"));
        MvcResult unknownResult = mvc.perform(post("/api/v1/plans/{key}/publish", unknown)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"))))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(unknownResult).get("details").get(0).get("type").asText())
                .isEqualTo("DIRECTION_UNKNOWN");
    }

    @Test
    void publishFreezesDirectionAndConfigVersionAndCancelKeepsHistory() throws Exception {
        String section = key("SEC");
        createConfig(section, 60);
        String plan = key("SCH");
        createPlan(plan, occ("G1", section, iso(9, 0, 0), iso(10, 0, 0), "UP"));
        publishPlan(plan);

        // 发布成功冻结方向与配置版本
        MvcResult published = mvc.perform(get("/api/v1/plans/{key}", plan))
                .andExpect(status().isOk()).andReturn();
        JsonNode occupancy = read(published).get("occupancies").get(0);
        assertThat(occupancy.get("direction").asText()).isEqualTo("UP");
        assertThat(occupancy.get("appliedConfigVersion").asInt()).isEqualTo(1);

        // 配置更新到版本 2 后，已发布占用的冻结版本保持 1
        mvc.perform(put("/api/v1/section-configs/{section}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("REQ") + "\",\"operator\":\"op-1\","
                                + "\"expectedVersion\":1,\"directionForward\":\"UP\","
                                + "\"directionBackward\":\"DOWN\",\"clearanceSeconds\":120}"))
                .andExpect(status().isOk());
        MvcResult afterUpdate = mvc.perform(get("/api/v1/plans/{key}", plan))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(afterUpdate).get("occupancies").get(0)
                .get("appliedConfigVersion").asInt()).isEqualTo(1);

        // 取消只释放占用：时隙查询为空，历史占用方向与冻结版本保持原值
        mvc.perform(post("/api/v1/plans/{key}/cancel", plan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
        MvcResult cancelled = mvc.perform(get("/api/v1/plans/{key}", plan))
                .andExpect(status().isOk()).andReturn();
        JsonNode cancelledBody = read(cancelled);
        assertThat(cancelledBody.get("status").asText()).isEqualTo("CANCELLED");
        JsonNode historical = cancelledBody.get("occupancies").get(0);
        assertThat(historical.get("direction").asText()).isEqualTo("UP");
        assertThat(historical.get("appliedConfigVersion").asInt()).isEqualTo(1);
        MvcResult slots = mvc.perform(get("/api/v1/published-slots")
                        .param("date", DAY.toString()).param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(slots)).isEmpty();
    }

    @Test
    void rescheduleValidatesAllAdjacentPairsAfterRemovingOldPlan() throws Exception {
        String section = key("SEC");
        createConfig(section, 60);
        String p1 = key("SCH");
        createPlan(p1, occ("G1", section, iso(8, 0, 0), iso(8, 30, 0), "UP"));
        publishPlan(p1);
        String oldPlan = key("SCH");
        createPlan(oldPlan, occ("G2", section, iso(8, 31, 0), iso(9, 0, 0), "UP"));
        publishPlan(oldPlan);
        String p3 = key("SCH");
        createPlan(p3, occ("G3", section, iso(10, 0, 0), iso(10, 30, 0), "DOWN"));
        publishPlan(p3);

        // 新计划反向 08:30:30 开始：移除旧计划后与 P1 反向相邻，间隔 30 秒不足 → 422
        String newPlan = key("SCH");
        createPlan(newPlan, occ("G4", section, iso(8, 30, 30), iso(9, 0, 0), "DOWN"));
        MvcResult rejected = mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newPlan, 1, 1)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode detail = read(rejected).get("details").get(0);
        assertThat(detail.get("type").asText()).isEqualTo("CLEARANCE_CONFLICT");
        assertThat(detail.get("actualGapSeconds").asLong()).isEqualTo(30);
        // 改签失败整体回滚：旧计划仍发布，新计划仍草稿
        MvcResult oldAfter = mvc.perform(get("/api/v1/plans/{key}", oldPlan))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(oldAfter).get("status").asText()).isEqualTo("PUBLISHED");
        MvcResult newAfter = mvc.perform(get("/api/v1/plans/{key}", newPlan))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(newAfter).get("status").asText()).isEqualTo("DRAFT");

        // 修正为 08:31:00（间隔 60 秒）后改签成功，新计划冻结配置版本 1
        mvc.perform(put("/api/v1/plans/{key}/occupancies", newPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"expectedVersion\":1,"
                                + "\"occupancies\":["
                                + occ("G4", section, iso(8, 31, 0), iso(9, 0, 0), "DOWN") + "]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/plans/{key}/reschedule", oldPlan)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), newPlan, 1, 2)))
                .andExpect(status().isOk());
        MvcResult rescheduled = mvc.perform(get("/api/v1/plans/{key}", newPlan))
                .andExpect(status().isOk()).andReturn();
        JsonNode newBody = read(rescheduled);
        assertThat(newBody.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(newBody.get("occupancies").get(0).get("appliedConfigVersion").asInt())
                .isEqualTo(1);

        // 改签候选内部相邻对同样检查：新草稿自带反向间隔不足 → 422，旧计划不受影响
        String internalBad = key("SCH");
        createPlan(internalBad,
                occ("G5", section, iso(12, 0, 0), iso(12, 30, 0), "UP"),
                occ("G6", section, iso(12, 30, 30), iso(13, 0, 0), "DOWN"));
        mvc.perform(post("/api/v1/plans/{key}/reschedule", p1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rescheduleBody(key("REQ"), internalBad, 1, 1)))
                .andExpect(status().isUnprocessableEntity());
        MvcResult p1After = mvc.perform(get("/api/v1/plans/{key}", p1))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(p1After).get("status").asText()).isEqualTo("PUBLISHED");
    }

    // ---------- 辅助 ----------

    private void createConfig(String section, int clearanceSeconds) throws Exception {
        mvc.perform(post("/api/v1/section-configs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("REQ") + "\",\"operator\":\"op-1\","
                                + "\"sectionId\":\"" + section + "\",\"directionForward\":\"UP\","
                                + "\"directionBackward\":\"DOWN\",\"clearanceSeconds\":"
                                + clearanceSeconds + "}"))
                .andExpect(status().isCreated());
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"))))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour, int minute, int second) {
        return DAY.atTime(hour, minute, second).atZone(SH).toInstant().toString();
    }

    private static String occ(String train, String section, String startIso, String endIso,
                              String direction) {
        String dir = direction == null ? "" : ",\"direction\":\"" + direction + "\"";
        return "{\"trainNo\":\"" + train + "\",\"sectionId\":\"" + section
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso + "\"" + dir + "}";
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
