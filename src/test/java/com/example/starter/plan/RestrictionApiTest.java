package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * 气象限速令 API 主流程、失败分支与幂等边界的端到端测试（H2 内存库）。
 * 各用例使用唯一 restrictionKey/requestKey/区段 ID，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestrictionApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2099, 1, 1);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 主流程 ----------

    @Test
    void createThenQueryDetailAndList() throws Exception {
        String restrictionKey = key("RST");
        String section = key("SEC");
        MvcResult created = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(key("REQ"), restrictionKey, section,
                                iso(8, 0), iso(10, 30), 120)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = read(created);
        assertThat(body.get("restrictionKey").asText()).isEqualTo(restrictionKey);
        assertThat(body.get("sectionId").asText()).isEqualTo(section);
        assertThat(body.get("maxSpeedKmh").asInt()).isEqualTo(120);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("startUtc").asText()).isEqualTo(iso(8, 0));

        MvcResult detail = mvc.perform(get("/api/v1/restrictions/{key}", restrictionKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail)).isEqualTo(body);

        MvcResult list = mvc.perform(get("/api/v1/restrictions")
                        .param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(list)).hasSize(1);
        assertThat(read(list).get(0).get("restrictionKey").asText()).isEqualTo(restrictionKey);
    }

    @Test
    void reviseIncrementsVersionAndRevokeKeepsHistory() throws Exception {
        String restrictionKey = key("RST");
        createRestriction(key("REQ"), restrictionKey, key("SEC"), iso(8, 0), iso(9, 0), 100);

        MvcResult revised = mvc.perform(put("/api/v1/restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(key("REQ"), 1, iso(8, 30), iso(11, 0), 80)))
                .andExpect(status().isOk()).andReturn();
        JsonNode revisedBody = read(revised);
        assertThat(revisedBody.get("version").asInt()).isEqualTo(2);
        assertThat(revisedBody.get("maxSpeedKmh").asInt()).isEqualTo(80);
        assertThat(revisedBody.get("startUtc").asText()).isEqualTo(iso(8, 30));

        MvcResult revoked = mvc.perform(post("/api/v1/restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionJson(key("REQ"))))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(revoked).get("status").asText()).isEqualTo("REVOKED");

        // 撤销后历史保留，仍可查询明细
        MvcResult detail = mvc.perform(get("/api/v1/restrictions/{key}", restrictionKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(detail).get("status").asText()).isEqualTo("REVOKED");
        assertThat(read(detail).get("version").asInt()).isEqualTo(2);
    }

    // ---------- 失败分支：422 参数校验 ----------

    @Test
    void invalidSpeedReturns422WithActualAndRequired() throws Exception {
        String section = key("SEC");
        MvcResult tooLow = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(key("REQ"), key("RST"), section,
                                iso(8, 0), iso(9, 0), 9)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode lowBody = read(tooLow);
        assertThat(lowBody.get("code").asText()).isEqualTo("INVALID_SPEED_RANGE");
        assertThat(lowBody.get("details").get(0).get("actual").asInt()).isEqualTo(9);
        assertThat(lowBody.get("details").get(0).get("requiredMin").asInt()).isEqualTo(10);
        assertThat(lowBody.get("details").get(0).get("requiredMax").asInt()).isEqualTo(300);

        MvcResult tooHigh = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(key("REQ"), key("RST"), section,
                                iso(8, 0), iso(9, 0), 301)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(tooHigh).get("details").get(0).get("actual").asInt()).isEqualTo(301);

        MvcResult missing = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(key("REQ"), key("RST"), section,
                                iso(8, 0), iso(9, 0), null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(missing).get("code").asText()).isEqualTo("INVALID_SPEED_RANGE");
    }

    @Test
    void invalidSectionOrWindowReturns422() throws Exception {
        MvcResult blankSection = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(key("REQ"), key("RST"), "  ",
                                iso(8, 0), iso(9, 0), 100)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(blankSection).get("code").asText()).isEqualTo("INVALID_SECTION");

        MvcResult reversed = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(key("REQ"), key("RST"), key("SEC"),
                                iso(9, 0), iso(8, 0), 100)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode reversedBody = read(reversed);
        assertThat(reversedBody.get("code").asText()).isEqualTo("INVALID_TIME_WINDOW");
        assertThat(reversedBody.get("details").get(0).get("actualStartUtc").asText())
                .isEqualTo(iso(9, 0));

        MvcResult equal = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(key("REQ"), key("RST"), key("SEC"),
                                iso(8, 0), iso(8, 0), 100)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(equal).get("code").asText()).isEqualTo("INVALID_TIME_WINDOW");
    }

    // ---------- 失败分支：状态与版本冲突 ----------

    @Test
    void reviseAndRevokeStateConflicts() throws Exception {
        String restrictionKey = key("RST");
        createRestriction(key("REQ"), restrictionKey, key("SEC"), iso(8, 0), iso(9, 0), 100);

        mvc.perform(put("/api/v1/restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(key("REQ"), 5, iso(8, 0), iso(10, 0), 90)))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(read(result).get("code").asText())
                        .isEqualTo("VERSION_CONFLICT"));

        mvc.perform(post("/api/v1/restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionJson(key("REQ"))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionJson(key("REQ"))))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(read(result).get("code").asText())
                        .isEqualTo("RESTRICTION_STATE_CONFLICT"));
        mvc.perform(put("/api/v1/restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(key("REQ"), 1, iso(8, 0), iso(10, 0), 90)))
                .andExpect(status().isConflict());
    }

    @Test
    void notFoundReturns404() throws Exception {
        mvc.perform(get("/api/v1/restrictions/{key}", key("RST")))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/restrictions/{key}", key("RST"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseJson(key("REQ"), 1, iso(8, 0), iso(9, 0), 90)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/restrictions/{key}/revoke", key("RST"))
                        .contentType(MediaType.APPLICATION_JSON).content(actionJson(key("REQ"))))
                .andExpect(status().isNotFound());
    }
    // ---------- 幂等边界 ----------

    @Test
    void idempotentReplayAndKeyReuse() throws Exception {
        String restrictionKey = key("RST");
        String requestKey = key("REQ");
        String section = key("SEC");
        MvcResult first = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(requestKey, restrictionKey, section,
                                iso(8, 0), iso(9, 0), 100)))
                .andExpect(status().isCreated()).andReturn();

        // 同键同参重放首次完整结果
        MvcResult replay = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(requestKey, restrictionKey, section,
                                iso(8, 0), iso(9, 0), 100)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键异参 409
        mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(requestKey, key("RST"), section,
                                iso(8, 0), iso(9, 0), 200)))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(read(result).get("code").asText())
                        .isEqualTo("IDEMPOTENT_KEY_REUSED"));
    }

    @Test
    void failureDoesNotOccupyRequestKey() throws Exception {
        String requestKey = key("REQ");
        // 失败（422）不占键
        mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(requestKey, key("RST"), key("SEC"),
                                iso(8, 0), iso(9, 0), 500)))
                .andExpect(status().isUnprocessableEntity());
        Integer recorded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_key = ?",
                Integer.class, requestKey);
        assertThat(recorded).isZero();

        // 修正参数后同键可成功
        mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(requestKey, key("RST"), key("SEC"),
                                iso(8, 0), iso(9, 0), 150)))
                .andExpect(status().isCreated());
    }

    @Test
    void readsDoNotChangeState() throws Exception {
        String restrictionKey = key("RST");
        String section = key("SEC");
        createRestriction(key("REQ"), restrictionKey, section, iso(8, 0), iso(9, 0), 100);

        MvcResult first = mvc.perform(get("/api/v1/restrictions/{key}", restrictionKey))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(get("/api/v1/restrictions").param("sectionId", section))
                .andExpect(status().isOk());
        MvcResult second = mvc.perform(get("/api/v1/restrictions/{key}", restrictionKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(second)).isEqualTo(read(first));
    }

    // ---------- 辅助 ----------

    private void createRestriction(String requestKey, String restrictionKey, String section,
                                   String startIso, String endIso, int speed) throws Exception {
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(requestKey, restrictionKey, section,
                                startIso, endIso, speed)))
                .andExpect(status().isCreated());
    }

    private String createJson(String requestKey, String restrictionKey, String section,
                              String startIso, String endIso, Integer speed) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("requestKey", requestKey);
        node.put("restrictionKey", restrictionKey);
        if (section != null) {
            node.put("sectionId", section);
        }
        if (startIso != null) {
            node.put("startUtc", startIso);
        }
        if (endIso != null) {
            node.put("endUtc", endIso);
        }
        if (speed != null) {
            node.put("maxSpeedKmh", speed);
        }
        node.put("operator", "op-test");
        return node.toString();
    }

    private String reviseJson(String requestKey, int expectedVersion,
                              String startIso, String endIso, int speed) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("requestKey", requestKey);
        node.put("expectedVersion", expectedVersion);
        node.put("startUtc", startIso);
        node.put("endUtc", endIso);
        node.put("maxSpeedKmh", speed);
        node.put("operator", "op-test");
        return node.toString();
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

    /** 运营日当日 hour:minute（Asia/Shanghai）的 UTC 时刻。 */
    private static String iso(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant().toString();
    }
}