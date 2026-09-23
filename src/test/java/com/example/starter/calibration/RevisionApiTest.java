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
 * 后继修订测试：仅原提交人可为 REJECTED 测量创建单一后继修订，
 * 保留证书与原始输入、仅修改测量值及说明、重新计算结果、版本从 1 开始，修订链只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RevisionApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_lineage");
        jdbc.update("DELETE FROM batch_snapshot");
        jdbc.update("DELETE FROM review_record");
        jdbc.update("DELETE FROM release_batch");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument, String a, String b) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2028-01-01T00:00:00Z","a":"%s","b":"%s"}
                                """.formatted(instrument, a, b)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();
    }

    private void submit(String key, String instrument, String reading, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                                 "lowerLimit":"0","upperLimit":"9","submittedBy":"%s"}
                                """.formatted(key, instrument, reading, by)))
                .andExpect(status().isCreated());
    }

    private String release(String actor, String... keys) throws Exception {
        StringBuilder keyJson = new StringBuilder();
        for (String key : keys) {
            if (keyJson.length() > 0) {
                keyJson.append(',');
            }
            keyJson.append('"').append(key).append('"');
        }
        String body = mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[" + keyJson + "]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.batchId");
    }

    private void review(String reviewKey, String batchId, String key, int version) throws Exception {
        mvc.perform(post("/api/reviews")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewKey":"%s","batchId":"%s",
                                 "items":[{"measurementKey":"%s","version":%d,"reason":"bad"}]}
                                """.formatted(reviewKey, batchId, key, version)))
                .andExpect(status().isOk());
    }

    @Test
    void revisionRecomputesAndKeepsCertificateAndOriginalInputs() throws Exception {
        long certId = createCert("INS-1", "2", "1");
        submit("M-1", "INS-1", "3", "alice");   // 2×3+1=7，合格
        String batchId = release("carol", "M-1");
        review("RV-1", batchId, "M-1", 0);

        // 原提交人创建后继修订：仅修改测量值及说明
        mvc.perform(post("/api/measurements/{key}/revisions", "M-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reading\":\"4\",\"note\":\"corrected\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.measurementKey").value("M-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.reading").value("4"))
                .andExpect(jsonPath("$.note").value("corrected"))
                .andExpect(jsonPath("$.computedValue").value("9"))   // 2×4+1 重新计算
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.certificateId").value(certId))  // 证书保留
                .andExpect(jsonPath("$.instrumentId").value("INS-1"))
                .andExpect(jsonPath("$.measuredAt").value("2026-06-01T00:00:00Z"))
                .andExpect(jsonPath("$.submittedBy").value("alice"));

        // 前驱保持 REJECTED 且内容不可变
        mvc.perform(get("/api/measurements/{key}", "M-1").param("version", "0"))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reading").value("3"))
                .andExpect(jsonPath("$.computedValue").value("7"));

        // 修订链只读查询：v0 → v1
        mvc.perform(get("/api/measurements/{key}/revisions", "M-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(0))
                .andExpect(jsonPath("$[0].status").value("REJECTED"))
                .andExpect(jsonPath("$[0].predecessorId").doesNotExist())
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].status").value("PENDING"))
                .andExpect(jsonPath("$[1].reading").value("4"))
                .andExpect(jsonPath("$[1].note").value("corrected"))
                .andExpect(jsonPath("$[1].predecessorId").isNumber());

        // 明细默认返回最新版本
        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void revisionOnlyByOriginalSubmitter() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "3", "alice");
        String batchId = release("carol", "M-1");
        review("RV-1", batchId, "M-1", 0);

        mvc.perform(post("/api/measurements/{key}/revisions", "M-1")
                        .header("X-Actor-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reading\":\"4\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_ORIGINAL_SUBMITTER"));
    }

    @Test
    void revisionRequiresRejectedPredecessor() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "3", "alice");

        // 待放行状态不可修订
        mvc.perform(post("/api/measurements/{key}/revisions", "M-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reading\":\"4\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_REJECTED"));

        // 已放行但未驳回不可修订
        release("carol", "M-1");
        mvc.perform(post("/api/measurements/{key}/revisions", "M-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reading\":\"4\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_REJECTED"));

        // 前驱不存在 → 404
        mvc.perform(post("/api/measurements/{key}/revisions", "M-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":7,\"reading\":\"4\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/measurements/{key}/revisions", "NO-SUCH")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reading\":\"4\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void singleSuccessorPerRejectedMeasurement() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "3", "alice");
        String batchId = release("carol", "M-1");
        review("RV-1", batchId, "M-1", 0);

        mvc.perform(post("/api/measurements/{key}/revisions", "M-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reading\":\"4\"}"))
                .andExpect(status().isCreated());

        // 同一前驱只能有一个直接后继修订
        mvc.perform(post("/api/measurements/{key}/revisions", "M-1")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reading\":\"5\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_EXISTS"));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key = 'M-1'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(2, count);
    }

    @Test
    void revisionChainOfUnknownKeyReturns404() throws Exception {
        mvc.perform(get("/api/measurements/{key}/revisions", "NO-SUCH"))
                .andExpect(status().isNotFound());
    }
}
