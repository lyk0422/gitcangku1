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
 * 单线区段换向配置 API 测试：创建/更新/查询、版本与范围校验、
 * 已发布集合合法性（旧无方向须先取消、增大间隔非法 409）与幂等边界（H2 内存库）。
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

    // ---------- 主流程 ----------

    @Test
    void createThenGetAndUpdateIncrementsVersion() throws Exception {
        String section = key("SEC");
        MvcResult created = mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "dispatcher-1", null, 60)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("sectionId").asText()).isEqualTo(section);
        assertThat(body.get("clearanceSeconds").asInt()).isEqualTo(60);
        assertThat(body.get("version").asInt()).isEqualTo(1);

        MvcResult queried = mvc.perform(get("/api/v1/sections/{id}/clearance", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(queried)).isEqualTo(body);

        MvcResult updated = mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "dispatcher-1", 1, 120)))
                .andExpect(status().isOk()).andReturn();
        JsonNode updatedBody = read(updated);
        assertThat(updatedBody.get("clearanceSeconds").asInt()).isEqualTo(120);
        assertThat(updatedBody.get("version").asInt()).isEqualTo(2);
    }

    @Test
    void acceptsBoundaryClearanceValues() throws Exception {
        mvc.perform(put("/api/v1/sections/{id}/clearance", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op", null, 1)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/sections/{id}/clearance", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op", null, 600)))
                .andExpect(status().isOk());
    }

    // ---------- 参数非法 400 ----------

    @Test
    void rejectsOutOfRangeAndMissingParams() throws Exception {
        // 0 与 601 超出 1~600 范围
        mvc.perform(put("/api/v1/sections/{id}/clearance", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op", null, 0)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/sections/{id}/clearance", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op", null, 601)))
                .andExpect(status().isBadRequest());
        // 缺 operator
        mvc.perform(put("/api/v1/sections/{id}/clearance", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + key("REQ") + "\",\"clearanceSeconds\":60}"))
                .andExpect(status().isBadRequest());
        // 创建时携带 expectedVersion
        mvc.perform(put("/api/v1/sections/{id}/clearance", key("SEC"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op", 1, 60)))
                .andExpect(status().isBadRequest());
        // 更新时缺 expectedVersion
        String section = key("SEC");
        mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op", null, 60)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op", null, 90)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 不存在 404 / 版本冲突 409 ----------

    @Test
    void missingConfigReturns404() throws Exception {
        mvc.perform(get("/api/v1/sections/{id}/clearance", key("SEC")))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateRejectsVersionMismatch() throws Exception {
        String section = key("SEC");
        mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op", null, 60)))
                .andExpect(status().isOk());
        MvcResult result = mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op", 5, 90)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(result).get("code").asText()).isEqualTo("VERSION_CONFLICT");
        // 版本未被错误推进
        MvcResult queried = mvc.perform(get("/api/v1/sections/{id}/clearance", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(queried).get("version").asInt()).isEqualTo(1);
    }

    // ---------- 已发布集合合法性 ----------

    @Test
    void directionlessPublishedOccupancyMustBeCancelledFirst() throws Exception {
        String section = key("SEC");
        // 旧无方向占用：配置前发布，不带 direction
        String legacy = key("SCH");
        createPlan(legacy, occ("G1", section, iso(8, 0, 0), iso(9, 0, 0), null));
        publishPlan(legacy);

        MvcResult rejected = mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op", null, 60)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(rejected).get("code").asText()).isEqualTo("DIRECTIONLESS_PUBLISHED");

        // 取消旧计划后配置成功
        cancelPlan(legacy);
        mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op", null, 60)))
                .andExpect(status().isOk());
    }

    @Test
    void increaseMakingPublishedSetIllegalRejectedWithActualAndRequired() throws Exception {
        String section = key("SEC");
        // 先发布带方向的占用：正向 09:00-10:00，反向 10:01:00-11:00（间隔 60 秒）
        String forward = key("SCH");
        createPlan(forward, occ("G1", section, iso(9, 0, 0), iso(10, 0, 0), "FORWARD"));
        publishPlan(forward);
        String reverse = key("SCH");
        createPlan(reverse, occ("G2", section, iso(10, 1, 0), iso(11, 0, 0), "REVERSE"));
        publishPlan(reverse);

        // 创建配置 60 秒：已发布集合恰好满足
        mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op", null, 60)))
                .andExpect(status().isOk());

        // 增大到 61 秒：实际间隔 60 秒不足 → 409 携带实际与要求值
        MvcResult rejected = mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op", 1, 61)))
                .andExpect(status().isConflict()).andReturn();
        JsonNode error = read(rejected);
        assertThat(error.get("code").asText()).isEqualTo("CLEARANCE_VIOLATION");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("actualSeconds").asLong()).isEqualTo(60);
        assertThat(detail.get("requiredSeconds").asInt()).isEqualTo(61);

        // 减小到 30 秒：已发布集合仍合法 → 成功
        mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op", 1, 30)))
                .andExpect(status().isOk());
    }

    // ---------- 幂等 ----------

    @Test
    void idempotentReplayAndParamConflict() throws Exception {
        String section = key("SEC");
        String requestId = key("REQ");
        String body = configBody(requestId, "dispatcher-9", null, 60);

        MvcResult first = mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放返回首次完整响应，版本不重复推进
        MvcResult replay = mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        assertThat(read(replay).get("version").asInt()).isEqualTo(1);

        // 同键不同参（间隔）→ 409
        mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestId, "dispatcher-9", null, 90)))
                .andExpect(status().isConflict());
        // 同键不同操作者 → 409
        mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestId, "dispatcher-10", null, 60)))
                .andExpect(status().isConflict());
    }

    @Test
    void failedUpdateDoesNotConsumeRequestId() throws Exception {
        String section = key("SEC");
        mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(key("REQ"), "op", null, 60)))
                .andExpect(status().isOk());
        // 版本冲突的失败请求不占键，修正参数后同键可成功
        String requestId = key("REQ");
        mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestId, "op", 5, 90)))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/sections/{id}/clearance", section)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configBody(requestId, "op", 1, 90)))
                .andExpect(status().isOk());
    }

    // ---------- 辅助 ----------

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

    private static String configBody(String requestId, String operator, Integer expectedVersion,
                                     int clearanceSeconds) {
        String versionField = expectedVersion == null ? ""
                : ",\"expectedVersion\":" + expectedVersion;
        return "{\"requestId\":\"" + requestId + "\",\"operator\":\"" + operator + "\""
                + versionField + ",\"clearanceSeconds\":" + clearanceSeconds + "}";
    }
}
