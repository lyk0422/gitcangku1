package com.example.starter.calibration;

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
 * 标准器血缘与失效预览测试：血缘创建（窗口/环校验）、测量绑定有效版本、
 * 预览沿血缘向下计算完整闭包且稳定排序、不写数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StandardLineageApiTest {

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

    private void createStandard(String standardId) throws Exception {
        mvc.perform(post("/api/standards").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"standardId\":\"" + standardId + "\",\"name\":\"标准器 " + standardId + "\"}"))
                .andExpect(status().isCreated());
    }

    private void createVersion(String versionKey, String standardId, String parentVersionKey,
                               String validFrom, String validTo, String certNo) throws Exception {
        String parent = parentVersionKey == null ? "null" : "\"" + parentVersionKey + "\"";
        mvc.perform(post("/api/standards/versions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"versionKey":"%s","standardId":"%s","parentVersionKey":%s,
                                 "validFrom":"%s","validTo":"%s","certificateNo":"%s"}
                                """.formatted(versionKey, standardId, parent, validFrom, validTo, certNo)))
                .andExpect(status().isCreated());
    }

    private long createCert(String instrument) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void submit(String key, String instrument, String measuredAt,
                        String standardVersionKey) throws Exception {
        String sv = standardVersionKey == null ? "null" : "\"" + standardVersionKey + "\"";
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"%s","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice",
                                 "standardVersionKey":%s}
                                """.formatted(key, instrument, measuredAt, sv)))
                .andExpect(status().isCreated());
    }

    /** 读取当前领域版本号（预览响应中的 domainVersion）。 */
    private long currentDomainVersion() throws Exception {
        String body = mvc.perform(post("/api/invalidations/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invalidationKey":"PROBE","rootVersionKey":"SV-ROOT",
                                 "effectiveFrom":"2026-06-01T00:00:00Z","reason":"探测"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.domainVersion")).longValue();
    }

    private void buildLineage() throws Exception {
        createStandard("STD-ROOT");
        createStandard("STD-MID");
        createStandard("STD-LEAF");
        createVersion("SV-ROOT", "STD-ROOT", null,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "CERT-R1");
        createVersion("SV-MID", "STD-MID", "SV-ROOT",
                "2026-02-01T00:00:00Z", "2026-12-01T00:00:00Z", "CERT-M1");
        createVersion("SV-LEAF", "STD-LEAF", "SV-MID",
                "2026-03-01T00:00:00Z", "2026-11-01T00:00:00Z", "CERT-L1");
    }

    @Test
    void lineageCreatedWithWindowAndCertificate() throws Exception {
        buildLineage();
        mvc.perform(get("/api/standards/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.versionKey=='SV-LEAF')].certificateNo").value("CERT-L1"))
                .andExpect(jsonPath("$[?(@.versionKey=='SV-LEAF')].status").value("VALID"));
    }

    @Test
    void childWindowOutsideParentRejected() throws Exception {
        createStandard("STD-ROOT");
        createStandard("STD-MID");
        createVersion("SV-ROOT", "STD-ROOT", null,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "CERT-R1");

        // 子窗口起点早于父窗口 → 422
        mvc.perform(post("/api/standards/versions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"versionKey":"SV-BAD1","standardId":"STD-MID","parentVersionKey":"SV-ROOT",
                                 "validFrom":"2025-12-01T00:00:00Z","validTo":"2026-06-01T00:00:00Z",
                                 "certificateNo":"CERT-X"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LINEAGE_INVALID"));

        // 子窗口终点晚于父窗口 → 422
        mvc.perform(post("/api/standards/versions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"versionKey":"SV-BAD2","standardId":"STD-MID","parentVersionKey":"SV-ROOT",
                                 "validFrom":"2026-06-01T00:00:00Z","validTo":"2027-06-01T00:00:00Z",
                                 "certificateNo":"CERT-X"}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 父版本不存在 → 404
        mvc.perform(post("/api/standards/versions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"versionKey":"SV-BAD3","standardId":"STD-MID","parentVersionKey":"SV-NOPE",
                                 "validFrom":"2026-06-01T00:00:00Z","validTo":"2026-07-01T00:00:00Z",
                                 "certificateNo":"CERT-X"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateVersionKeyRejected() throws Exception {
        createStandard("STD-ROOT");
        createVersion("SV-ROOT", "STD-ROOT", null,
                "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "CERT-R1");
        mvc.perform(post("/api/standards/versions").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"versionKey":"SV-ROOT","standardId":"STD-ROOT","parentVersionKey":null,
                                 "validFrom":"2026-01-01T00:00:00Z","validTo":"2027-01-01T00:00:00Z",
                                 "certificateNo":"CERT-R2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_VERSION_KEY"));
    }

    @Test
    void measurementBindsValidStandardVersion() throws Exception {
        buildLineage();
        createCert("INS-1");
        submit("M-1", "INS-1", "2026-06-01T00:00:00Z", "SV-LEAF");

        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.standardVersionId").isNumber())
                .andExpect(jsonPath("$.impactVersion").doesNotExist());
    }

    @Test
    void measurementOutsideVersionWindowRejected() throws Exception {
        buildLineage();
        createCert("INS-1");

        // 测量时刻早于版本窗口 → 422
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-EARLY","instrumentId":"INS-1",
                                 "measuredAt":"2026-01-15T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice",
                                 "standardVersionKey":"SV-LEAF"}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 版本键不存在 → 404
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-NOSUCH","instrumentId":"INS-1",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice",
                                 "standardVersionKey":"SV-NOPE"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void previewComputesFullClosureWithoutWriting() throws Exception {
        buildLineage();
        createCert("INS-1");
        submit("M-LEAF", "INS-1", "2026-06-01T00:00:00Z", "SV-LEAF");
        submit("M-MID", "INS-1", "2026-06-01T00:00:00Z", "SV-MID");
        submit("M-ROOT", "INS-1", "2026-06-01T00:00:00Z", "SV-ROOT");
        submit("M-BEFORE", "INS-1", "2026-04-01T00:00:00Z", "SV-LEAF"); // 失效起始前，不受影响
        submit("M-UNBOUND", "INS-1", "2026-06-01T00:00:00Z", null);      // 未绑定标准器，不受影响

        long orderCountBefore = count("invalidation_order");
        long snapshotCountBefore = count("invalidation_snapshot");

        // 以 SV-MID 为失效根，失效起始 2026-06-01：闭包含 SV-MID、SV-LEAF，不含 SV-ROOT
        mvc.perform(post("/api/invalidations/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"invalidationKey":"INV-P","rootVersionKey":"SV-MID",
                                 "effectiveFrom":"2026-06-01T00:00:00Z","reason":"预览"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invalidationKey").doesNotExist())
                .andExpect(jsonPath("$.standards.length()").value(2))
                .andExpect(jsonPath("$.standards[0].versionKey").value("SV-LEAF"))
                .andExpect(jsonPath("$.standards[1].versionKey").value("SV-MID"))
                .andExpect(jsonPath("$.measurements.length()").value(2))
                .andExpect(jsonPath("$.measurements[0].measurementKey").value("M-LEAF"))
                .andExpect(jsonPath("$.measurements[1].measurementKey").value("M-MID"))
                .andExpect(jsonPath("$.measurements[0].impactPath.length()").value(2))
                .andExpect(jsonPath("$.measurements[1].impactPath.length()").value(1));

        // 预览不写数据
        org.junit.jupiter.api.Assertions.assertEquals(orderCountBefore, count("invalidation_order"));
        org.junit.jupiter.api.Assertions.assertEquals(snapshotCountBefore, count("invalidation_snapshot"));
    }

    private long count(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
