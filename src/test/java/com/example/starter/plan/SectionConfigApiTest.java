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
 * 单线区段换向配置 API 测试：创建/更新/查询、版本与参数校验、
 * 已发布集合合法性 409、幂等重放与失败不占键（H2 内存库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SectionConfigApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createThenGetAndMissingReturns404() throws Exception {
        String section = key("SEC");
        MvcResult created = mvc.perform(post("/api/v1/section-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), section, 60)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("sectionId").asText()).isEqualTo(section);
        assertThat(body.get("directionForward").asText()).isEqualTo("UP");
        assertThat(body.get("directionBackward").asText()).isEqualTo("DOWN");
        assertThat(body.get("clearanceSeconds").asInt()).isEqualTo(60);
        assertThat(body.get("version").asInt()).isEqualTo(1);

        MvcResult fetched = mvc.perform(get("/api/v1/section-configs/{section}", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(fetched)).isEqualTo(body);

        mvc.perform(get("/api/v1/section-configs/{section}", key("SEC")))
                .andExpect(status().isNotFound());
    }

    @Test
    void createRejectsInvalidParams() throws Exception {
        // 清空间隔下界之外
        mvc.perform(post("/api/v1/section-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), key("SEC"), 0)))
                .andExpect(status().isBadRequest());
        // 清空间隔上界之外
        mvc.perform(post("/api/v1/section-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), key("SEC"), 601)))
                .andExpect(status().isBadRequest());
        // 边界值 1 与 600 合法
        mvc.perform(post("/api/v1/section-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), key("SEC"), 1)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/section-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), key("SEC"), 600)))
                .andExpect(status().isCreated());
        // 正反方向相同
        mvc.perform(post("/api/v1/section-configs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("REQ") + "\",\"operator\":\"op-1\","
                                + "\"sectionId\":\"" + key("SEC") + "\",\"directionForward\":\"UP\","
                                + "\"directionBackward\":\"UP\",\"clearanceSeconds\":60}"))
                .andExpect(status().isBadRequest());
        // 缺 requestId
        mvc.perform(post("/api/v1/section-configs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"op-1\",\"sectionId\":\"" + key("SEC")
                                + "\",\"directionForward\":\"UP\",\"directionBackward\":\"DOWN\","
                                + "\"clearanceSeconds\":60}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateSectionConfigRejected() throws Exception {
        String section = key("SEC");
        createConfig(section, 60);
        mvc.perform(post("/api/v1/section-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), section, 30)))
                .andExpect(status().isConflict());
    }

    @Test
    void updateRequiresExpectedVersionAndIncrements() throws Exception {
        String section = key("SEC");
        createConfig(section, 60);

        // 版本不匹配
        mvc.perform(put("/api/v1/section-configs/{section}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 5, 30)))
                .andExpect(status().isConflict());
        // 不存在
        mvc.perform(put("/api/v1/section-configs/{section}", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, 30)))
                .andExpect(status().isNotFound());

        MvcResult updated = mvc.perform(put("/api/v1/section-configs/{section}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, 30)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(updated);
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("clearanceSeconds").asInt()).isEqualTo(30);
    }

    @Test
    void configIdempotentReplayAndParamConflict() throws Exception {
        String section = key("SEC");
        String requestId = key("REQ");
        String body = configBody(requestId, section, 60);
        MvcResult first = mvc.perform(post("/api/v1/section-configs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放返回首次完整响应
        MvcResult replay = mvc.perform(post("/api/v1/section-configs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        // 同键异参 → 409
        mvc.perform(post("/api/v1/section-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestId, key("SEC"), 60)))
                .andExpect(status().isConflict());

        // 更新幂等：同键同参重放不重复加版本
        String updateId = key("REQ");
        String updateBody = updateBody(updateId, 1, 45);
        MvcResult updated = mvc.perform(put("/api/v1/section-configs/{section}", section)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult updateReplay = mvc.perform(put("/api/v1/section-configs/{section}", section)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(updateReplay)).isEqualTo(read(updated));
        assertThat(read(updateReplay).get("version").asInt()).isEqualTo(2);
        // 同键异参（不同期望版本）→ 409
        mvc.perform(put("/api/v1/section-configs/{section}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(updateId, 2, 45)))
                .andExpect(status().isConflict());
    }

    @Test
    void directionlessPublishedOccupancyBlocksConfigUntilCancelled() throws Exception {
        String section = key("SEC");
        // 旧无方向占用已发布：不能猜方向，配置创建须 409
        String plan = key("SCH");
        createPlan(plan, occ("G1", section, iso(8), iso(9), null));
        publishPlan(plan);

        String requestId = key("REQ");
        MvcResult rejected = mvc.perform(post("/api/v1/section-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestId, section, 60)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(rejected).get("code").asText()).isEqualTo("OCCUPANCY_DIRECTION_UNKNOWN");

        // 失败不占键：取消旧计划后同 requestId 创建成功
        cancelPlan(plan);
        mvc.perform(post("/api/v1/section-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestId, section, 60)))
                .andExpect(status().isCreated());
    }

    @Test
    void increaseClearanceThatBreaksPublishedSetRejected() throws Exception {
        String section = key("SEC");
        createConfig(section, 60);
        String up = key("SCH");
        createPlan(up, occ("G1", section, iso(9), iso(10), "UP"));
        publishPlan(up);
        String down = key("SCH");
        createPlan(down, occ("G2", section, iso(10, 1, 0), iso(11), "DOWN"));
        publishPlan(down);

        // 增大到 61 秒：已发布相邻反向对间隔 60 秒不满足 → 409
        MvcResult rejected = mvc.perform(put("/api/v1/section-configs/{section}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, 61)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(rejected).get("code").asText()).isEqualTo("CONFIG_SET_INVALID");
        // 配置保持版本 1、间隔 60
        MvcResult fetched = mvc.perform(get("/api/v1/section-configs/{section}", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(fetched).get("version").asInt()).isEqualTo(1);
        assertThat(read(fetched).get("clearanceSeconds").asInt()).isEqualTo(60);

        // 等于当前间隔（60）与减小（30）均合法
        mvc.perform(put("/api/v1/section-configs/{section}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 1, 60)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/section-configs/{section}", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(key("REQ"), 2, 30)))
                .andExpect(status().isOk());
    }

    // ---------- 辅助 ----------

    private void createConfig(String section, int clearanceSeconds) throws Exception {
        mvc.perform(post("/api/v1/section-configs").contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), section, clearanceSeconds)))
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
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isOk());
    }

    private void cancelPlan(String scheduleKey) throws Exception {
        mvc.perform(post("/api/v1/plans/{key}/cancel", scheduleKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour) {
        return iso(hour, 0, 0);
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

    private static String configBody(String requestId, String section, int clearanceSeconds) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"op-1\",\"sectionId\":\""
                + section + "\",\"directionForward\":\"UP\",\"directionBackward\":\"DOWN\","
                + "\"clearanceSeconds\":" + clearanceSeconds + "}";
    }

    private static String updateBody(String requestId, int expectedVersion, int clearanceSeconds) {
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"op-1\",\"expectedVersion\":"
                + expectedVersion + ",\"directionForward\":\"UP\",\"directionBackward\":\"DOWN\","
                + "\"clearanceSeconds\":" + clearanceSeconds + "}";
    }
}
