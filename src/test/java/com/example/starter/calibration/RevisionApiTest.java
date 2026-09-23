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
 * 测量修订测试：修订主流程（新版 PENDING、最新指针切换、旧版保留）、失败分支
 * （400/404/409/422）、requestId 幂等重放、版本历史、旧放行入口兼容与版本化放行。
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

    private org.springframework.test.web.servlet.ResultActions revise(String key, String actor,
                        int expectedRevision, String requestId, String reason,
                        String reading, String lower, String upper) throws Exception {
        return mvc.perform(post("/api/measurements/{key}/revisions", key)
                .header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"expectedRevision":%d,"requestId":"%s","reason":"%s",
                         "reading":"%s","lowerLimit":"%s","upperLimit":"%s"}
                        """.formatted(expectedRevision, requestId, reason, reading, lower, upper)));
    }

    @Test
    void reviseCreatesNewPendingVersionAndKeepsOldVersion() throws Exception {
        createCert("INS-1", "2", "0.5");
        submit("M-1", "INS-1", "1", "0", "9", "alice");

        // 旧放行入口先放行首版，验证放行历史保留
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-1\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(1));

        // 原提交人修订：新版 PENDING，revision=2，按原测量时刻重匹配证书计算
        revise("M-1", "alice", 1, "REQ-1", "读数复测修正", "1.5", "0", "9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.revisionReason").value("读数复测修正"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.instrumentId").value("INS-1"))
                .andExpect(jsonPath("$.measuredAt").value("2026-06-01T00:00:00Z"))
                .andExpect(jsonPath("$.computedValue").value("3.5"))
                .andExpect(jsonPath("$.displayValue").value("3.5000"))
                .andExpect(jsonPath("$.passed").value(true));

        // 新版提交即排除旧版当前可用资格，且不因新版未放行而回退
        mvc.perform(get("/api/measurements/usable")).andExpect(jsonPath("$.length()").value(0));

        // 明细默认返回最新版并带 revision
        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 版本历史：两版齐全，旧版原始值、计算结果与放行历史保留
        mvc.perform(get("/api/measurements/{key}/revisions", "M-1"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].revision").value(1))
                .andExpect(jsonPath("$[0].reading").value("1"))
                .andExpect(jsonPath("$[0].computedValue").value("2.5"))
                .andExpect(jsonPath("$[0].status").value("RELEASED"))
                .andExpect(jsonPath("$[0].usable").value(false))
                .andExpect(jsonPath("$[0].releases[0].releasedBy").value("carol"))
                .andExpect(jsonPath("$[1].revision").value(2))
                .andExpect(jsonPath("$[1].status").value("PENDING"));

        // 数据库层：仅一版为最新
        Integer latestCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key = 'M-1' AND is_latest = TRUE",
                Integer.class);
        assertEquals(1, latestCount);
    }

    @Test
    void reviseValidationAndConflictBranches() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");

        // 非原提交人 → 409 ACTOR_MISMATCH
        revise("M-1", "bob", 1, "REQ-A", "原因", "2", "0", "9")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTOR_MISMATCH"));

        // expectedRevision 与当前不一致 → 409 REVISION_MISMATCH
        revise("M-1", "alice", 2, "REQ-B", "原因", "2", "0", "9")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_MISMATCH"));

        // 不存在的键 → 404
        revise("M-NONE", "alice", 1, "REQ-C", "原因", "2", "0", "9")
                .andExpect(status().isNotFound());

        // 非法参数 → 400：空原因、空 requestId、缺 expectedRevision、非法读数、下限>上限
        mvc.perform(post("/api/measurements/{key}/revisions", "M-1")
                        .header("X-Actor-Id", "alice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"requestId\":\"R1\",\"reason\":\"  \","
                                + "\"reading\":\"2\",\"lowerLimit\":\"0\",\"upperLimit\":\"9\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/measurements/{key}/revisions", "M-1")
                        .header("X-Actor-Id", "alice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"requestId\":\"\",\"reason\":\"r\","
                                + "\"reading\":\"2\",\"lowerLimit\":\"0\",\"upperLimit\":\"9\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/measurements/{key}/revisions", "M-1")
                        .header("X-Actor-Id", "alice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"R1\",\"reason\":\"r\","
                                + "\"reading\":\"2\",\"lowerLimit\":\"0\",\"upperLimit\":\"9\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/measurements/{key}/revisions", "M-1")
                        .header("X-Actor-Id", "alice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"requestId\":\"R1\",\"reason\":\"r\","
                                + "\"reading\":\"1.1234567\",\"lowerLimit\":\"0\",\"upperLimit\":\"9\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/measurements/{key}/revisions", "M-1")
                        .header("X-Actor-Id", "alice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"requestId\":\"R1\",\"reason\":\"r\","
                                + "\"reading\":\"2\",\"lowerLimit\":\"9\",\"upperLimit\":\"0\"}"))
                .andExpect(status().isBadRequest());

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/measurements/{key}/revisions", "M-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"requestId\":\"R1\",\"reason\":\"r\","
                                + "\"reading\":\"2\",\"lowerLimit\":\"0\",\"upperLimit\":\"9\"}"))
                .andExpect(status().isBadRequest());

        // 全部失败：不增号、不切换指针
        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key = 'M-1'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void reviseWithoutMatchingCertificateReturns422AndDoesNotConsumeRequestId() throws Exception {
        long certId = createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());

        // 原测量时刻已无未撤销证书 → 422，不增号
        revise("M-1", "alice", 1, "REQ-9", "原因", "2", "0", "9")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_MATCHING_CERTIFICATE"));
        mvc.perform(get("/api/measurements/{key}", "M-1")).andExpect(jsonPath("$.revision").value(1));

        // 失败不占键：补一张新证书后，同一 requestId 可成功
        createCert("INS-1", "2", "0");
        revise("M-1", "alice", 1, "REQ-9", "原因", "2", "0", "9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.computedValue").value("4"));
    }

    @Test
    void requestIdReplayReturnsFirstResultAndParamChangeConflicts() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");

        String first = revise("M-1", "alice", 1, "REQ-1", "原因", "2", "0", "9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andReturn().getResponse().getContentAsString();
        long firstId = ((Number) com.jayway.jsonpath.JsonPath.read(first, "$.id")).longValue();

        // 同键同参重放：返回首次结果，不产生新版本
        revise("M-1", "alice", 1, "REQ-1", "原因", "2", "0", "9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.revision").value(2));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM measurement WHERE measurement_key = 'M-1'", Integer.class);
        assertEquals(2, count);

        // 同 requestId 改参 → 409
        revise("M-1", "alice", 1, "REQ-1", "原因", "3", "0", "9")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 继续修订到第 3 版后，重放旧 requestId 仍返回首次结果且不切回旧版
        revise("M-1", "alice", 2, "REQ-2", "再次修订", "4", "0", "9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(3));
        revise("M-1", "alice", 1, "REQ-1", "原因", "2", "0", "9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.revision").value(2));
        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.revision").value(3));
        mvc.perform(get("/api/measurements/{key}/revisions", "M-1"))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void oldReleaseEndpointRequiresExplicitVersionForRevisedKeys() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-NEW", "INS-1", "1", "0", "9", "alice");
        submit("M-OLD", "INS-1", "1", "0", "9", "alice");
        revise("M-NEW", "alice", 1, "REQ-1", "原因", "2", "0", "9").andExpect(status().isOk());

        // 已修订的键走旧入口 → 整批 409 REVISION_REQUIRED；未修订的键同批被牵连不放行
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"M-NEW\",\"M-OLD\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='M-NEW')].reasons[0]").value("REVISION_REQUIRED"));
        mvc.perform(get("/api/measurements/{key}", "M-OLD"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 从未修订的键旧入口保持可用
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"M-OLD\"]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/measurements/{key}", "M-OLD"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.revision").value(1));
    }

    @Test
    void versionedReleaseHappyPathAndItemFailures() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");
        submit("M-2", "INS-1", "100", "0", "9", "bob");   // 不合格
        submit("M-3", "INS-1", "1", "0", "9", "carol");
        revise("M-1", "alice", 1, "REQ-1", "原因", "2", "0", "9").andExpect(status().isOk());
        revise("M-3", "carol", 1, "REQ-2", "原因", "2", "0", "9").andExpect(status().isOk());

        // 混合失败：旧版 NOT_LATEST、不合格 NOT_PASSED、放行人=提交人 SAME_ACTOR、不存在 → 整批 409
        mvc.perform(post("/api/measurements/release-versions")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":["
                                + "{\"measurementKey\":\"M-1\",\"revision\":1},"
                                + "{\"measurementKey\":\"M-2\",\"revision\":1},"
                                + "{\"measurementKey\":\"M-3\",\"revision\":2},"
                                + "{\"measurementKey\":\"M-NONE\",\"revision\":1}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='M-1')].reasons[0]").value("NOT_LATEST"))
                .andExpect(jsonPath("$.failures[?(@.key=='M-2')].reasons[0]").value("NOT_PASSED"))
                .andExpect(jsonPath("$.failures[?(@.key=='M-3')].reasons[0]").value("SAME_ACTOR"))
                .andExpect(jsonPath("$.failures[?(@.key=='M-NONE')].reasons[0]")
                        .value("MEASUREMENT_NOT_FOUND"));

        // 整批回滚：没有任何一项被放行
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(0, releaseCount);

        // 正确版本整批放行
        mvc.perform(post("/api/measurements/release-versions")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"measurementKey\":\"M-1\",\"revision\":2},"
                                + "{\"measurementKey\":\"M-3\",\"revision\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").isString())
                .andExpect(jsonPath("$.releasedBy").value("dave"))
                .andExpect(jsonPath("$.released.length()").value(2))
                .andExpect(jsonPath("$.released[?(@.measurementKey=='M-1')].revision").value(2));

        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.releases[0].releasedBy").value("dave"));
        mvc.perform(get("/api/measurements/usable"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.measurementKey=='M-1')].revision").value(2));

        // 重复放行同一版本 → ALREADY_RELEASED
        mvc.perform(post("/api/measurements/release-versions")
                        .header("X-Actor-Id", "dave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"measurementKey\":\"M-1\",\"revision\":2}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("ALREADY_RELEASED"));
    }

    @Test
    void versionedReleaseBatchValidation() throws Exception {
        createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");

        // 空批次 → 400
        mvc.perform(post("/api/measurements/release-versions")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());

        // 超过 50 项 → 400
        String big = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> "{\"measurementKey\":\"K" + i + "\",\"revision\":1}")
                .toList().toString();
        mvc.perform(post("/api/measurements/release-versions")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":" + big + "}"))
                .andExpect(status().isBadRequest());

        // 批次内重复键 → 400
        mvc.perform(post("/api/measurements/release-versions")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"measurementKey\":\"M-1\",\"revision\":1},"
                                + "{\"measurementKey\":\"M-1\",\"revision\":1}]}"))
                .andExpect(status().isBadRequest());

        // 缺 revision → 400
        mvc.perform(post("/api/measurements/release-versions")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"measurementKey\":\"M-1\"}]}"))
                .andExpect(status().isBadRequest());

        // 缺少 X-Actor-Id → 400
        mvc.perform(post("/api/measurements/release-versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"measurementKey\":\"M-1\",\"revision\":1}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void versionedReleaseRejectsRevokedCertificate() throws Exception {
        long certId = createCert("INS-1", "1", "0");
        submit("M-1", "INS-1", "1", "0", "9", "alice");
        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());

        mvc.perform(post("/api/measurements/release-versions")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"measurementKey\":\"M-1\",\"revision\":1}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[0].reasons[0]").value("CERTIFICATE_REVOKED"));
        mvc.perform(get("/api/measurements/{key}", "M-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void historyOfUnknownKeyReturns404() throws Exception {
        mvc.perform(get("/api/measurements/{key}/revisions", "NO-SUCH-KEY"))
                .andExpect(status().isNotFound());
    }
}
