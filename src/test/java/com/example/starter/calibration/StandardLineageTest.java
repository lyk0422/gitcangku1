package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 标准器血缘测试：创建校验（父级存在、窗口含于父级、无环、业务键唯一）与测量提交绑定当时有效版本。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StandardLineageTest {

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
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.standardId").value(standardId))
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void createLineageAndQuery() throws Exception {
        createStandard("STD-A", null, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "CERT-A-1");
        createStandard("STD-B", "STD-A", "2026-02-01T00:00:00Z", "2026-12-01T00:00:00Z", "CERT-B-1");
        createStandard("STD-C", "STD-B", "2026-03-01T00:00:00Z", "2026-11-01T00:00:00Z", "CERT-C-1");

        mvc.perform(get("/api/standards/STD-C"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentStandardId").value("STD-B"))
                .andExpect(jsonPath("$.certificateNo").value("CERT-C-1"));
        mvc.perform(get("/api/standards/STD-NONE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void childWindowExceedingParentRejected() throws Exception {
        createStandard("STD-A", null, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "CERT-A-1");
        // 起点早于父级
        mvc.perform(post("/api/standards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"standardId":"STD-B1","parentStandardId":"STD-A",
                                 "validFrom":"2025-12-01T00:00:00Z","validTo":"2026-06-01T00:00:00Z",
                                 "certificateNo":"CERT-B1"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WINDOW_EXCEEDS_PARENT"));
        // 终点晚于父级
        mvc.perform(post("/api/standards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"standardId":"STD-B2","parentStandardId":"STD-A",
                                 "validFrom":"2026-06-01T00:00:00Z","validTo":"2027-06-01T00:00:00Z",
                                 "certificateNo":"CERT-B2"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WINDOW_EXCEEDS_PARENT"));
    }

    @Test
    void parentMissingAndSelfCycleAndDuplicateRejected() throws Exception {
        mvc.perform(post("/api/standards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"standardId":"STD-X","parentStandardId":"STD-GHOST",
                                 "validFrom":"2026-01-01T00:00:00Z","validTo":"2027-01-01T00:00:00Z",
                                 "certificateNo":"CERT-X"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PARENT_STANDARD_NOT_FOUND"));

        mvc.perform(post("/api/standards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"standardId":"STD-X","parentStandardId":"STD-X",
                                 "validFrom":"2026-01-01T00:00:00Z","validTo":"2027-01-01T00:00:00Z",
                                 "certificateNo":"CERT-X"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LINEAGE_CYCLE"));

        createStandard("STD-X", null, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "CERT-X");
        mvc.perform(post("/api/standards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"standardId":"STD-X","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","certificateNo":"CERT-X2"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_STANDARD_ID"));
    }

    @Test
    void invalidWindowRejected() throws Exception {
        mvc.perform(post("/api/standards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"standardId":"STD-BAD","validFrom":"2027-01-01T00:00:00Z",
                                 "validTo":"2026-01-01T00:00:00Z","certificateNo":"CERT-BAD"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("validFrom")));
    }

    @Test
    void measurementBindsValidStandardVersion() throws Exception {
        createStandard("STD-A", null, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", "CERT-A-1");
        mvc.perform(post("/api/certificates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-S","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"2","b":"1"}"""))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-S1","instrumentId":"INS-S",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"3",
                                 "lowerLimit":"0","upperLimit":"10","submittedBy":"alice",
                                 "standardId":"STD-A"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.standardId").value("STD-A"))
                .andExpect(jsonPath("$.computedValue").value("7"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 测量时刻不在标准器窗口内 → 422
        mvc.perform(post("/api/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-S2","instrumentId":"INS-S",
                                 "measuredAt":"2028-06-01T00:00:00Z","reading":"3",
                                 "lowerLimit":"0","upperLimit":"10","submittedBy":"alice",
                                 "standardId":"STD-A"}"""))
                .andExpect(status().isUnprocessableEntity());

        // 标准器不存在 → 422
        mvc.perform(post("/api/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-S3","instrumentId":"INS-S",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"3",
                                 "lowerLimit":"0","upperLimit":"10","submittedBy":"alice",
                                 "standardId":"STD-GHOST"}"""))
                .andExpect(status().isUnprocessableEntity());

        // 不提供 standardId 时保持原有行为（不绑定）
        mvc.perform(post("/api/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"M-S4","instrumentId":"INS-S",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"3",
                                 "lowerLimit":"0","upperLimit":"10","submittedBy":"alice"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.standardId").doesNotExist());
    }
}
