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
 * 限速令撤销、修订与生效限速诊断测试（H2 内存库）。
 * 验证叠加限速取最低速度、左闭右开边界、撤销/修订状态机与历史列表稳定性。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestrictionLifecycleApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void revokeTransitionsStateAndKeepsHistory() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 30);
        String restrictionKey = key("RST");
        registerRestriction(restrictionKey, section, iso(8), iso(9), 100);

        // 撤销 → REVOKED，版本不变
        String revokeKey = key("REQ");
        MvcResult revoked = mvc.perform(post("/api/v1/restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(revokeKey, "op-r")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(revoked);
        assertThat(body.get("status").asText()).isEqualTo("REVOKED");
        assertThat(body.get("version").asInt()).isEqualTo(1);

        // 同键重放返回首次结果
        MvcResult replay = mvc.perform(post("/api/v1/restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(revokeKey, "op-r")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(body);

        // 新键重复撤销 → 409；撤销后不再出现在生效诊断中
        mvc.perform(post("/api/v1/restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isConflict());
        MvcResult limit = mvc.perform(get("/api/v1/sections/{id}/speed-limit", section)
                        .param("at", iso(8, 30)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(limit).get("effectiveSpeedKmh").isNull()).isTrue();

        // 历史列表仍保留已撤销记录
        MvcResult list = mvc.perform(get("/api/v1/restrictions").param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(list)).hasSize(1);
        assertThat(read(list).get(0).get("status").asText()).isEqualTo("REVOKED");

        // 撤销不存在的限速令 → 404
        mvc.perform(post("/api/v1/restrictions/{key}/revoke", key("RST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void reviseIncrementsVersionAndValidates() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 30);
        String restrictionKey = key("RST");
        registerRestriction(restrictionKey, section, iso(8), iso(9), 100);

        // 修订：时段与速度更新，版本加一
        MvcResult revised = mvc.perform(put("/api/v1/restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(key("REQ"), iso(9), iso(11), 80, "op-v")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(revised);
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("maxSpeedKmh").asInt()).isEqualTo(80);
        assertThat(body.get("startUtc").asText()).isEqualTo(iso(9));

        // 修订速度越界 → 422；时段非法 → 422
        mvc.perform(put("/api/v1/restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(key("REQ"), iso(9), iso(11), 400, null)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(key("REQ"), iso(11), iso(9), 80, null)))
                .andExpect(status().isUnprocessableEntity());

        // 修订不存在的限速令 → 404
        mvc.perform(put("/api/v1/restrictions/{key}", key("RST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(key("REQ"), iso(9), iso(11), 80, null)))
                .andExpect(status().isNotFound());

        // 已撤销不可修订 → 409
        String revokedKey = key("RST");
        registerRestriction(revokedKey, section, iso(8), iso(9), 100);
        mvc.perform(post("/api/v1/restrictions/{key}/revoke", revokedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionBody(key("REQ"), null)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/restrictions/{key}", revokedKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(key("REQ"), iso(9), iso(11), 80, null)))
                .andExpect(status().isConflict());
    }

    @Test
    void effectiveLimitTakesLowestSpeedAndHalfOpenBoundary() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 30);
        registerRestriction(key("RST"), section, iso(8), iso(10), 120);
        registerRestriction(key("RST"), section, iso(9), iso(11), 80);

        // 08:30 仅第一条生效 → 120
        assertThat(effectiveSpeed(section, iso(8, 30))).isEqualTo(120);
        // 09:30 两条叠加 → 最低 80
        assertThat(effectiveSpeed(section, iso(9, 30))).isEqualTo(80);
        // 10:00 恰为第一条结束（右开）→ 仅第二条生效 → 80
        assertThat(effectiveSpeed(section, iso(10, 0))).isEqualTo(80);
        // 11:00 恰为第二条结束（右开）→ 无生效限速 → null
        MvcResult none = mvc.perform(get("/api/v1/sections/{id}/speed-limit", section)
                        .param("at", iso(11, 0)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(none).get("effectiveSpeedKmh").isNull()).isTrue();
        assertThat(read(none).get("restrictions")).isEmpty();

        // 未登记区段 → 404
        mvc.perform(get("/api/v1/sections/{id}/speed-limit", key("SEC"))
                        .param("at", iso(8, 0)))
                .andExpect(status().isNotFound());
    }

    // ---------- 辅助 ----------

    private int effectiveSpeed(String section, String atIso) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/sections/{id}/speed-limit", section)
                        .param("at", atIso))
                .andExpect(status().isOk()).andReturn();
        return read(result).get("effectiveSpeedKmh").asInt();
    }

    private void registerSection(String sectionId, int designSpeed, int headway) throws Exception {
        mvc.perform(post("/api/v1/sections").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sectionId\":\"" + sectionId + "\",\"designSpeedKmh\":"
                                + designSpeed + ",\"minHeadwayMinutes\":" + headway + "}"))
                .andExpect(status().isCreated());
    }

    private void registerRestriction(String restrictionKey, String sectionId,
                                     String startIso, String endIso, int maxSpeed) throws Exception {
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestKey\":\"" + key("REQ") + "\",\"restrictionKey\":\""
                                + restrictionKey + "\",\"sectionId\":\"" + sectionId
                                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso
                                + "\",\"maxSpeedKmh\":" + maxSpeed + "}"))
                .andExpect(status().isCreated());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String actionBody(String requestKey, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\""
                + (operator == null ? "" : ",\"operator\":\"" + operator + "\"") + "}";
    }

    private static String reviseBody(String requestKey, String startIso, String endIso,
                                     int maxSpeed, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"startUtc\":\"" + startIso
                + "\",\"endUtc\":\"" + endIso + "\",\"maxSpeedKmh\":" + maxSpeed
                + (operator == null ? "" : ",\"operator\":\"" + operator + "\"") + "}";
    }
}
