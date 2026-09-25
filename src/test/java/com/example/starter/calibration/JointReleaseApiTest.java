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
 * 跨仪器联合批次放行测试：混合仪器原子放行、422 逐条原因与整批回滚、
 * 批次参数校验、jointBatchKey 幂等（同参重放快照/异参 409/失败不占键）、
 * 联合放行记录查询及撤销后记录不可变。
 */
@SpringBootTest
@AutoConfigureMockMvc
class JointReleaseApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM joint_release_item");
        jdbc.update("DELETE FROM joint_release_batch");
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

    private void submit(String key, String instrument, String reading, String lower,
                        String upper, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s",
                                 "measuredAt":"2026-06-01T00:00:00Z","reading":"%s",
                                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                                """.formatted(key, instrument, reading, lower, upper, by)))
                .andExpect(status().isCreated());
    }

    @Test
    void mixedInstrumentBatchReleasedAtomically() throws Exception {
        long certA = createCert("INS-A", "1", "0");
        long certB = createCert("INS-B", "2", "1");
        submit("J-1", "INS-A", "1", "0", "9", "alice");
        submit("J-2", "INS-B", "3", "0", "9", "bob");

        // 换序提交，响应中 released 按字典序稳定输出
        mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"JB-1\",\"keys\":[\"J-2\",\"J-1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jointBatchKey").value("JB-1"))
                .andExpect(jsonPath("$.releasedBy").value("carol"))
                .andExpect(jsonPath("$.releasedAt").isString())
                .andExpect(jsonPath("$.released[0]").value("J-1"))
                .andExpect(jsonPath("$.released[1]").value("J-2"));

        // 每条测量自身放行历史与既有单条查询兼容，批次 ID 即联合批次键
        mvc.perform(get("/api/measurements/{key}", "J-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.releases[0].batchId").value("JB-1"))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));
        mvc.perform(get("/api/measurements/{key}", "J-2"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releases[0].batchId").value("JB-1"));

        // 按联合批次键查询全部关联测量明细：固化证书与计算值快照，稳定排序
        mvc.perform(get("/api/joint-releases/{key}", "JB-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jointBatchKey").value("JB-1"))
                .andExpect(jsonPath("$.releasedBy").value("carol"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].measurementKey").value("J-1"))
                .andExpect(jsonPath("$.items[0].certificateId").value((int) certA))
                .andExpect(jsonPath("$.items[0].computedValue").value("1"))
                .andExpect(jsonPath("$.items[1].measurementKey").value("J-2"))
                .andExpect(jsonPath("$.items[1].certificateId").value((int) certB))
                .andExpect(jsonPath("$.items[1].computedValue").value("7"));

        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void invalidBatchRejectsWholeBatchWith422AndItemReasons() throws Exception {
        createCert("INS-A", "1", "0");
        createCert("INS-B", "1", "0");
        submit("J-OK", "INS-A", "1", "0", "9", "alice");   // 合格但提交人=放行人
        submit("J-FAIL", "INS-B", "100", "0", "9", "bob"); // 不合格
        submit("J-GOOD", "INS-A", "2", "0", "9", "bob");   // 合法可放行

        mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"JB-2\",\"keys\":[\"J-OK\",\"J-FAIL\",\"J-GOOD\",\"J-MISSING\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("JOINT_BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures[?(@.key=='J-OK')].reasons[0]").value("SAME_ACTOR"))
                .andExpect(jsonPath("$.failures[?(@.key=='J-FAIL')].reasons[0]").value("NOT_PASSED"))
                .andExpect(jsonPath("$.failures[?(@.key=='J-MISSING')].reasons[0]")
                        .value("MEASUREMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.failures.length()").value(3));

        // 整批回滚：J-GOOD 仍为待放行，无任何放行记录与联合批次记录
        mvc.perform(get("/api/measurements/{key}", "J-GOOD"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_batch", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_item", Integer.class));
    }

    @Test
    void revokedCertificateRejectsWholeBatchWith422() throws Exception {
        long certA = createCert("INS-A", "1", "0");
        createCert("INS-B", "1", "0");
        submit("J-1", "INS-A", "1", "0", "9", "alice");
        submit("J-2", "INS-B", "2", "0", "9", "bob");
        mvc.perform(post("/api/certificates/{id}/revoke", certA)).andExpect(status().isOk());

        mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"JB-3\",\"keys\":[\"J-1\",\"J-2\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[?(@.key=='J-1')].reasons[0]")
                        .value("CERTIFICATE_REVOKED"));

        mvc.perform(get("/api/measurements/{key}", "J-2"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_batch", Integer.class));
    }

    @Test
    void batchParameterValidation() throws Exception {
        createCert("INS-A", "1", "0");
        submit("J-1", "INS-A", "1", "0", "9", "alice");
        submit("J-2", "INS-A", "2", "0", "9", "bob");

        // 少于 2 条 → 400
        mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"JB-4\",\"keys\":[\"J-1\"]}"))
                .andExpect(status().isBadRequest());

        // 超过 20 条 → 400
        String big = java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> "\"K" + i + "\"").toList().toString();
        mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"JB-4\",\"keys\":" + big + "}"))
                .andExpect(status().isBadRequest());

        // 批次内重复键 → 400
        mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"JB-4\",\"keys\":[\"J-1\",\"J-1\"]}"))
                .andExpect(status().isBadRequest());

        // 缺少 jointBatchKey → 400
        mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"J-1\",\"J-2\"]}"))
                .andExpect(status().isBadRequest());

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/joint-releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"JB-4\",\"keys\":[\"J-1\",\"J-2\"]}"))
                .andExpect(status().isBadRequest());

        // 全部 400 不占键、不放行
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_batch", Integer.class));
        mvc.perform(get("/api/measurements/{key}", "J-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void idempotentReplaySameKeySameParamsReturnsSnapshot() throws Exception {
        createCert("INS-A", "1", "0");
        createCert("INS-B", "1", "0");
        submit("J-1", "INS-A", "1", "0", "9", "alice");
        submit("J-2", "INS-B", "2", "0", "9", "bob");

        String first = mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"JB-5\",\"keys\":[\"J-1\",\"J-2\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String firstReleasedAt = com.jayway.jsonpath.JsonPath.read(first, "$.releasedAt");

        // 同键同参（测量集合换序）重放：返回首次响应快照，不重复放行
        mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"JB-5\",\"keys\":[\"J-2\",\"J-1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releasedAt").value(firstReleasedAt))
                .andExpect(jsonPath("$.released[0]").value("J-1"))
                .andExpect(jsonPath("$.released[1]").value("J-2"));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_batch", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_item", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));

        // 同键异参（测量集合不同）→ 409
        mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"JB-5\",\"keys\":[\"J-1\"]}"))
                .andExpect(status().isBadRequest()); // 条数非法先于幂等裁决
        submit("J-3", "INS-A", "3", "0", "9", "alice");
        mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"JB-5\",\"keys\":[\"J-1\",\"J-3\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOINT_BATCH_KEY_CONFLICT"));

        // 同键异参（放行人不同）→ 409
        mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"JB-5\",\"keys\":[\"J-2\",\"J-1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOINT_BATCH_KEY_CONFLICT"));
    }

    @Test
    void failedAttemptDoesNotOccupyKey() throws Exception {
        createCert("INS-A", "1", "0");
        submit("J-1", "INS-A", "1", "0", "9", "alice");
        submit("J-2", "INS-A", "2", "0", "9", "bob");

        // 首次失败（SAME_ACTOR）→ 422，不占键
        mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"JB-6\",\"keys\":[\"J-1\",\"J-2\"]}"))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_batch", Integer.class));

        // 同一 jointBatchKey 修正放行人后成功
        mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"JB-6\",\"keys\":[\"J-1\",\"J-2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jointBatchKey").value("JB-6"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_batch", Integer.class));
    }

    @Test
    void jointRecordImmutableAfterCertificateRevoked() throws Exception {
        long certA = createCert("INS-A", "1", "0");
        createCert("INS-B", "1", "0");
        submit("J-1", "INS-A", "1", "0", "9", "alice");
        submit("J-2", "INS-B", "2", "0", "9", "bob");
        mvc.perform(post("/api/joint-releases")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jointBatchKey\":\"JB-7\",\"keys\":[\"J-1\",\"J-2\"]}"))
                .andExpect(status().isOk());

        // 放行提交后撤销证书：不追溯改写已完成的联合放行记录
        mvc.perform(post("/api/certificates/{id}/revoke", certA)).andExpect(status().isOk());
        mvc.perform(get("/api/joint-releases/{key}", "JB-7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].measurementKey").value("J-1"))
                .andExpect(jsonPath("$.items[0].certificateId").value((int) certA))
                .andExpect(jsonPath("$.items[0].computedValue").value("1"));

        // 测量历史保留、状态仍为 RELEASED，但失去当前可用资格
        mvc.perform(get("/api/measurements/{key}", "J-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.releases[0].batchId").value("JB-7"));
        mvc.perform(get("/api/measurements/usable"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].measurementKey").value("J-2"));
    }

    @Test
    void detailUnknownJointBatchReturns404() throws Exception {
        mvc.perform(get("/api/joint-releases/{key}", "NO-SUCH-BATCH"))
                .andExpect(status().isNotFound());
    }
}
