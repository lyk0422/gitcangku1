package com.example.starter.calibration;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 差分接口测试：创建主流程（合成 9.8/10.1 差 0.3 落在 [0,0.3]）、配对约束拒绝、
 * 放行/取消语义、证书撤销边界、批量放行门禁、requestId 幂等与期望版本。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DifferentialApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM differential_occupation");
        jdbc.update("DELETE FROM differential");
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
    }

    private long createCert(String instrument, String from, String to, String a, String b) throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"%s","validFrom":"%s","validTo":"%s","a":"%s","b":"%s"}
                                """.formatted(instrument, from, to, a, b)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void submit(String key, String instrument, String measuredAt, String reading,
                        String lower, String upper, String by) throws Exception {
        mvc.perform(post("/api/measurements").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"measurementKey":"%s","instrumentId":"%s","measuredAt":"%s","reading":"%s",
                                 "lowerLimit":"%s","upperLimit":"%s","submittedBy":"%s"}
                                """.formatted(key, instrument, measuredAt, reading, lower, upper, by)))
                .andExpect(status().isCreated());
    }

    /** 合成场景：参考 9.8、待测 10.1，采样间隔 5 秒，双方合格。 */
    private long setupSyntheticPair() throws Exception {
        long certId = createCert("INS-D", "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z", "1", "0");
        submit("D-REF", "INS-D", "2026-06-01T00:00:00Z", "9.8", "0", "100", "alice");
        submit("D-DUT", "INS-D", "2026-06-01T00:00:05Z", "10.1", "0", "100", "bob");
        return certId;
    }

    private ResultActions createDiff(String requestId, String key, String ref, String dut,
                                     String lower, String upper, String actor) throws Exception {
        return mvc.perform(post("/api/differentials").header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","differentialKey":"%s","referenceKey":"%s","dutKey":"%s",
                         "lowerLimit":"%s","upperLimit":"%s"}
                        """.formatted(requestId, key, ref, dut, lower, upper)));
    }

    private ResultActions releaseDiff(String key, String requestId, Long expectedVersion,
                                      String actor) throws Exception {
        String version = expectedVersion == null ? "" : ",\"expectedVersion\":" + expectedVersion;
        return mvc.perform(post("/api/differentials/{key}/release", key).header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\"" + version + "}"));
    }

    private ResultActions cancelDiff(String key, String requestId, Long expectedVersion,
                                     String actor) throws Exception {
        String version = expectedVersion == null ? "" : ",\"expectedVersion\":" + expectedVersion;
        return mvc.perform(post("/api/differentials/{key}/cancel", key).header("X-Actor-Id", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\"" + version + "}"));
    }

    @Test
    void createAndReleaseSyntheticPair() throws Exception {
        setupSyntheticPair();

        // 差值 0.3 落在闭区间 [0, 0.3] 内 → 合格待审
        createDiff("RQ-C1", "DIFF-1", "D-REF", "D-DUT", "0", "0.3", "carol")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.differentialKey").value("DIFF-1"))
                .andExpect(jsonPath("$.instrumentId").value("INS-D"))
                .andExpect(jsonPath("$.referenceMeasurementKey").value("D-REF"))
                .andExpect(jsonPath("$.dutMeasurementKey").value("D-DUT"))
                .andExpect(jsonPath("$.difference").value("0.3"))
                .andExpect(jsonPath("$.displayDifference").value("0.3000"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.createdBy").value("carol"));

        // 配对不改变原测量放行状态
        mvc.perform(get("/api/measurements/{key}", "D-REF"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 放行：同时放行两条原测量并冻结双方证书与精确值
        releaseDiff("DIFF-1", "RQ-R1", 0L, "dave")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.releasedBy").value("dave"))
                .andExpect(jsonPath("$.frozenReferenceValue").value("9.8"))
                .andExpect(jsonPath("$.frozenDutValue").value("10.1"))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/api/measurements/{key}", "D-REF"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(true));
        mvc.perform(get("/api/measurements/{key}", "D-DUT"))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releases[0].batchId").value("DIFF-DIFF-1"));

        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(2, releaseCount, "差分放行必须为两条原测量各写一条放行历史");

        mvc.perform(get("/api/differentials/usable"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].differentialKey").value("DIFF-1"));
        mvc.perform(get("/api/differentials/usable").param("instrumentId", "INS-OTHER"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void createRejectsInvalidPairs() throws Exception {
        createCert("INS-A", "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z", "1", "0");
        createCert("INS-B", "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z", "1", "0");
        submit("M-REF", "INS-A", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        submit("M-FAR", "INS-A", "2026-06-01T00:00:11Z", "2", "0", "9", "alice");
        submit("M-FAIL", "INS-A", "2026-06-01T00:00:01Z", "100", "0", "9", "alice");
        submit("M-OTHER", "INS-B", "2026-06-01T00:00:02Z", "2", "0", "9", "alice");

        // 不同仪器 → 422
        createDiff("RQ-P1", "DIFF-P1", "M-REF", "M-OTHER", "0", "9", "carol")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSTRUMENT_MISMATCH"));
        // 采样间隔 11 秒 > 10 秒 → 422
        createDiff("RQ-P2", "DIFF-P2", "M-REF", "M-FAR", "0", "9", "carol")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAMPLING_INTERVAL_EXCEEDED"));
        // 待测测量自身不合格 → 422
        createDiff("RQ-P3", "DIFF-P3", "M-REF", "M-FAIL", "0", "200", "carol")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MEASUREMENT_NOT_PASSED"));
        // 参考与待测相同 → 400
        createDiff("RQ-P4", "DIFF-P4", "M-REF", "M-REF", "0", "9", "carol")
                .andExpect(status().isBadRequest());
        // 下限大于上限 → 400
        createDiff("RQ-P5", "DIFF-P5", "M-REF", "M-FAR", "9", "0", "carol")
                .andExpect(status().isBadRequest());
        // 测量不存在 → 404
        createDiff("RQ-P6", "DIFF-P6", "M-REF", "M-MISSING", "0", "9", "carol")
                .andExpect(status().isNotFound());
        // 全部失败：不产生任何差分记录
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM differential", Integer.class);
        assertEquals(0, count);
    }

    @Test
    void occupationConflictAndCancelReleasesOccupation() throws Exception {
        setupSyntheticPair();
        submit("D-THIRD", "INS-D", "2026-06-01T00:00:03Z", "10.0", "0", "100", "alice");

        createDiff("RQ-O1", "DIFF-O1", "D-REF", "D-DUT", "0", "0.3", "carol")
                .andExpect(status().isCreated());
        // 同一测量不能被第二条未取消差分占用 → 409
        createDiff("RQ-O2", "DIFF-O2", "D-REF", "D-THIRD", "0", "0.3", "carol")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEASUREMENT_ALREADY_PAIRED"));

        // 取消：记录保留不删除，占用释放
        cancelDiff("DIFF-O1", "RQ-O3", null, "carol")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("carol"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/differentials/{key}", "DIFF-O1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 占用已释放：同一测量可重新配对
        createDiff("RQ-O4", "DIFF-O2", "D-REF", "D-THIRD", "0", "0.3", "carol")
                .andExpect(status().isCreated());

        // 重复取消 → 409
        cancelDiff("DIFF-O1", "RQ-O5", null, "carol")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"));
    }

    @Test
    void releasedDifferentialCannotBeCancelled() throws Exception {
        setupSyntheticPair();
        createDiff("RQ-X1", "DIFF-X1", "D-REF", "D-DUT", "0", "0.3", "carol")
                .andExpect(status().isCreated());
        releaseDiff("DIFF-X1", "RQ-X2", null, "dave").andExpect(status().isOk());

        cancelDiff("DIFF-X1", "RQ-X3", null, "carol")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RELEASED"));
        mvc.perform(get("/api/differentials/{key}", "DIFF-X1"))
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    void outOfLimitDifferentialSavedButNotReleasable() throws Exception {
        setupSyntheticPair();

        // 差值 0.3 超出上限 0.2：不合格差分仍保存为待审
        createDiff("RQ-L1", "DIFF-L1", "D-REF", "D-DUT", "0", "0.2", "carol")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 不合格不能放行 → 409，原测量保持待放行
        releaseDiff("DIFF-L1", "RQ-L2", null, "dave")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_PASSED"));
        mvc.perform(get("/api/differentials/{key}", "DIFF-L1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/measurements/{key}", "D-REF"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(0, releaseCount);
    }

    @Test
    void releaserMustNotBeAnySubmitter() throws Exception {
        setupSyntheticPair();
        createDiff("RQ-S1", "DIFF-S1", "D-REF", "D-DUT", "0", "0.3", "carol")
                .andExpect(status().isCreated());

        releaseDiff("DIFF-S1", "RQ-S2", null, "alice")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAME_ACTOR"));
        releaseDiff("DIFF-S1", "RQ-S3", null, "bob")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAME_ACTOR"));
        releaseDiff("DIFF-S1", "RQ-S4", null, "dave")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    void revokedReferenceCertificateBlocksWholeRelease() throws Exception {
        // 两条测量各用不同有效证书（相邻区间，采样跨越边界 4 秒）
        long refCert = createCert("INS-V", "2026-01-01T00:00:00Z", "2026-07-01T00:00:00Z", "1", "0");
        createCert("INS-V", "2026-07-01T00:00:00Z", "2027-01-01T00:00:00Z", "1", "0");
        submit("V-REF", "INS-V", "2026-06-30T23:59:58Z", "9.8", "0", "100", "alice");
        submit("V-DUT", "INS-V", "2026-07-01T00:00:02Z", "10.1", "0", "100", "bob");

        createDiff("RQ-V1", "DIFF-V1", "V-REF", "V-DUT", "0", "0.3", "carol")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passed").value(true));

        // 参考证书先撤销 → 整次 409，两条原测量均不得被放行
        mvc.perform(post("/api/certificates/{id}/revoke", refCert)).andExpect(status().isOk());
        releaseDiff("DIFF-V1", "RQ-V2", null, "dave")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_REVOKED"));

        mvc.perform(get("/api/measurements/{key}", "V-REF"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/measurements/{key}", "V-DUT"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/differentials/{key}", "DIFF-V1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.usable").value(false));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(0, releaseCount);
    }

    @Test
    void revokeAfterReleaseDropsUsabilityButKeepsEvidence() throws Exception {
        long certId = setupSyntheticPair();
        createDiff("RQ-E1", "DIFF-E1", "D-REF", "D-DUT", "0", "0.3", "carol")
                .andExpect(status().isCreated());
        releaseDiff("DIFF-E1", "RQ-E2", null, "dave").andExpect(status().isOk());
        mvc.perform(get("/api/differentials/usable")).andExpect(jsonPath("$.length()").value(1));

        mvc.perform(post("/api/certificates/{id}/revoke", certId)).andExpect(status().isOk());

        // 当前不可用，但旧配对及放行证据仍可查
        mvc.perform(get("/api/differentials/usable")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/differentials/{key}", "DIFF-E1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.releasedBy").value("dave"))
                .andExpect(jsonPath("$.frozenReferenceValue").value("9.8"))
                .andExpect(jsonPath("$.frozenDutValue").value("10.1"));
    }

    @Test
    void batchReleaseRejectsPairedMeasurements() throws Exception {
        setupSyntheticPair();
        createDiff("RQ-B1", "DIFF-B1", "D-REF", "D-DUT", "0", "0.3", "carol")
                .andExpect(status().isCreated());

        // 原批量放行遇到已配对测量必须整批拒绝，不能绕过差分门禁
        mvc.perform(post("/api/measurements/release")
                        .header("X-Actor-Id", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"D-REF\",\"D-DUT\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.failures[?(@.key=='D-REF')].reasons[0]").value("PAIRED_IN_DIFFERENTIAL"))
                .andExpect(jsonPath("$.failures[?(@.key=='D-DUT')].reasons[0]").value("PAIRED_IN_DIFFERENTIAL"));

        mvc.perform(get("/api/measurements/{key}", "D-REF"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(0, releaseCount);
    }

    @Test
    void idempotencyReplayConflictAndFailureNotOccupying() throws Exception {
        setupSyntheticPair();

        // 同键同参重放首次完整响应
        String first = createDiff("RQ-I1", "DIFF-I1", "D-REF", "D-DUT", "0", "0.3", "carol")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = createDiff("RQ-I1", "DIFF-I1", "D-REF", "D-DUT", "0", "0.3", "carol")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, replay, "同键同参必须重放首次完整响应");
        Integer diffCount = jdbc.queryForObject("SELECT COUNT(*) FROM differential", Integer.class);
        assertEquals(1, diffCount);

        // 同键异参 → 409
        createDiff("RQ-I1", "DIFF-I1", "D-REF", "D-DUT", "0", "0.4", "carol")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 失败不占键：异仪器创建 422 后，同 requestId 换合法参数可成功
        createCert("INS-D2", "2026-01-01T00:00:00Z", "2028-01-01T00:00:00Z", "1", "0");
        submit("D-OTHER-INS", "INS-D2", "2026-06-01T00:00:00Z", "1", "0", "9", "alice");
        createDiff("RQ-I2", "DIFF-I2", "D-REF", "D-OTHER-INS", "0", "9", "carol")
                .andExpect(status().isUnprocessableEntity());
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'RQ-I2'", Integer.class);
        assertEquals(0, idemCount, "业务失败不得占用幂等键");

        // 放行幂等：同键同参重放；新键重复放行 → 409
        releaseDiff("DIFF-I1", "RQ-I3", null, "dave")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        releaseDiff("DIFF-I1", "RQ-I3", null, "dave")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releasedBy").value("dave"));
        releaseDiff("DIFF-I1", "RQ-I4", null, "dave")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RELEASED"));
        Integer releaseCount = jdbc.queryForObject("SELECT COUNT(*) FROM release_record", Integer.class);
        assertEquals(2, releaseCount, "重放不得重复写放行历史");
    }

    @Test
    void expectedVersionCheckedOnAction() throws Exception {
        setupSyntheticPair();
        createDiff("RQ-W1", "DIFF-W1", "D-REF", "D-DUT", "0", "0.3", "carol")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0));

        cancelDiff("DIFF-W1", "RQ-W2", 5L, "carol")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_MISMATCH"));
        cancelDiff("DIFF-W1", "RQ-W3", 0L, "carol")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void unknownDifferentialReturns404() throws Exception {
        mvc.perform(get("/api/differentials/{key}", "NO-SUCH"))
                .andExpect(status().isNotFound());
        releaseDiff("NO-SUCH", "RQ-U1", null, "dave")
                .andExpect(status().isNotFound());
        cancelDiff("NO-SUCH", "RQ-U2", null, "dave")
                .andExpect(status().isNotFound());
    }
}
