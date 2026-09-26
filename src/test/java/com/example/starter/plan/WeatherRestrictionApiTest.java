package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 气象限速令 API 测试：登记/撤销/修订主流程、参数非法 422、状态冲突 409、
 * 幂等重放与异参 409、明细/历史/诊断只读查询（H2 内存库）。
 * 各用例使用唯一 restrictionKey/requestKey/区段 ID，共享库内互不干扰。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WeatherRestrictionApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2030, 1, 5);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------- 登记主流程 ----------

    @Test
    void registerCreatesActiveVersion1() throws Exception {
        String restrictionKey = key("RST");
        String section = key("SEC");

        MvcResult result = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), restrictionKey, section,
                                iso(10), iso(12), 60, "op-a")))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("restrictionKey").asText()).isEqualTo(restrictionKey);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("sectionId").asText()).isEqualTo(section);
        assertThat(body.get("startUtc").asText()).isEqualTo(iso(10));
        assertThat(body.get("endUtc").asText()).isEqualTo(iso(12));
        assertThat(body.get("maxSpeedKmh").asInt()).isEqualTo(60);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("operator").asText()).isEqualTo("op-a");

        // 明细查询可按区段过滤命中
        MvcResult list = mvc.perform(get("/api/v1/restrictions")
                        .param("sectionId", section).param("onlyActive", "true"))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(list);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("restrictionKey").asText()).isEqualTo(restrictionKey);
    }

    @Test
    void registerAcceptsBoundarySpeeds() throws Exception {
        String section = key("SEC");
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), section,
                                iso(10), iso(11), 10, "op-a")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), section,
                                iso(10), iso(11), 300, "op-a")))
                .andExpect(status().isCreated());
    }

    // ---------- 参数非法 422 ----------

    @Test
    void registerRejectsSpeedOutOfRangeWithActualAndRequired() throws Exception {
        String section = key("SEC");
        // 低于下限
        MvcResult low = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), section,
                                iso(10), iso(11), 5, "op-a")))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode lowBody = read(low);
        assertThat(lowBody.get("code").asText()).isEqualTo("RESTRICTION_INVALID");
        JsonNode lowDetail = lowBody.get("details").get(0);
        assertThat(lowDetail.get("actualSpeedKmh").asInt()).isEqualTo(5);
        assertThat(lowDetail.get("minSpeedKmh").asInt()).isEqualTo(10);
        assertThat(lowDetail.get("maxSpeedKmh").asInt()).isEqualTo(300);

        // 高于上限
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), section,
                                iso(10), iso(11), 301, "op-a")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void registerRejectsIllegalWindowAndSection() throws Exception {
        String section = key("SEC");
        // 结束不晚于开始
        MvcResult window = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), section,
                                iso(12), iso(10), 60, "op-a")))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode windowBody = read(window);
        assertThat(windowBody.get("code").asText()).isEqualTo("RESTRICTION_INVALID");
        assertThat(windowBody.get("details").get(0).get("actualStartUtc").asText())
                .isEqualTo(iso(12));
        assertThat(windowBody.get("details").get(0).get("actualEndUtc").asText())
                .isEqualTo(iso(10));

        // 空区段
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), "  ",
                                iso(10), iso(11), 60, "op-a")))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 撤销 / 修订 ----------

    @Test
    void revokeFlipsStatusAndKeepsHistoryRow() throws Exception {
        String restrictionKey = key("RST");
        register(restrictionKey, key("SEC"), iso(10), iso(12), 60);

        MvcResult revoked = mvc.perform(post("/api/v1/restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody(key("REQ"), "op-b")))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(revoked).get("status").asText()).isEqualTo("REVOKED");

        // 历史行保留：版本 1 仍在，状态已撤销
        MvcResult history = mvc.perform(get("/api/v1/restrictions/{key}", restrictionKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(history);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("status").asText()).isEqualTo("REVOKED");
        assertThat(items.get(0).get("maxSpeedKmh").asInt()).isEqualTo(60);

        // 再次撤销 → 409 状态冲突
        mvc.perform(post("/api/v1/restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody(key("REQ"), "op-b")))
                .andExpect(status().isConflict());
    }

    @Test
    void reviseRevokesOldVersionAndAppendsNewVersionAtomically() throws Exception {
        String restrictionKey = key("RST");
        String section = key("SEC");
        register(restrictionKey, section, iso(10), iso(12), 60);

        MvcResult revised = mvc.perform(post("/api/v1/restrictions/{key}/revise", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(key("REQ"), section, iso(10), iso(13), 40, "op-c")))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(revised);
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("maxSpeedKmh").asInt()).isEqualTo(40);
        assertThat(body.get("endUtc").asText()).isEqualTo(iso(13));
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");

        // 历史：v1 已撤销不改写，v2 生效
        MvcResult history = mvc.perform(get("/api/v1/restrictions/{key}", restrictionKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = read(history);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(items.get(0).get("status").asText()).isEqualTo("REVOKED");
        assertThat(items.get(0).get("maxSpeedKmh").asInt()).isEqualTo(60);
        assertThat(items.get(1).get("version").asInt()).isEqualTo(2);
        assertThat(items.get(1).get("status").asText()).isEqualTo("ACTIVE");

        // 仅生效查询只返回 v2
        MvcResult active = mvc.perform(get("/api/v1/restrictions")
                        .param("sectionId", section).param("onlyActive", "true"))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(active)).hasSize(1);
    }

    @Test
    void revokeAndReviseUnknownKeyReturn404() throws Exception {
        String missing = key("RST");
        mvc.perform(post("/api/v1/restrictions/{key}/revoke", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody(key("REQ"), "op-a")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/restrictions/{key}/revise", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(key("REQ"), key("SEC"), iso(10), iso(11), 60, "op-a")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/restrictions/{key}", missing))
                .andExpect(status().isNotFound());
    }

    @Test
    void registerDuplicateKeyAndReviseAfterRevokeReturn409() throws Exception {
        String restrictionKey = key("RST");
        String section = key("SEC");
        register(restrictionKey, section, iso(10), iso(12), 60);

        // 业务键已存在 → 409
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), restrictionKey, section,
                                iso(11), iso(12), 80, "op-a")))
                .andExpect(status().isConflict());

        // 撤销后无生效版本，修订 → 409；重新登记同键 → 409
        revoke(restrictionKey);
        mvc.perform(post("/api/v1/restrictions/{key}/revise", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(key("REQ"), section, iso(10), iso(11), 60, "op-a")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), restrictionKey, section,
                                iso(11), iso(12), 80, "op-a")))
                .andExpect(status().isConflict());
    }

    // ---------- 幂等 ----------

    @Test
    void registerIdempotentReplayAndParamConflict() throws Exception {
        String restrictionKey = key("RST");
        String section = key("SEC");
        String requestKey = key("REQ");
        String body = registerBody(requestKey, restrictionKey, section, iso(10), iso(12), 60, "op-a");

        MvcResult first = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(post("/api/v1/restrictions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 重放不产生重复行
        MvcResult history = mvc.perform(get("/api/v1/restrictions/{key}", restrictionKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(history)).hasSize(1);

        // 同键不同参（速度不同）→ 409
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, key("RST"), section,
                                iso(10), iso(12), 80, "op-a")))
                .andExpect(status().isConflict());
    }

    @Test
    void registerFailureDoesNotConsumeRequestKey() throws Exception {
        String restrictionKey = key("RST");
        String section = key("SEC");
        String requestKey = key("REQ");
        // 首次速度非法 → 422，不占键
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, restrictionKey, section,
                                iso(10), iso(12), 5, "op-a")))
                .andExpect(status().isUnprocessableEntity());
        // 修正后同键重试成功
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, restrictionKey, section,
                                iso(10), iso(12), 50, "op-a")))
                .andExpect(status().isCreated());
    }

    // ---------- 诊断查询（只读） ----------

    @Test
    void diagnoseReturnsMinimumSpeedAndOverlapMinutes() throws Exception {
        String section = key("SEC");
        String slow = key("RST");
        String fast = key("RST");
        register(slow, section, iso(10), iso(12), 40);
        register(fast, section, iso(11), iso(13), 120);
        // 已撤销的不参与裁决
        String revoked = key("RST");
        register(revoked, section, iso(10), iso(13), 20);
        revoke(revoked);

        MvcResult result = mvc.perform(get("/api/v1/restrictions/diagnose")
                        .param("sectionId", section)
                        .param("startUtc", iso(10, 30)).param("endUtc", iso(12, 30)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("sectionId").asText()).isEqualTo(section);
        // 同区段同时刻多条生效以最低速度为准
        assertThat(body.get("effectiveSpeedKmh").asInt()).isEqualTo(40);
        JsonNode contributors = body.get("contributors");
        assertThat(contributors).hasSize(2);
        assertThat(contributors.get(0).get("restrictionKey").asText()).isEqualTo(slow);
        assertThat(contributors.get(0).get("overlapMinutes").asLong()).isEqualTo(90);
        assertThat(contributors.get(1).get("restrictionKey").asText()).isEqualTo(fast);
        assertThat(contributors.get(1).get("overlapMinutes").asLong()).isEqualTo(90);

        // 诊断只读：重复查询结果一致，且限速令数量不变
        MvcResult again = mvc.perform(get("/api/v1/restrictions/diagnose")
                        .param("sectionId", section)
                        .param("startUtc", iso(10, 30)).param("endUtc", iso(12, 30)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(again)).isEqualTo(body);
        MvcResult list = mvc.perform(get("/api/v1/restrictions").param("sectionId", section))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(list)).hasSize(3);
    }

    @Test
    void diagnoseWithoutRestrictionReturnsNullSpeedAndRejectsIllegalWindow() throws Exception {
        String section = key("SEC");
        MvcResult result = mvc.perform(get("/api/v1/restrictions/diagnose")
                        .param("sectionId", section)
                        .param("startUtc", iso(10)).param("endUtc", iso(11)))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = read(result);
        assertThat(body.get("effectiveSpeedKmh").isNull()).isTrue();
        assertThat(body.get("contributors")).isEmpty();

        mvc.perform(get("/api/v1/restrictions/diagnose")
                        .param("sectionId", section)
                        .param("startUtc", iso(11)).param("endUtc", iso(10)))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 辅助 ----------

    private void register(String restrictionKey, String section, String startIso, String endIso,
                          int speed) throws Exception {
        mvc.perform(post("/api/v1/restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), restrictionKey, section,
                                startIso, endIso, speed, "op-a")))
                .andExpect(status().isCreated());
    }

    private void revoke(String restrictionKey) throws Exception {
        mvc.perform(post("/api/v1/restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody(key("REQ"), "op-a")))
                .andExpect(status().isOk());
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

    private static String registerBody(String requestKey, String restrictionKey, String sectionId,
                                       String startIso, String endIso, int speed, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"restrictionKey\":\"" + restrictionKey
                + "\",\"sectionId\":\"" + sectionId + "\",\"startUtc\":\"" + startIso
                + "\",\"endUtc\":\"" + endIso + "\",\"maxSpeedKmh\":" + speed
                + ",\"operator\":\"" + operator + "\"}";
    }

    private static String revokeBody(String requestKey, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"" + operator + "\"}";
    }

    private static String reviseBody(String requestKey, String sectionId, String startIso,
                                     String endIso, int speed, String operator) {
        return "{\"requestKey\":\"" + requestKey + "\",\"sectionId\":\"" + sectionId
                + "\",\"startUtc\":\"" + startIso + "\",\"endUtc\":\"" + endIso
                + "\",\"maxSpeedKmh\":" + speed + ",\"operator\":\"" + operator + "\"}";
    }
}
