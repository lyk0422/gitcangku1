package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 气象限速令登记/撤销/查询 API 测试（H2 内存库）：主流程、422 参数分支、
 * 404/409 状态分支与幂等边界。各用例使用唯一区段 ID 与 requestKey，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RestrictionApiTest {

    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 主流程 ----------

    @Test
    void registerThenListAndFilterBySection() throws Exception {
        String section = key("SEC");
        MvcResult created = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), section, iso(8), iso(10), 120, "dispatcher-1")))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("id").asLong()).isPositive();
        assertThat(body.get("sectionId").asText()).isEqualTo(section);
        assertThat(body.get("startUtc").asText()).isEqualTo(iso(8));
        assertThat(body.get("endUtc").asText()).isEqualTo(iso(10));
        assertThat(body.get("maxSpeedKmh").asInt()).isEqualTo(120);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("operator").asText()).isEqualTo("dispatcher-1");
        assertThat(body.get("revokedAt").isNull()).isTrue();

        // 明细查询：按区段过滤命中，其他区段不命中
        JsonNode list = read(mvc.perform(get("/api/v1/restrictions")
                        .param("sectionId", section))
                .andExpect(status().isOk()).andReturn());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(body.get("id").asLong());
        JsonNode empty = read(mvc.perform(get("/api/v1/restrictions")
                        .param("sectionId", key("SEC")))
                .andExpect(status().isOk()).andReturn());
        assertThat(empty).isEmpty();
    }

    @Test
    void overlappingRestrictionsOnSameSectionAreAllowed() throws Exception {
        String section = key("SEC");
        register(section, iso(8), iso(12), 200);
        register(section, iso(8), iso(12), 80);

        JsonNode list = read(mvc.perform(get("/api/v1/restrictions")
                        .param("sectionId", section))
                .andExpect(status().isOk()).andReturn());
        assertThat(list).hasSize(2);
    }

    @Test
    void boundarySpeedsTenAndThreeHundredAreAccepted() throws Exception {
        String section = key("SEC");
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), section, iso(8), iso(9), 10, null)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), section, iso(9), iso(10), 300, null)))
                .andExpect(status().isCreated());
    }

    // ---------- 参数非法 422 ----------

    @Test
    void registerRejectsInvalidSectionIntervalAndSpeed() throws Exception {
        String section = key("SEC");
        // 区段为空 → 422 INVALID_SECTION
        MvcResult blankSection = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), " ", iso(8), iso(9), 100, null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(blankSection).get("code").asText()).isEqualTo("INVALID_SECTION");

        // 时段结束不晚于开始 → 422 INVALID_INTERVAL
        MvcResult badInterval = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), section, iso(9), iso(9), 100, null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(badInterval).get("code").asText()).isEqualTo("INVALID_INTERVAL");

        // 速度越界 → 422 INVALID_SPEED
        MvcResult tooSlow = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), section, iso(8), iso(9), 9, null)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(tooSlow).get("code").asText()).isEqualTo("INVALID_SPEED");
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), section, iso(8), iso(9), 301, null)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 撤销 ----------

    @Test
    void revokeMarksStatusAndKeepsRowQueryable() throws Exception {
        String section = key("SEC");
        long id = register(section, iso(8), iso(9), 100);

        MvcResult revoked = mvc.perform(post("/api/v1/restrictions/{id}/revoke", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody(key("REQ"), "dispatcher-2")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(revoked);
        assertThat(body.get("status").asText()).isEqualTo("REVOKED");
        assertThat(body.get("revokedAt").isNull()).isFalse();

        // 默认查询不含已撤销；includeRevoked=true 可见历史行
        JsonNode activeOnly = read(mvc.perform(get("/api/v1/restrictions")
                        .param("sectionId", section))
                .andExpect(status().isOk()).andReturn());
        assertThat(activeOnly).isEmpty();
        JsonNode all = read(mvc.perform(get("/api/v1/restrictions")
                        .param("sectionId", section).param("includeRevoked", "true"))
                .andExpect(status().isOk()).andReturn());
        assertThat(all).hasSize(1);
        assertThat(all.get(0).get("status").asText()).isEqualTo("REVOKED");
    }

    @Test
    void revokeMissingReturns404AndDoubleRevokeReturns409() throws Exception {
        mvc.perform(post("/api/v1/restrictions/{id}/revoke", 999_999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody(key("REQ"), null)))
                .andExpect(status().isNotFound());

        long id = register(key("SEC"), iso(8), iso(9), 100);
        revoke(id, key("REQ"));
        MvcResult second = mvc.perform(post("/api/v1/restrictions/{id}/revoke", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody(key("REQ"), null)))
                .andExpect(status().isConflict()).andReturn();
        assertThat(read(second).get("code").asText()).isEqualTo("RESTRICTION_STATE_CONFLICT");
    }

    // ---------- 幂等 ----------

    @Test
    void registerIdempotentReplayAndParamConflict() throws Exception {
        String section = key("SEC");
        String requestKey = key("REQ");
        String body = registerBody(requestKey, section, iso(8), iso(9), 100, "op");
        MvcResult first = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();

        // 同键同参重放返回首次结果（同一限速令 id，不重复登记）
        MvcResult replay = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
        JsonNode list = read(mvc.perform(get("/api/v1/restrictions")
                        .param("sectionId", section))
                .andExpect(status().isOk()).andReturn());
        assertThat(list).hasSize(1);

        // 同键不同参 → 409
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, section, iso(8), iso(9), 90, "op")))
                .andExpect(status().isConflict());
    }

    @Test
    void failedRegisterDoesNotConsumeRequestKey() throws Exception {
        String section = key("SEC");
        String requestKey = key("REQ");
        // 首次速度非法 → 422，不占键
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, section, iso(8), iso(9), 5, null)))
                .andExpect(status().isUnprocessableEntity());
        // 修正后同键重试成功
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, section, iso(8), iso(9), 50, null)))
                .andExpect(status().isCreated());
    }

    @Test
    void revokeIdempotentReplay() throws Exception {
        long id = register(key("SEC"), iso(8), iso(9), 100);
        String requestKey = key("REQ");
        String body = revokeBody(requestKey, "op");
        MvcResult first = mvc.perform(post("/api/v1/restrictions/{id}/revoke", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        // 同键同参重放返回首次结果，不因已撤销而 409
        MvcResult replay = mvc.perform(post("/api/v1/restrictions/{id}/revoke", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));
    }

    // ---------- 辅助 ----------

    private long register(String section, String startIso, String endIso, int speed)
            throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), section, startIso, endIso, speed, null)))
                .andExpect(status().isCreated()).andReturn();
        return read(result).get("id").asLong();
    }

    private void revoke(long id, String requestKey) throws Exception {
        mvc.perform(post("/api/v1/restrictions/{id}/revoke", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody(requestKey, null)))
                .andExpect(status().isOk());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String iso(int hour) {
        return BASE.plusSeconds(hour * 3600L).toString();
    }

    private static String registerBody(String requestKey, String sectionId, String startIso,
                                       String endIso, int speed, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"sectionId\":\"" + sectionId
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso
                + "\",\"maxSpeedKmh\":" + speed
                + (operator == null ? "" : ",\"operator\":\"" + operator + "\"") + "}";
    }

    private static String revokeBody(String requestKey, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\""
                + (operator == null ? "" : ",\"operator\":\"" + operator + "\"") + "}";
    }
}
