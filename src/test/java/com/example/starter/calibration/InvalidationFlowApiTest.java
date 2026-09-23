package com.example.starter.calibration;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 失效单主流程测试：创建快照、双人不同人员确认、整体冻结
 * （标准器 INVALID、未审核 BLOCKED、已放行 REVIEW_REQUIRED 保留放行快照）、
 * 单一 impactVersion、最短血缘路径冻结、历史查询显式显示影响状态、影响查询按 impactVersion 只读重现。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvalidationFlowApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM impact_path");
        jdbc.update("DELETE FROM invalidation_confirmation");
        jdbc.update("DELETE FROM invalidation_snapshot");
        jdbc.update("DELETE FROM invalidation_order");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM standard_version");
        jdbc.update("DELETE FROM measurement_standard");
    }

    private void standard(String id) throws Exception {
        mvc.perform(post("/api/standards").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"standardId\":\"" + id + "\",\"name\":\"" + id + "\"}"))
                .andExpect(status().isCreated());
    }

    private void version(String key, String std, String parent, String from, String to) throws Exception {
        String p = parent == null ? "null" : "\"" + parent + "\"";
        mvc.perform(post("/api/standards/versions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"versionKey":"%s","standardId":"%s","parentVersionKey":%s,
                                 "validFrom":"%s","validTo":"%s","certificateNo":"C-%s"}
                                """.formatted(key, std, p, from, to, key)))
                .andExpect(status().isCreated());
    }

    private void cert(String instrument) throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated());
    }

    private void submit(String key, String instrument, String measuredAt, String stdVersion, String by)
            throws Exception {
        String sv = stdVersion == null ? "null" : "\"" + stdVersion + "\"";
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"%s","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s",
                                 "standardVersionKey":%s}
                                """.formatted(key, instrument, measuredAt, by, sv)))
                .andExpect(status().isCreated());
    }

    private void release(String key, String actor) throws Exception {
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"" + key + "\"]}"))
                .andExpect(status().isOk());
    }

    private long versionId(String key) {
        return jdbc.queryForObject("SELECT id FROM standard_version WHERE version_key = ?",
                Long.class, key);
    }

    private String body(String content) {
        return content;
    }

    /** 建立三代血缘 ROOT → MID → LEAF，仪器 INS-1 下三条分别绑定三代版本的测量（两条已放行）。 */
    private void buildScenario() throws Exception {
        standard("STD-ROOT");
        standard("STD-MID");
        standard("STD-LEAF");
        version("SV-ROOT", "STD-ROOT", null, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
        version("SV-MID", "STD-MID", "SV-ROOT", "2026-02-01T00:00:00Z", "2026-12-01T00:00:00Z");
        version("SV-LEAF", "STD-LEAF", "SV-MID", "2026-03-01T00:00:00Z", "2026-11-01T00:00:00Z");
        cert("INS-1");
        submit("M-LEAF-PEND", "INS-1", "2026-06-01T00:00:00Z", "SV-LEAF", "alice");
        submit("M-MID-REL", "INS-1", "2026-06-01T00:00:00Z", "SV-MID", "bob");
        submit("M-ROOT-REL", "INS-1", "2026-06-01T00:00:00Z", "SV-ROOT", "carol");
        release("M-MID-REL", "reviewer1");
        release("M-ROOT-REL", "reviewer2");
    }

    private String createOrder(String key, String rootVersion, String requestId, long expectedVersion)
            throws Exception {
        return mvc.perform(post("/api/invalidations")
                        .header("X-Request-Id", requestId)
                        .header("X-Actor-Id", "qm-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"invalidationKey":"%s","rootVersionKey":"%s",
                                 "effectiveFrom":"2026-06-01T00:00:00Z","expectedVersion":%d,
                                 "reason":"标准器超差"}
                                """.formatted(key, rootVersion, expectedVersion))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void twoDifferentConfirmerActivationFreezesWholeClosure() throws Exception {
        buildScenario();
        long expectedVersion = jdbc.queryForObject("SELECT version FROM domain_state WHERE id = 1", Long.class);

        // 失效根 SV-ROOT：三代版本与三条测量全部在闭包内
        String created = createOrder("INV-1", "SV-ROOT", "REQ-1", expectedVersion);
        com.jayway.jsonpath.DocumentContext json =
                com.jayway.jsonpath.JsonPath.parse(created);
        org.junit.jupiter.api.Assertions.assertEquals(Integer.valueOf(3), json.read("$.standards.length()"));
        org.junit.jupiter.api.Assertions.assertEquals(Integer.valueOf(3), json.read("$.measurements.length()"));
        org.junit.jupiter.api.Assertions.assertNull(json.read("$.impactVersion"));

        // 第一名确认：仍为 PENDING
        mvc.perform(post("/api/invalidations/INV-1/confirmations").header("X-Actor-Id", "qm-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.confirmers.length()").value(1));

        // 同一人重复确认 → 409
        mvc.perform(post("/api/invalidations/INV-1/confirmations").header("X-Actor-Id", "qm-a"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CONFIRMED"));

        // 仅一名确认时不得冻结
        org.junit.jupiter.api.Assertions.assertEquals(0L,
                jdbc.queryForObject("SELECT COUNT(*) FROM measurement WHERE status IN ('BLOCKED','REVIEW_REQUIRED')",
                        Long.class));

        // 第二名不同确认人：整体激活
        String activated = mvc.perform(
                        post("/api/invalidations/INV-1/confirmations").header("X-Actor-Id", "qm-b"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        com.jayway.jsonpath.DocumentContext actJson =
                com.jayway.jsonpath.JsonPath.parse(activated);
        org.junit.jupiter.api.Assertions.assertEquals("ACTIVATED", actJson.read("$.status"));
        String impactVersion = actJson.read("$.impactVersion");
        org.junit.jupiter.api.Assertions.assertTrue(impactVersion.startsWith("IV-"));
        org.junit.jupiter.api.Assertions.assertEquals(Integer.valueOf(2), actJson.read("$.confirmers.length()"));

        // 根标准器及全部子孙 INVALID
        Long invalidCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM standard_version WHERE status = 'INVALID'", Long.class);
        org.junit.jupiter.api.Assertions.assertEquals(3L, invalidCount);

        // 未审核 BLOCKED；已放行 REVIEW_REQUIRED 且数值保留
        org.junit.jupiter.api.Assertions.assertEquals("BLOCKED",
                jdbc.queryForObject("SELECT status FROM measurement WHERE measurement_key = 'M-LEAF-PEND'",
                        String.class));
        Map<String, Object> midRel = jdbc.queryForMap(
                "SELECT * FROM measurement WHERE measurement_key = 'M-MID-REL'");
        org.junit.jupiter.api.Assertions.assertEquals("REVIEW_REQUIRED", midRel.get("status"));
        org.junit.jupiter.api.Assertions.assertEquals(impactVersion, midRel.get("impact_version"));
        org.junit.jupiter.api.Assertions.assertNotNull(midRel.get("computed_value"));
        org.junit.jupiter.api.Assertions.assertEquals(true, midRel.get("passed"));

        // 原放行快照保留
        Long releaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record r JOIN measurement m ON m.id = r.measurement_id "
                        + "WHERE m.measurement_key = 'M-MID-REL'", Long.class);
        org.junit.jupiter.api.Assertions.assertEquals(1L, releaseCount);

        // 冻结血缘路径：M-LEAF-PEND → [SV-LEAF, SV-MID, SV-ROOT] 三代
        long leafId = jdbc.queryForObject(
                "SELECT id FROM measurement WHERE measurement_key = 'M-LEAF-PEND'", Long.class);
        java.util.List<Long> leafPath = jdbc.queryForList(
                "SELECT version_id FROM impact_path WHERE measurement_id = ? ORDER BY depth",
                Long.class, leafId);
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of(versionId("SV-LEAF"), versionId("SV-MID"), versionId("SV-ROOT")),
                leafPath);

        // M-MID-REL 路径两代
        long midId = jdbc.queryForObject(
                "SELECT id FROM measurement WHERE measurement_key = 'M-MID-REL'", Long.class);
        java.util.List<Long> midPath = jdbc.queryForList(
                "SELECT version_id FROM impact_path WHERE measurement_id = ? ORDER BY depth",
                Long.class, midId);
        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of(versionId("SV-MID"), versionId("SV-ROOT")), midPath);
    }

    @Test
    void historyStillReturnsResultWithImpactStatus() throws Exception {
        buildScenario();
        long expectedVersion = jdbc.queryForObject("SELECT version FROM domain_state WHERE id = 1", Long.class);
        createOrder("INV-2", "SV-ROOT", "REQ-2", expectedVersion);
        mvc.perform(post("/api/invalidations/INV-2/confirmations").header("X-Actor-Id", "qm-a"))
                .andExpect(status().isOk());
        String activated = mvc.perform(post("/api/invalidations/INV-2/confirmations").header("X-Actor-Id", "qm-b"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String impactVersion = com.jayway.jsonpath.JsonPath.read(activated, "$.impactVersion");

        // 历史明细仍返回原结果，显式显示 REVIEW_REQUIRED、impactVersion 与放行快照
        mvc.perform(get("/api/measurements/{key}", "M-MID-REL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.impactVersion").value(impactVersion))
                .andExpect(jsonPath("$.computedValue").value("1"))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("reviewer1"));

        // 冻结后不得再放行未审核记录
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "reviewer9")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-LEAF-PEND\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("NOT_PENDING"));
    }

    @Test
    void impactQueryReplaysByImpactVersion() throws Exception {
        buildScenario();
        long expectedVersion = jdbc.queryForObject("SELECT version FROM domain_state WHERE id = 1", Long.class);
        createOrder("INV-3", "SV-MID", "REQ-3", expectedVersion);
        mvc.perform(post("/api/invalidations/INV-3/confirmations").header("X-Actor-Id", "qm-a"))
                .andExpect(status().isOk());
        String activated = mvc.perform(post("/api/invalidations/INV-3/confirmations").header("X-Actor-Id", "qm-b"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String impactVersion = com.jayway.jsonpath.JsonPath.read(activated, "$.impactVersion");

        // 按 impactVersion 只读重现：闭包 MID/LEAF 两代，M-ROOT-REL 不在影响范围仍 RELEASED
        mvc.perform(get("/api/impacts/{iv}", impactVersion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.standards.length()").value(2))
                .andExpect(jsonPath("$.measurements.length()").value(2))
                .andExpect(jsonPath("$.measurements[?(@.measurementKey=='M-LEAF-PEND')].status").value("BLOCKED"))
                .andExpect(jsonPath("$.measurements[?(@.measurementKey=='M-MID-REL')].status")
                        .value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.measurements[?(@.measurementKey=='M-MID-REL')].releaseSnapshot.releasedBy")
                        .value("reviewer1"));

        // 未受影响的 M-ROOT-REL 仍可查询且为 RELEASED
        mvc.perform(get("/api/measurements/{key}", "M-ROOT-REL"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true));

        // 未知 impactVersion → 404
        mvc.perform(get("/api/impacts/{iv}", "IV-NOPE")).andExpect(status().isNotFound());
    }

    @Test
    void afterActivationNewMeasurementCannotBindInvalidVersion() throws Exception {
        buildScenario();
        long expectedVersion = jdbc.queryForObject("SELECT version FROM domain_state WHERE id = 1", Long.class);
        createOrder("INV-5", "SV-ROOT", "REQ-5", expectedVersion);
        mvc.perform(post("/api/invalidations/INV-5/confirmations").header("X-Actor-Id", "qm-a"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/invalidations/INV-5/confirmations").header("X-Actor-Id", "qm-b"))
                .andExpect(status().isOk());

        // 失效提交后新校准不得绑定已 INVALID 版本 → 422
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-AFTER","instrumentId":"INS-1",
                                 "measuredAt":"2026-06-02T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"dave",
                                 "standardVersionKey":"SV-ROOT"}"""))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void confirmUnknownOrderReturns404AndActivatedOrderReturns409() throws Exception {
        buildScenario();
        mvc.perform(post("/api/invalidations/NOPE/confirmations").header("X-Actor-Id", "qm-a"))
                .andExpect(status().isNotFound());

        long expectedVersion = jdbc.queryForObject("SELECT version FROM domain_state WHERE id = 1", Long.class);
        createOrder("INV-4", "SV-ROOT", "REQ-4", expectedVersion);
        mvc.perform(post("/api/invalidations/INV-4/confirmations").header("X-Actor-Id", "qm-a"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/invalidations/INV-4/confirmations").header("X-Actor-Id", "qm-b"))
                .andExpect(status().isOk());
        // 已激活再确认 → 409
        mvc.perform(post("/api/invalidations/INV-4/confirmations").header("X-Actor-Id", "qm-c"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_ACTIVATED"));
    }
}
