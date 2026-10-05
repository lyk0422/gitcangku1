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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 气象限速令 API 集成测试（H2 内存库，MODE=MySQL）：
 * 登记/诊断主流程、422 参数分支、修订撤销版本化、幂等重放与异参 409、失败不占键、只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class RestrictionApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock clock;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void resetClock() {
        clock.setInstant(TestClockConfig.DEFAULT_NOW);
        // 清理场景数据：各用例在干净的限速令与计划数据上执行（种子区段与发布锁保留）
        jdbc.update("DELETE FROM rail_plan_rearrangement");
        jdbc.update("DELETE FROM rail_speed_restriction");
        jdbc.update("DELETE FROM rail_plan_reschedule_link");
        jdbc.update("DELETE FROM rail_plan_occupancy");
        jdbc.update("DELETE FROM rail_day_plan");
        jdbc.update("DELETE FROM idempotency_record");
    }

    @Test
    void registerAndEffectiveSpeedDiagnostics() throws Exception {
        String keyA = key("RST");
        MvcResult created = mvc.perform(post("/api/v1/speed-restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), keyA, "SEC-L1-A", iso(0), iso(12), 80)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = read(created);
        assertThat(body.get("restrictionKey").asText()).isEqualTo(keyA);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("maxSpeedKmh").asInt()).isEqualTo(80);
        assertThat(body.get("operator").asText()).isEqualTo("dispatcher-1");

        // 诊断：窗口内生效 80
        MvcResult effective = mvc.perform(get("/api/v1/speed-restrictions/effective")
                        .param("sectionId", "SEC-L1-A").param("at", iso(8)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(effective).get("effectiveSpeedKmh").asInt()).isEqualTo(80);
        assertThat(read(effective).get("activeRestrictions")).hasSize(1);

        // 同时刻叠加更低限速 60：以最低速度为准
        String keyB = key("RST");
        mvc.perform(post("/api/v1/speed-restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), keyB, "SEC-L1-A", iso(0), iso(12), 60)))
                .andExpect(status().isCreated());
        MvcResult lowered = mvc.perform(get("/api/v1/speed-restrictions/effective")
                        .param("sectionId", "SEC-L1-A").param("at", iso(8)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(lowered).get("effectiveSpeedKmh").asInt()).isEqualTo(60);
        assertThat(read(lowered).get("activeRestrictions")).hasSize(2);

        // 窗口外无生效限速：effectiveSpeedKmh 为 null
        MvcResult outside = mvc.perform(get("/api/v1/speed-restrictions/effective")
                        .param("sectionId", "SEC-L1-A").param("at", iso(13)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(outside).get("effectiveSpeedKmh").isNull()).isTrue();
        assertThat(read(outside).get("activeRestrictions")).isEmpty();

        // 明细、历史与按区段列表
        mvc.perform(get("/api/v1/speed-restrictions/{key}", keyA)).andExpect(status().isOk());
        MvcResult list = mvc.perform(get("/api/v1/speed-restrictions")
                        .param("sectionId", "SEC-L1-A"))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(list).size()).isGreaterThanOrEqualTo(2);

        // 未注册区段诊断 → 404
        mvc.perform(get("/api/v1/speed-restrictions/effective")
                        .param("sectionId", key("SEC")).param("at", iso(8)))
                .andExpect(status().isNotFound());
    }

    @Test
    void registerRejectsInvalidParamsWith422() throws Exception {
        // 速度低于下限
        MvcResult tooSlow = mvc.perform(post("/api/v1/speed-restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), "SEC-L1-A",
                                iso(0), iso(12), 5)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode error = read(tooSlow);
        assertThat(error.get("code").asText()).isEqualTo("RESTRICTION_INVALID");
        assertThat(error.get("details").get(0).get("actualMaxSpeedKmh").asInt()).isEqualTo(5);
        assertThat(error.get("details").get(0).get("requiredMinKmh").asInt()).isEqualTo(10);
        assertThat(error.get("details").get(0).get("requiredMaxKmh").asInt()).isEqualTo(300);

        // 速度高于上限
        mvc.perform(post("/api/v1/speed-restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), "SEC-L1-A",
                                iso(0), iso(12), 301)))
                .andExpect(status().isUnprocessableEntity());
        // 时段非法：结束等于开始 / 结束早于开始
        mvc.perform(post("/api/v1/speed-restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), "SEC-L1-A",
                                iso(8), iso(8), 80)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/speed-restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), "SEC-L1-A",
                                iso(12), iso(8), 80)))
                .andExpect(status().isUnprocessableEntity());
        // 区段未注册
        String unknownSection = key("SEC");
        MvcResult unknown = mvc.perform(post("/api/v1/speed-restrictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), key("RST"), unknownSection,
                                iso(0), iso(12), 80)))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(read(unknown).get("details").get(0).get("sectionId").asText())
                .isEqualTo(unknownSection);
        // 缺 requestKey → 400
        mvc.perform(post("/api/v1/speed-restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restrictionKey\":\"" + key("RST")
                                + "\",\"sectionId\":\"SEC-L1-A\",\"startUtc\":\"" + iso(0)
                                + "\",\"endUtc\":\"" + iso(12)
                                + "\",\"maxSpeedKmh\":80,\"operator\":\"dispatcher-1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reviseAndRevokeVersioning() throws Exception {
        String restrictionKey = key("RST");
        mvc.perform(post("/api/v1/speed-restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), restrictionKey, "SEC-L1-A",
                                iso(0), iso(12), 80)))
                .andExpect(status().isCreated());

        // 修订：版本加一，旧版本置 SUPERSEDED，历史保留
        MvcResult revised = mvc.perform(put("/api/v1/speed-restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(key("REQ"), iso(1), iso(13), 60)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(revised).get("version").asInt()).isEqualTo(2);
        assertThat(read(revised).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(read(revised).get("maxSpeedKmh").asInt()).isEqualTo(60);

        MvcResult history = mvc.perform(get("/api/v1/speed-restrictions/{key}/history",
                        restrictionKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode versions = read(history);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(versions.get(0).get("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(versions.get(1).get("version").asInt()).isEqualTo(2);
        assertThat(versions.get(1).get("status").asText()).isEqualTo("ACTIVE");

        // 生效诊断采用新版本（窗口 [01:00, 13:00)，限速 60）
        MvcResult effective = mvc.perform(get("/api/v1/speed-restrictions/effective")
                        .param("sectionId", "SEC-L1-A").param("at", iso(2)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(effective).get("effectiveSpeedKmh").asInt()).isEqualTo(60);

        // 撤销：最新版本置 REVOKED，历史版本保留
        MvcResult revoked = mvc.perform(post("/api/v1/speed-restrictions/{key}/revoke",
                        restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(revoked).get("status").asText()).isEqualTo("REVOKED");
        MvcResult historyAfterRevoke = mvc.perform(get("/api/v1/speed-restrictions/{key}/history",
                        restrictionKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(historyAfterRevoke)).hasSize(2);

        // 已撤销不可再修订、不可再撤销
        mvc.perform(put("/api/v1/speed-restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(key("REQ"), iso(1), iso(13), 50)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/speed-restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isConflict());

        // 不存在的限速令 → 404
        String missing = key("RST");
        mvc.perform(get("/api/v1/speed-restrictions/{key}", missing))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/speed-restrictions/{key}", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(key("REQ"), iso(1), iso(13), 50)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/speed-restrictions/{key}/revoke", missing)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(key("REQ"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void idempotentReplayAndParamConflict() throws Exception {
        String restrictionKey = key("RST");
        String requestKey = key("REQ");
        String body = registerBody(requestKey, restrictionKey, "SEC-L1-A", iso(0), iso(12), 80);

        MvcResult first = mvc.perform(post("/api/v1/speed-restrictions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        // 同键同参重放返回首次完整结果
        MvcResult replay = mvc.perform(post("/api/v1/speed-restrictions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertThat(read(replay)).isEqualTo(read(first));

        // 同键异参 → 409
        mvc.perform(post("/api/v1/speed-restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, key("RST"), "SEC-L1-A",
                                iso(0), iso(12), 80)))
                .andExpect(status().isConflict());

        // 修订幂等：重放不产生第三版本
        String reviseKey = key("REQ");
        String revise = reviseBody(reviseKey, iso(1), iso(13), 60);
        mvc.perform(put("/api/v1/speed-restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON).content(revise))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/speed-restrictions/{key}", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON).content(revise))
                .andExpect(status().isOk());
        MvcResult history = mvc.perform(get("/api/v1/speed-restrictions/{key}/history",
                        restrictionKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(history)).hasSize(2);

        // 撤销幂等：重放返回首次结果而非 409
        String revokeKey = key("REQ");
        mvc.perform(post("/api/v1/speed-restrictions/{key}/revoke", restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(revokeKey)))
                .andExpect(status().isOk());
        MvcResult revokeReplay = mvc.perform(post("/api/v1/speed-restrictions/{key}/revoke",
                        restrictionKey)
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody(revokeKey)))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(revokeReplay).get("status").asText()).isEqualTo("REVOKED");
    }

    @Test
    void failureDoesNotOccupyIdempotencyKey() throws Exception {
        String requestKey = key("REQ");
        // 首次失败（速度越界 422）不占键
        mvc.perform(post("/api/v1/speed-restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, key("RST"), "SEC-L1-A",
                                iso(0), iso(12), 5)))
                .andExpect(status().isUnprocessableEntity());
        // 修正参数后同键重发成功
        mvc.perform(post("/api/v1/speed-restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(requestKey, key("RST"), "SEC-L1-A",
                                iso(0), iso(12), 80)))
                .andExpect(status().isCreated());
    }

    @Test
    void readsDoNotChangeState() throws Exception {
        String restrictionKey = key("RST");
        mvc.perform(post("/api/v1/speed-restrictions").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(key("REQ"), restrictionKey, "SEC-L1-A",
                                iso(0), iso(12), 80)))
                .andExpect(status().isCreated());
        MvcResult before = mvc.perform(get("/api/v1/speed-restrictions/{key}", restrictionKey))
                .andExpect(status().isOk()).andReturn();

        // 各类只读查询
        mvc.perform(get("/api/v1/speed-restrictions/{key}/history", restrictionKey))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/speed-restrictions").param("sectionId", "SEC-L1-A"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/speed-restrictions/effective")
                        .param("sectionId", "SEC-L1-A").param("at", iso(8)))
                .andExpect(status().isOk());

        // 读取后状态不变
        MvcResult after = mvc.perform(get("/api/v1/speed-restrictions/{key}", restrictionKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(read(after)).isEqualTo(read(before));
    }

    // ---------- 辅助 ----------

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

    private static String registerBody(String requestKey, String restrictionKey, String sectionId,
                                       String startIso, String endIso, int maxSpeed) {
        return "{\"requestKey\":\"" + requestKey + "\",\"restrictionKey\":\"" + restrictionKey
                + "\",\"sectionId\":\"" + sectionId + "\",\"startUtc\":\"" + startIso
                + "\",\"endUtc\":\"" + endIso + "\",\"maxSpeedKmh\":" + maxSpeed
                + ",\"operator\":\"dispatcher-1\"}";
    }

    private static String reviseBody(String requestKey, String startIso, String endIso,
                                     int maxSpeed) {
        return "{\"requestKey\":\"" + requestKey + "\",\"startUtc\":\"" + startIso
                + "\",\"endUtc\":\"" + endIso + "\",\"maxSpeedKmh\":" + maxSpeed
                + ",\"operator\":\"dispatcher-2\"}";
    }

    private static String actionBody(String requestKey) {
        return "{\"requestKey\":\"" + requestKey + "\",\"operator\":\"dispatcher-2\"}";
    }
}
