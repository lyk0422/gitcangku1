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
 * 限速令登记/修订/撤销/查询/诊断 API 的主流程、失败分支与幂等边界测试（H2 内存库）。
 * 各用例使用唯一 restrictionKey/requestKey/区段 ID，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestrictionApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程 ----------

    @Test
    void registerThenQueryDetailAndHistory() throws Exception {
        String section = key("SEC");
        String restrictionKey = key("RST");
        MvcResult created = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), restrictionKey, section,
                                iso(9), iso(10), 60, "dispatcher-1")))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("restrictionKey").asText()).isEqualTo(restrictionKey);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("sectionId").asText()).isEqualTo(section);
        assertThat(body.get("startUtc").asText()).isEqualTo(iso(9));
        assertThat(body.get("endUtc").asText()).isEqualTo(iso(10));
        assertThat(body.get("maxSpeedKmh").asInt()).isEqualTo(60);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("operator").asText()).isEqualTo("dispatcher-1");
        assertThat(body.get("revokedAt").isNull()).isTrue();

        MvcResult detail = mvc.perform(get("/api/v1/restrictions/{key}", restrictionKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);

        MvcResult list = mvc.perform(get("/api/v1/restrictions")
                        .param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(list);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("restrictionKey").asText()).isEqualTo(restrictionKey);
    }

    @Test
    void amendIncrementsVersionAndRevokeKeepsHistory() throws Exception {
        String section = key("SEC");
        String restrictionKey = key("RST");
        register(restrictionKey, section, iso(9), iso(10), 60);

        MvcResult amended = mvc.perform(put("/api/v1/restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendBody(key("REQ"), 1, section, iso(9), iso(11), 80, "op-2")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(amended);
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("maxSpeedKmh").asInt()).isEqualTo(80);
        assertThat(body.get("endUtc").asText()).isEqualTo(iso(11));

        // 版本不匹配 → 409
        mvc.perform(put("/api/v1/restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendBody(key("REQ"), 1, section, iso(9), iso(11), 80, "op-2")))
                .andExpect(status().isConflict());

        MvcResult revoked = mvc.perform(post("/api/v1/restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody(key("REQ"), "op-3")))
                .andExpect(status().isOk()).andReturn();
        JsonNode revokedBody = read(revoked);
        assertThat(revokedBody.get("status").asText()).isEqualTo("REVOKED");
        assertThat(revokedBody.get("revokedAt").isNull()).isFalse();

        // 重复撤销 → 409；已撤销不可修订 → 409；历史明细仍可查
        mvc.perform(post("/api/v1/restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody(key("REQ"), "op-3")))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendBody(key("REQ"), 2, section, iso(9), iso(11), 80, "op-2")))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/restrictions/{key}", restrictionKey))
                .andExpect(status().isOk());
    }

    // ---------- 参数非法 422 ----------

    @Test
    void registerRejectsInvalidParamsWith422() throws Exception {
        // 速度越界（低于 10 / 高于 300）
        assertInvalid(registerBody(key("REQ"), key("RST"), key("SEC"), iso(9), iso(10), 9, "op"),
                "maxSpeedKmh");
        assertInvalid(registerBody(key("REQ"), key("RST"), key("SEC"), iso(9), iso(10), 301, "op"),
                "maxSpeedKmh");
        // 区段为空
        assertInvalid(registerBody(key("REQ"), key("RST"), "", iso(9), iso(10), 60, "op"),
                "sectionId");
        // 时段非法：结束等于开始、结束早于开始
        assertInvalid(registerBody(key("REQ"), key("RST"), key("SEC"), iso(9), iso(9), 60, "op"),
                "endUtc");
        assertInvalid(registerBody(key("REQ"), key("RST"), key("SEC"), iso(10), iso(9), 60, "op"),
                "endUtc");
    }

    private void assertInvalid(String body, String field) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("INVALID_RESTRICTION");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("field").asText()).isEqualTo(field);
        assertThat(detail.has("actual")).isTrue();
        assertThat(detail.has("required")).isTrue();
    }
    // ---------- 不存在 404 ----------

    @Test
    void missingRestrictionReturns404() throws Exception {
        String missing = key("RST");
        mvc.perform(get("/api/v1/restrictions/{key}", missing)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/restrictions/{key}", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendBody(key("REQ"), 1, key("SEC"), iso(9), iso(10), 60, "op")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/restrictions/{key}/revoke", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody(key("REQ"), "op")))
                .andExpect(status().isNotFound());
    }

    // ---------- 幂等 ----------

    @Test
    void registerIdempotentReplayAndParamConflict() throws Exception {
        String restrictionKey = key("RST");
        String requestKey = key("REQ");
        String body = registerBody(requestKey, restrictionKey, key("SEC"), iso(9), iso(10), 60, "op");

        MvcResult first = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, key("RST"), key("SEC"),
                                iso(9), iso(10), 60, "op")))
                .andExpect(status().isConflict());
    }

    @Test
    void registerFailureDoesNotConsumeRequestKey() throws Exception {
        String requestKey = key("REQ");
        String restrictionKey = key("RST");
        String section = key("SEC");
        // 首次因速度越界 422，不占键
        mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, restrictionKey, section,
                                iso(9), iso(10), 5, "op")))
                .andExpect(status().isUnprocessableEntity());
        // 修正后同键重发成功
        mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, restrictionKey, section,
                                iso(9), iso(10), 50, "op")))
                .andExpect(status().isCreated());
    }

    // ---------- 诊断：叠加以最低速度为准 ----------

    @Test
    void effectiveSpeedDiagnosticsUsesLowestStackedSpeed() throws Exception {
        String section = key("SEC");
        register(key("RST"), section, iso(9), iso(11), 120);
        register(key("RST"), section, iso(10), iso(12), 60);
        register(key("RST"), key("SEC"), iso(10), iso(12), 30);

        // 09:30 仅 120 生效
        MvcResult at0930 = mvc.perform(get("/api/v1/restrictions/effective")
                        .param("sectionId", section).param("at", iso(9, 30)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body1 = read(at0930);
        assertThat(body1.get("effectiveSpeedKmh").asInt()).isEqualTo(120);
        assertThat(body1.get("activeRestrictions")).hasSize(1);

        // 10:30 两条叠加以最低 60 为准
        MvcResult at1030 = mvc.perform(get("/api/v1/restrictions/effective")
                        .param("sectionId", section).param("at", iso(10, 30)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body2 = read(at1030);
        assertThat(body2.get("effectiveSpeedKmh").asInt()).isEqualTo(60);
        assertThat(body2.get("activeRestrictions")).hasSize(2);

        // 左闭右开：10:00 恰为第二条开始时刻，计入
        MvcResult at1000 = mvc.perform(get("/api/v1/restrictions/effective")
                        .param("sectionId", section).param("at", iso(10)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(at1000).get("effectiveSpeedKmh").asInt()).isEqualTo(60);

        // 11:00 恰为第一条结束时刻（不含），仅第二条生效
        MvcResult at1100 = mvc.perform(get("/api/v1/restrictions/effective")
                        .param("sectionId", section).param("at", iso(11)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body4 = read(at1100);
        assertThat(body4.get("effectiveSpeedKmh").asInt()).isEqualTo(60);
        assertThat(body4.get("activeRestrictions")).hasSize(1);

        // 无生效限速令：effectiveSpeedKmh 为 null
        MvcResult none = mvc.perform(get("/api/v1/restrictions/effective")
                        .param("sectionId", section).param("at", iso(20)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body5 = read(none);
        assertThat(body5.get("effectiveSpeedKmh").isNull()).isTrue();
        assertThat(body5.get("activeRestrictions")).isEmpty();
    }

    // ---------- 辅助 ----------

    private void register(String restrictionKey, String section, String startIso, String endIso,
                          int speed) throws Exception {
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), restrictionKey, section,
                                startIso, endIso, speed, "op")))
                .andExpect(status().isCreated());
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
    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }

    private static String registerBody(String requestKey, String restrictionKey, String section,
                                       String startIso, String endIso, int speed, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"restrictionKey\":\"" + restrictionKey
                + "\",\"sectionId\":\"" + section + "\",\"startUtc\":\"" + startIso
                + "\",\"endUtc\":\"" + endIso + "\",\"maxSpeedKmh\":" + speed
                + ",\"operator\":\"" + operator + "\"}";
    }

    private static String amendBody(String requestKey, int expectedVersion, String section,
                                    String startIso, String endIso, int speed, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"expectedVersion\":" + expectedVersion
                + ",\"sectionId\":\"" + section + "\",\"startUtc\":\"" + startIso
                + "\",\"endUtc\":\"" + endIso + "\",\"maxSpeedKmh\":" + speed
                + ",\"operator\":\"" + operator + "\"}";
    }

    private static String revokeBody(String requestKey, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator + "\"}";
    }
}