package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 参考漂移放行测试：撤销任一参考后不可新放行、旧详情仍保留原值与历史、
 * 当前可用查询同时核对证书与两端档案三份证据、批量放行原子拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DriftReleaseApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM drift_reference_archive");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM request_dedup");
    }

    private long createCert(String instrument) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}
                                """.formatted(instrument)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private long createArchive(String instrument, String at, String offset) throws Exception {
        String body = mvc.perform(post("/api/drift-archives").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","referencedAt":"%s","offset":"%s"}
                                """.formatted(instrument, at, offset)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void submitDrift(String key, String instrument, long a0, long a1) throws Exception {
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T10:30:00Z","reading":"5",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice",
                                 "archiveId0":%d,"archiveId1":%d}
                                """.formatted(key, instrument, a0, a1)))
                .andExpect(status().isCreated());
    }

    private void release(String actor, String keysJson) throws Exception {
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":" + keysJson + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void revokeReferenceBlocksNewReleaseButKeepsOldDetail() throws Exception {
        // 合成数据：证书 a=1、b=0，两参考 10:00 偏 0、11:00 偏 2，10:30 原值 5 得 4
        createCert("INS-D");
        long a0 = createArchive("INS-D", "2026-06-01T10:00:00Z", "0");
        long a1 = createArchive("INS-D", "2026-06-01T11:00:00Z", "2");
        submitDrift("D-OLD", "INS-D", a0, a1);
        submitDrift("D-NEW", "INS-D", a0, a1);
        release("carol", "[\"D-OLD\"]");

        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(1));

        // 撤销任一参考：当前可用立即失效，但旧详情仍为 4，历史保留
        mvc.perform(post("/api/drift-archives/{id}/revoke", a1)).andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/measurements/{key}", "D-OLD"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.correctedValue").value("4"))
                .andExpect(jsonPath("$.displayValue").value("4.0000"))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));

        // 撤销后不可新放行：整批 409，原因 REFERENCE_REVOKED，测量保持待放行
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"D-NEW\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].key").value("D-NEW"))
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("REFERENCE_REVOKED"));
        mvc.perform(get("/api/measurements/{key}", "D-NEW"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 撤销后也不可用撤销档案提交新测量
        mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"D-AFTER","instrumentId":"INS-D",
                                 "measuredAt":"2026-06-01T10:30:00Z","reading":"5",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"alice",
                                 "archiveId0":%d,"archiveId1":%d}
                                """.formatted(a0, a1)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void batchWithRevokedReferenceRejectsWholeBatchAtomically() throws Exception {
        createCert("INS-D");
        long a0 = createArchive("INS-D", "2026-06-01T10:00:00Z", "0");
        long a1 = createArchive("INS-D", "2026-06-01T11:00:00Z", "2");
        submitDrift("D-1", "INS-D", a0, a1);

        // 普通测量（同证书）作为同批合法项
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"S-1","instrumentId":"INS-D",
                                 "measuredAt":"2026-06-01T10:30:00Z","reading":"1",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"bob"}"""))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/drift-archives/{id}/revoke", a0)).andExpect(status().isOk());

        // 任一项证据失效 → 整批不放，合法项也不放行
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"D-1\",\"S-1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[?(@.key=='D-1')].reasons[0]")
                        .value("REFERENCE_REVOKED"));
        mvc.perform(get("/api/measurements/{key}", "S-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(0, releaseCount, "整批拒绝不得产生任何放行历史");
    }

    @Test
    void revokedCertificateAlsoBlocksDriftRelease() throws Exception {
        long certId = createCert("INS-D");
        long a0 = createArchive("INS-D", "2026-06-01T10:00:00Z", "0");
        long a1 = createArchive("INS-D", "2026-06-01T11:00:00Z", "2");
        submitDrift("D-1", "INS-D", a0, a1);

        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"D-1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("CERTIFICATE_REVOKED"));
    }

    @Test
    void archiveCrudAndDoubleRevoke() throws Exception {
        long a0 = createArchive("INS-D", "2026-06-01T10:00:00Z", "0");
        mvc.perform(get("/api/drift-archives/{id}", a0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offset").value("0"))
                .andExpect(jsonPath("$.revoked").value(false));
        mvc.perform(post("/api/drift-archives/{id}/revoke", a0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.revokedAt").isString());
        mvc.perform(post("/api/drift-archives/{id}/revoke", a0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVOKED"));
        mvc.perform(get("/api/drift-archives/{id}", 999999L))
                .andExpect(status().isNotFound());
    }
}
