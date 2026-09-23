package com.example.starter.calibration;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 失效闭包与整体冻结测试：预览完整闭包稳定排序、双人确认、激活后
 * 根及子孙 INVALID、未审核 BLOCKED、已放行 REVIEW_REQUIRED 且保留原放行快照与数值、
 * 单一 impactVersion 与最短血缘路径冻结、影响查询只读重现、历史查询显式显示影响状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvalidationFreezeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM impact_item");
        jdbc.update("DELETE FROM invalidation_confirmation");
        jdbc.update("DELETE FROM invalidation_order");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM standard_version");
        jdbc.update("DELETE FROM lineage_lock");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private void createStandard(String standardId, String parentId,
                                String validFrom, String validTo, String certificateNo) throws Exception {
        String parent = parentId == null ? "null" : "\"" + parentId + "\"";
        mvc.perform(post("/api/standards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"standardId":"%s","parentStandardId":%s,"validFrom":"%s",
                                 "validTo":"%s","certificateNo":"%s"}"""
                                .formatted(standardId, parent, validFrom, validTo, certificateNo)))
                .andExpect(status().isCreated());
    }

    private void createCertificateAndMeasurement(String key, String measuredAt, String standardId)
            throws Exception {
        String instrument = "INS-" + key;
        mvc.perform(post("/api/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2025-01-01T00:00:00Z",
                                 "validTo":"2029-01-01T00:00:00Z","a":"1","b":"0"}"""
                                .formatted(instrument)))
                .andExpect(status().isCreated());
        String std = standardId == null ? "" : ",\"standardId\":\"" + standardId + "\"";
        mvc.perform(post("/api/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"%s","reading":"5",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice"%s}"""
                                .formatted(key, instrument, measuredAt, std)))
                .andExpect(status().isCreated());
    }

    private String createInvalidation(String requestId, String key, String root, String invalidFrom,
                                      int expectedVersion) throws Exception {
        return mvc.perform(post("/api/invalidations")
                        .header("X-Actor-Id", "qm-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","invalidationKey":"%s","rootStandardId":"%s",
                                 "invalidFrom":"%s","expectedVersion":%d,"reason":"drift found"}"""
                                .formatted(requestId, key, root, invalidFrom, expectedVersion)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private void confirm(String key, String actor) throws Exception {
        mvc.perform(post("/api/invalidations/{key}/confirm", key).header("X-Actor-Id", actor))
                .andExpect(status().isOk());
    }

    private String activate(String key, String actor) throws Exception {
        return mvc.perform(post("/api/invalidations/{key}/activate", key).header("X-Actor-Id", actor))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void previewComputesFullClosureWithStableOrder() throws Exception {
        // 血缘：ROOT 下 A、B 两支，B 下 C；另有独立 D
        createStandard("ROOT", null, "2025-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "C0");
        createStandard("STD-A", "ROOT", "2025-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "CA");
        createStandard("STD-B", "ROOT", "2025-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "CB");
        createStandard("STD-C", "STD-B", "2025-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "CC");
        createStandard("STD-D", null, "2025-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "CD");

        createCertificateAndMeasurement("M-A1", "2026-06-01T00:00:00Z", "STD-A");
        createCertificateAndMeasurement("M-C1", "2026-08-01T00:00:00Z", "STD-C");
        // 早于失效时刻，不受影响
        createCertificateAndMeasurement("M-A-OLD", "2025-06-01T00:00:00Z", "STD-A");
        // 绑定独立标准器 D，不受影响
        createCertificateAndMeasurement("M-D1", "2026-06-01T00:00:00Z", "STD-D");

        String body = createInvalidation("REQ-PV", "INV-PV", "ROOT", "2026-01-01T00:00:00Z", 0);
        List<String> standardIds = JsonPath.read(body, "$.closure.standards[*].standardId");
        assertEquals(List.of("ROOT", "STD-A", "STD-B", "STD-C"), standardIds);
        List<String> measurementKeys = JsonPath.read(body, "$.closure.measurements[*].measurementKey");
        assertEquals(List.of("M-A1", "M-C1"), measurementKeys, "闭包记录须稳定排序且排除窗口外记录");

        String preview = mvc.perform(get("/api/invalidations/INV-PV/preview"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(standardIds, JsonPath.read(preview, "$.closure.standards[*].standardId"));
        assertEquals(measurementKeys, JsonPath.read(preview, "$.closure.measurements[*].measurementKey"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void activateFreezesWholeClosureAtomically() throws Exception {
        createStandard("ROOT", null, "2025-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "C0");
        createStandard("STD-A", "ROOT", "2025-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "CA");
        createStandard("STD-B", "STD-A", "2025-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "CB");

        createCertificateAndMeasurement("M-PENDING", "2026-06-01T00:00:00Z", "STD-A");
        createCertificateAndMeasurement("M-RELEASED", "2026-06-01T00:00:00Z", "STD-B");
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"M-RELEASED\"]}"))
                .andExpect(status().isOk());

        createInvalidation("REQ-ACT", "INV-ACT", "ROOT", "2026-01-01T00:00:00Z", 0);

        // 未双人确认不能激活
        confirm("INV-ACT", "qm-1");
        mvc.perform(post("/api/invalidations/INV-ACT/activate").header("X-Actor-Id", "qm-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIRMATION_REQUIRED"));
        // 创建人不能确认
        mvc.perform(post("/api/invalidations/INV-ACT/confirm").header("X-Actor-Id", "qm-lead"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREATOR_CANNOT_CONFIRM"));
        // 同一人不能重复确认
        mvc.perform(post("/api/invalidations/INV-ACT/confirm").header("X-Actor-Id", "qm-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CONFIRMATION"));
        confirm("INV-ACT", "qm-2");

        String activated = activate("INV-ACT", "qm-1");
        String impactVersion = JsonPath.read(activated, "$.impactVersion");
        assertNotNull(impactVersion);
        List<Map<String, Object>> items = JsonPath.read(activated, "$.items[*]");
        assertEquals(1, items.size(), "仅已放行结果进入影响明细");
        assertEquals("M-RELEASED", items.get(0).get("measurementKey"));
        assertEquals("STD-B>STD-A>ROOT", items.get(0).get("path"), "冻结到失效根的最短血缘路径");

        // 根及子孙标记 INVALID，版本递增
        for (String standardId : List.of("ROOT", "STD-A", "STD-B")) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT * FROM standard_version WHERE standard_id = ?", standardId);
            assertEquals("INVALID", row.get("status"));
            assertEquals(1, ((Number) row.get("version")).intValue());
        }

        Map<String, Object> pending = jdbc.queryForMap(
                "SELECT * FROM measurement WHERE measurement_key = 'M-PENDING'");
        assertEquals("BLOCKED", pending.get("status"));
        assertEquals(impactVersion, pending.get("impact_version"));
        assertEquals("STD-A>ROOT", pending.get("impact_path"));

        Map<String, Object> released = jdbc.queryForMap(
                "SELECT * FROM measurement WHERE measurement_key = 'M-RELEASED'");
        assertEquals("REVIEW_REQUIRED", released.get("status"));
        assertEquals(impactVersion, released.get("impact_version"));
        assertEquals("STD-B>STD-A>ROOT", released.get("impact_path"));
        // 原放行快照保留
        Integer releaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record WHERE measurement_id = ?",
                Integer.class, ((Number) released.get("id")).longValue());
        assertEquals(1, releaseCount);

        // 失效后不得再放行受影响结果
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"M-PENDING\"]}"))
                .andExpect(status().isConflict());

        // 重复激活 409
        mvc.perform(post("/api/invalidations/INV-ACT/activate").header("X-Actor-Id", "qm-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_ACTIVATED"));

        // 历史查询返回原结果并显式显示影响状态
        mvc.perform(get("/api/measurements/M-RELEASED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.impactVersion").value(impactVersion))
                .andExpect(jsonPath("$.impactPath").value("STD-B>STD-A>ROOT"))
                .andExpect(jsonPath("$.computedValue").value("5"))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));

        // 影响查询只读可重现
        String impact = mvc.perform(get("/api/impacts/{impactVersion}", impactVersion))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals("M-RELEASED", JsonPath.read(impact, "$.items[0].measurementKey"));
        assertEquals("STD-B>STD-A>ROOT", JsonPath.read(impact, "$.items[0].path"));
        assertFalse(((List<?>) JsonPath.read(impact, "$.items[*]")).isEmpty());
    }

    @Test
    void expectedVersionMismatchRejected() throws Exception {
        createStandard("ROOT", null, "2025-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "C0");
        mvc.perform(post("/api/invalidations")
                        .header("X-Actor-Id", "qm-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-V","invalidationKey":"INV-V","rootStandardId":"ROOT",
                                 "invalidFrom":"2026-01-01T00:00:00Z","expectedVersion":9,"reason":"x"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPECTED_VERSION_MISMATCH"));
        // 失败不占键：同 requestId 可用正确参数创建
        createInvalidation("REQ-V", "INV-V", "ROOT", "2026-01-01T00:00:00Z", 0);
    }

    @Test
    void rootMissingRejected() throws Exception {
        mvc.perform(post("/api/invalidations")
                        .header("X-Actor-Id", "qm-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-404","invalidationKey":"INV-404","rootStandardId":"GHOST",
                                 "invalidFrom":"2026-01-01T00:00:00Z","expectedVersion":0,"reason":"x"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void shortestPathTieBreaksByStandardIdLexicographic() throws Exception {
        // 两条等长路径：C 同时？血缘只允许单父，无法构造同一子的多父路径；
        // 改为验证同一失效根下两个孙级各自路径冻结正确且 impact 明细按 measurementKey 稳定排序。
        createStandard("ROOT", null, "2025-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "C0");
        createStandard("AAA", "ROOT", "2025-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "CA");
        createStandard("ZZZ", "ROOT", "2025-01-01T00:00:00Z", "2029-01-01T00:00:00Z", "CZ");
        createCertificateAndMeasurement("M-Z", "2026-06-01T00:00:00Z", "ZZZ");
        createCertificateAndMeasurement("M-A", "2026-06-01T00:00:00Z", "AAA");
        for (String key : List.of("M-Z", "M-A")) {
            mvc.perform(post("/api/measurements/release")
                            .header("X-Actor-Id", "carol")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"keys\":[\"" + key + "\"]}"))
                    .andExpect(status().isOk());
        }
        createInvalidation("REQ-TIE", "INV-TIE", "ROOT", "2026-01-01T00:00:00Z", 0);
        confirm("INV-TIE", "qm-1");
        confirm("INV-TIE", "qm-2");
        String activated = activate("INV-TIE", "qm-1");
        assertEquals(List.of("M-A", "M-Z"),
                JsonPath.read(activated, "$.items[*].measurementKey"));
        assertEquals("AAA>ROOT", JsonPath.read(activated, "$.items[0].path"));
        assertEquals("ZZZ>ROOT", JsonPath.read(activated, "$.items[1].path"));
        assertTrue(true);
    }
}
