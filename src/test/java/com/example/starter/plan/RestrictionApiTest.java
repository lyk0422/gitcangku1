package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 限速令登记主流程、422 参数校验（区段/时段/速度）与幂等边界测试（H2 内存库）。
 * 各用例使用唯一业务键，共享库内互不干扰。
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

    // ---------- 区段档案 ----------

    @Test
    void registerSectionThenGetAndDuplicateRejected() throws Exception {
        String section = key("SEC");
        MvcResult created = mvc.perform(post("/api/v1/sections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(section, 200, 30)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("sectionId").asText()).isEqualTo(section);
        assertThat(body.get("designSpeedKmh").asInt()).isEqualTo(200);
        assertThat(body.get("minHeadwayMinutes").asInt()).isEqualTo(30);

        MvcResult detail = mvc.perform(get("/api/v1/sections/{id}", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);

        // 重复登记 → 409
        mvc.perform(post("/api/v1/sections").contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(section, 200, 30)))
                .andExpect(status().isConflict());

        // 非法设计速度（低于 10）→ 400 参数校验
        mvc.perform(post("/api/v1/sections").contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(key("SEC"), 5, 30)))
                .andExpect(status().isBadRequest());

        // 未登记区段查询 → 404
        mvc.perform(get("/api/v1/sections/{id}", key("SEC")))
                .andExpect(status().isNotFound());
    }

    // ---------- 限速令登记主流程 ----------

    @Test
    void registerRestrictionThenQueryDetailAndList() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 30);
        String restrictionKey = key("RST");

        MvcResult created = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), restrictionKey, section,
                                iso(8), iso(10), 100, "dispatcher-1")))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("restrictionKey").asText()).isEqualTo(restrictionKey);
        assertThat(body.get("sectionId").asText()).isEqualTo(section);
        assertThat(body.get("maxSpeedKmh").asInt()).isEqualTo(100);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("operator").asText()).isEqualTo("dispatcher-1");
        assertThat(body.get("startUtc").asText()).isEqualTo(iso(8));

        MvcResult detail = mvc.perform(get("/api/v1/restrictions/{key}", restrictionKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);

        MvcResult list = mvc.perform(get("/api/v1/restrictions")
                        .param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(list)).hasSize(1);

        // 重复业务键 → 409
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), restrictionKey, section,
                                iso(8), iso(10), 100, null)))
                .andExpect(status().isConflict());
    }

    // ---------- 422：区段 / 时段 / 速度非法 ----------

    @Test
    void registerRejectsUnknownSection() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), key("SEC"),
                                iso(8), iso(9), 100, null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(result);
        assertThat(error.get("code").asText()).isEqualTo("INVALID_SECTION");
        assertThat(error.get("details").get(0).get("sectionId").asText()).isNotBlank();
    }

    @Test
    void registerRejectsInvalidTimeWindow() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 30);
        // 结束等于开始
        MvcResult equal = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), section,
                                iso(8), iso(8), 100, null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(equal);
        assertThat(error.get("code").asText()).isEqualTo("INVALID_TIME_WINDOW");
        assertThat(error.get("details").get(0).get("actualStartUtc").asText()).isEqualTo(iso(8));
        assertThat(error.get("details").get(0).get("required").asText()).contains("endUtc");
        // 结束早于开始
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), section,
                                iso(9), iso(8), 100, null)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void registerRejectsSpeedOutOfRange() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 30);
        // 低于下限 10
        MvcResult tooLow = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), section,
                                iso(8), iso(9), 5, null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(tooLow);
        assertThat(error.get("code").asText()).isEqualTo("INVALID_SPEED");
        JsonNode detail = error.get("details").get(0);
        assertThat(detail.get("actualSpeedKmh").asInt()).isEqualTo(5);
        assertThat(detail.get("requiredMinSpeedKmh").asInt()).isEqualTo(10);
        assertThat(detail.get("requiredMaxSpeedKmh").asInt()).isEqualTo(300);
        // 高于上限 300
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), section,
                                iso(8), iso(9), 301, null)))
                .andExpect(status().isUnprocessableEntity());
        // 边界值 10 与 300 合法
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), section,
                                iso(8), iso(9), 10, null)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), section,
                                iso(8), iso(9), 300, null)))
                .andExpect(status().isCreated());
    }

    // ---------- 幂等 ----------

    @Test
    void registerIdempotentReplayAndParamConflict() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 30);
        String requestKey = key("REQ");
        String body = registerBody(requestKey, key("RST"), section, iso(8), iso(9), 100, "op-a");

        MvcResult first = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放返回首次结果
        MvcResult replay = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键不同参（操作者不同也属于异参）→ 409
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, key("RST"), section,
                                iso(8), iso(9), 100, "op-b")))
                .andExpect(status().isConflict());
    }

    @Test
    void failedRegistrationDoesNotOccupyKey() throws Exception {
        String section = key("SEC");
        registerSection(section, 200, 30);
        String requestKey = key("REQ");
        String restrictionKey = key("RST");
        // 首次失败（速度越界 422）不占键
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, restrictionKey, section,
                                iso(8), iso(9), 500, null)))
                .andExpect(status().isUnprocessableEntity());
        // 修正后同键成功
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, restrictionKey, section,
                                iso(8), iso(9), 100, null)))
                .andExpect(status().isCreated());
    }

    // ---------- 辅助 ----------

    private void registerSection(String sectionId, int designSpeed, int headway) throws Exception {
        mvc.perform(post("/api/v1/sections").contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody(sectionId, designSpeed, headway)))
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
        return DAY.atTime(hour, 0).atZone(SH).toInstant().toString();
    }

    private static String sectionBody(String sectionId, int designSpeed, int headway) {
        return "{\"sectionId\":\"" + sectionId + "\",\"designSpeedKmh\":" + designSpeed
                + ",\"minHeadwayMinutes\":" + headway + "}";
    }

    private static String registerBody(String requestKey, String restrictionKey, String sectionId,
                                       String startIso, String endIso, int maxSpeed, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"restrictionKey\":\"" + restrictionKey
                + "\",\"sectionId\":\"" + sectionId + "\",\"startUtc\":\"" + startIso
                + "\",\"endUtc\":\"" + endIso + "\",\"maxSpeedKmh\":" + maxSpeed
                + (operator == null ? "" : ",\"operator\":\"" + operator + "\"") + "}";
    }
}
