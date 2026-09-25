package com.example.starter.calibration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跨仪器联合批次放行测试（真实 H2 内存库，MODE=MySQL）：
 * 跨仪器一致放行与不可变记录、422 逐条原因与整批回滚、requestId 幂等重放/异参冲突、
 * jointBatchKey 全局唯一、放行后证书撤销不追溯、数量与请求头校验、按批次查询。
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

    private ResultActions jointRelease(String actor, String body) throws Exception {
        return mvc.perform(post("/api/joint-releases")
                .header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void crossInstrumentJointBatchReleasedAtomicallyAndQueryable() throws Exception {
        createCert("INS-A", "2", "1"); // computed = 2×reading+1
        createCert("INS-B", "1", "0");
        submit("A-1", "INS-A", "1", "0", "9", "alice"); // computed 3
        submit("B-1", "INS-B", "2", "0", "9", "bob");   // computed 2

        jointRelease("carol", """
                {"jointBatchKey":"JB-1","requestId":"REQ-1","keys":["A-1","B-1"]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jointBatchKey").value("JB-1"))
                .andExpect(jsonPath("$.requestId").value("REQ-1"))
                .andExpect(jsonPath("$.releasedBy").value("carol"))
                .andExpect(jsonPath("$.releasedAt").isString())
                .andExpect(jsonPath("$.released[0]").value("A-1"))
                .andExpect(jsonPath("$.released[1]").value("B-1"));

        // 每条测量均已放行
        mvc.perform(get("/api/measurements/{key}", "A-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.computedValue").value("3"))
                .andExpect(jsonPath("$.releases[0].batchId").value("JB-1"))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("carol"));
        mvc.perform(get("/api/measurements/{key}", "B-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"));

        // 可通过 jointBatchKey 查询该批全部关联测量，明细按测量键字典序稳定排序并固化快照
        mvc.perform(get("/api/joint-releases/{jointBatchKey}", "JB-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jointBatchKey").value("JB-1"))
                .andExpect(jsonPath("$.requestId").value("REQ-1"))
                .andExpect(jsonPath("$.releasedBy").value("carol"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].measurementKey").value("A-1"))
                .andExpect(jsonPath("$.items[0].instrumentId").value("INS-A"))
                .andExpect(jsonPath("$.items[0].computedValue").value("3"))
                .andExpect(jsonPath("$.items[0].releasedBy").value("carol"))
                .andExpect(jsonPath("$.items[1].measurementKey").value("B-1"))
                .andExpect(jsonPath("$.items[1].instrumentId").value("INS-B"))
                .andExpect(jsonPath("$.items[1].computedValue").value("2"));

        Integer jointBatches = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_release_batch WHERE joint_batch_key = 'JB-1'", Integer.class);
        Integer jointItems = jdbc.queryForObject(
                "SELECT COUNT(*) FROM joint_release_item WHERE joint_batch_key = 'JB-1'", Integer.class);
        Integer history = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_record WHERE batch_id = 'JB-1'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, jointBatches);
        org.junit.jupiter.api.Assertions.assertEquals(2, jointItems);
        org.junit.jupiter.api.Assertions.assertEquals(2, history, "既有单条放行历史必须同步写入并兼容查询");
    }

    @Test
    void invalidItemsReturn422WithPerItemReasonsAndNoPartialRelease() throws Exception {
        createCert("INS-A", "1", "0");
        long certB = createCert("INS-B", "1", "0");
        submit("A-OK", "INS-A", "1", "0", "9", "alice");     // 合格，放行人 carol 可放行
        submit("A-FAIL", "INS-A", "100", "0", "9", "bob");   // 不合格
        submit("A-SELF", "INS-A", "1", "0", "9", "carol");   // 放行人=提交人
        submit("B-REVOKED", "INS-B", "1", "0", "9", "bob");  // 证书将被撤销
        mvc.perform(post("/api/certificates/{id}/revoke", certB)).andExpect(status().isOk());

        jointRelease("carol", """
                {"jointBatchKey":"JB-BAD","requestId":"REQ-BAD",
                 "keys":["A-OK","A-FAIL","A-SELF","B-REVOKED","MISSING"]}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("JOINT_BATCH_REJECTED"))
                .andExpect(jsonPath("$.failures.length()").value(4))
                .andExpect(jsonPath("$.failures[?(@.key=='A-FAIL')].reasons[0]").value("NOT_PASSED"))
                .andExpect(jsonPath("$.failures[?(@.key=='A-SELF')].reasons[0]").value("SAME_ACTOR"))
                .andExpect(jsonPath("$.failures[?(@.key=='B-REVOKED')].reasons[0]")
                        .value("CERTIFICATE_REVOKED"))
                .andExpect(jsonPath("$.failures[?(@.key=='MISSING')].reasons[0]")
                        .value("MEASUREMENT_NOT_FOUND"));

        // 整批回滚：合格项也未被放行，任何联合/历史记录都不写入（失败不占键）
        mvc.perform(get("/api/measurements/{key}", "A-OK"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.releases.length()").value(0));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_batch", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_item", Integer.class));
        org.junit.jupiter.api.Assertions.assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class));
    }

    @Test
    void alreadyReleasedItemRejected422() throws Exception {
        createCert("INS-A", "1", "0");
        createCert("INS-B", "1", "0");
        submit("A-1", "INS-A", "1", "0", "9", "alice");
        submit("B-1", "INS-B", "2", "0", "9", "bob");
        // 先用既有同仪器入口放行 A-1
        mvc.perform(post("/api/measurements/release").header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"A-1\"]}"))
                .andExpect(status().isOk());

        jointRelease("carol", """
                {"jointBatchKey":"JB-DUP","requestId":"REQ-DUP","keys":["A-1","B-1"]}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.failures[?(@.key=='A-1')].reasons[0]")
                        .value("ALREADY_RELEASED"));
        mvc.perform(get("/api/measurements/{key}", "B-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void sameRequestIdReorderedReplaysFirstSnapshot() throws Exception {
        createCert("INS-A", "1", "0");
        createCert("INS-B", "1", "0");
        submit("A-1", "INS-A", "1", "0", "9", "alice");
        submit("B-1", "INS-B", "2", "0", "9", "bob");

        String first = jointRelease("carol", """
                {"jointBatchKey":"JB-IDEM","requestId":"REQ-IDEM","keys":["B-1","A-1"]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released[0]").value("B-1"))
                .andExpect(jsonPath("$.released[1]").value("A-1"))
                .andReturn().getResponse().getContentAsString();
        String firstAt = com.jayway.jsonpath.JsonPath.read(first, "$.releasedAt");

        // 换序重放：视为同参，返回首次响应快照（含首次顺序与时刻）
        jointRelease("carol", """
                {"jointBatchKey":"JB-IDEM","requestId":"REQ-IDEM","keys":["A-1","B-1"]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jointBatchKey").value("JB-IDEM"))
                .andExpect(jsonPath("$.releasedAt").value(firstAt))
                .andExpect(jsonPath("$.released[0]").value("B-1"))
                .andExpect(jsonPath("$.released[1]").value("A-1"));

        org.junit.jupiter.api.Assertions.assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM joint_release_batch", Integer.class),
                "重放不得产生新的联合放行记录");
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() throws Exception {
        createCert("INS-A", "1", "0");
        createCert("INS-B", "1", "0");
        submit("A-1", "INS-A", "1", "0", "9", "alice");
        submit("A-2", "INS-A", "2", "0", "9", "alice");
        submit("B-1", "INS-B", "2", "0", "9", "bob");

        jointRelease("carol", """
                {"jointBatchKey":"JB-X1","requestId":"REQ-X","keys":["A-1","B-1"]}""")
                .andExpect(status().isOk());

        // 同 requestId 不同测量集合 → 409
        jointRelease("carol", """
                {"jointBatchKey":"JB-X1","requestId":"REQ-X","keys":["A-2","B-1"]}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_CONFLICT"));

        // 同 requestId 不同 jointBatchKey → 409
        jointRelease("carol", """
                {"jointBatchKey":"JB-X2","requestId":"REQ-X","keys":["A-1","B-1"]}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_CONFLICT"));
    }

    @Test
    void failureDoesNotOccupyJointBatchKeyOrRequestId() throws Exception {
        createCert("INS-A", "1", "0");
        createCert("INS-B", "1", "0");
        submit("A-1", "INS-A", "1", "0", "9", "alice");
        submit("B-1", "INS-B", "2", "0", "9", "bob");

        // 首次因不合格项 422 失败
        jointRelease("carol", """
                {"jointBatchKey":"JB-RETRY","requestId":"REQ-RETRY",
                 "keys":["A-1","B-1","MISSING"]}""")
                .andExpect(status().isUnprocessableEntity());

        // 失败不占键：同 jointBatchKey、同 requestId 以合法参数重试应成功
        jointRelease("carol", """
                {"jointBatchKey":"JB-RETRY","requestId":"REQ-RETRY","keys":["A-1","B-1"]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jointBatchKey").value("JB-RETRY"));
    }

    @Test
    void jointBatchKeyGloballyUniqueAcrossRequestIds() throws Exception {
        createCert("INS-A", "1", "0");
        createCert("INS-B", "1", "0");
        submit("A-1", "INS-A", "1", "0", "9", "alice");
        submit("A-2", "INS-A", "2", "0", "9", "alice");
        submit("B-1", "INS-B", "2", "0", "9", "bob");

        jointRelease("carol", """
                {"jointBatchKey":"JB-UNIQ","requestId":"REQ-U1","keys":["A-1","B-1"]}""")
                .andExpect(status().isOk());

        jointRelease("carol", """
                {"jointBatchKey":"JB-UNIQ","requestId":"REQ-U2","keys":["A-2","B-1"]}""")
                .andExpect(status().isConflict());
    }

    @Test
    void revokingCertificateAfterJointReleaseKeepsImmutableRecord() throws Exception {
        long certA = createCert("INS-A", "1", "0");
        createCert("INS-B", "1", "0");
        submit("A-1", "INS-A", "1", "0", "9", "alice");
        submit("B-1", "INS-B", "2", "0", "9", "bob");

        jointRelease("carol", """
                {"jointBatchKey":"JB-REV","requestId":"REQ-REV","keys":["A-1","B-1"]}""")
                .andExpect(status().isOk());

        // 放行提交成功后撤销证书：不追溯改写已完成的联合放行记录
        mvc.perform(post("/api/certificates/{id}/revoke", certA)).andExpect(status().isOk());

        mvc.perform(get("/api/joint-releases/{jointBatchKey}", "JB-REV"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].measurementKey").value("A-1"))
                .andExpect(jsonPath("$.items[0].certificateId").value(certA))
                .andExpect(jsonPath("$.items[0].computedValue").value("1"));

        // 测量失去当前可用资格，但状态与放行历史保留
        mvc.perform(get("/api/measurements/{key}", "A-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.releases[0].batchId").value("JB-REV"));
    }

    @Test
    void sizeDuplicateAndHeaderValidation() throws Exception {
        createCert("INS-A", "1", "0");
        submit("A-1", "INS-A", "1", "0", "9", "alice");

        // 仅 1 条 → 400
        jointRelease("carol", """
                {"jointBatchKey":"JB-V1","requestId":"REQ-V1","keys":["A-1"]}""")
                .andExpect(status().isBadRequest());

        // 21 条 → 400
        String many = java.util.stream.IntStream.range(0, 21).mapToObj(i -> "A-1").toList().toString();
        jointRelease("carol",
                "{\"jointBatchKey\":\"JB-V2\",\"requestId\":\"REQ-V2\",\"keys\":" + many + "}")
                .andExpect(status().isBadRequest());

        // 批内重复键 → 400
        jointRelease("carol", """
                {"jointBatchKey":"JB-V3","requestId":"REQ-V3","keys":["A-1","A-1"]}""")
                .andExpect(status().isBadRequest());

        // 缺少 jointBatchKey / requestId → 400
        jointRelease("carol", """
                {"keys":["A-1","A-1"]}""")
                .andExpect(status().isBadRequest());

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/joint-releases").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jointBatchKey":"JB-V4","requestId":"REQ-V4","keys":["A-1","A-1"]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownJointBatchReturns404() throws Exception {
        mvc.perform(get("/api/joint-releases/{jointBatchKey}", "NO-SUCH-BATCH"))
                .andExpect(status().isNotFound());
    }
}
