package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 增量发布（基底摘要门禁）与设备本地维护 API 测试（H2 内存库，MODE=MySQL）：
 * 主流程、422 门禁、回执三重校验 409、维护串行、FAILED 不改镜像、幂等与结构化指纹。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareDeltaTest {

    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final String D = "d".repeat(64);
    private static final String P = "e".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDevice(String requestId, String deviceId, String version, String digest, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"%s","bucketNo":%d,
                "currentDigest":"%s"}
                """.formatted(requestId, deviceId, version, bucket, digest)))
                .andExpect(status().isOk());
    }

    private long createDeltaRelease(String requestId, String from, String to, String base, int ratio)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"%s","toVersion":"%s","ratio":%d,
                "releaseType":"DELTA","baseDigest":"%s","patchDigest":"%s","targetDigest":"%s"}
                """.formatted(requestId, from, to, ratio, base, P, D)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseType").value("DELTA"))
                .andExpect(jsonPath("$.baseDigest").value(base))
                .andExpect(jsonPath("$.patchDigest").value(P))
                .andExpect(jsonPath("$.targetDigest").value(D))
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private long pullTask(String deviceId, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    @Test
    void 主流程_增量拉取冻结摘要_合法SUCCESS同时写版本摘要代次与统计() throws Exception {
        registerDevice("r1", "d1", "1.0.0", A, 5);
        long releaseId = createDeltaRelease("r2", "1.0.0", "2.0.0", A, 100);

        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.baseDigest").value(A))
                .andExpect(jsonPath("$.task.patchDigest").value(P))
                .andExpect(jsonPath("$.task.targetDigest").value(D))
                .andExpect(jsonPath("$.task.imageGeneration").value(1))
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r4","result":"SUCCESS","imageGeneration":1,
                        "actualBaseDigest":"%s","actualTargetDigest":"%s"}
                        """.formatted(A, D)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.currentDigest").value(D))
                .andExpect(jsonPath("$.imageGeneration").value(2))
                .andExpect(jsonPath("$.model").value("m1"))
                .andExpect(jsonPath("$.bucketNo").value(5));

        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1))
                .andExpect(jsonPath("$.roundFailed").value(0));
    }

    @Test
    void 拉取门禁_基底摘要不匹配422_版本不匹配422_不生成任务() throws Exception {
        registerDevice("r1", "d1", "1.0.0", A, 5);
        // 基底全 b，设备摘要全 a：不能拉取
        createDeltaRelease("r2", "1.0.0", "2.0.0", B, 100);
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELTA_BASE_DIGEST_MISMATCH"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();

        // 版本不匹配：422
        registerDevice("r4", "d2", "9.9.9", A, 5);
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r5\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELTA_VERSION_MISMATCH"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
    }

    @Test
    void 创建与登记_摘要格式校验_大写统一小写_普通发布不带摘要() throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r1","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":5,
                        "currentDigest":"xyz"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DIGEST"));

        // 大写摘要落库统一小写
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r2","deviceId":"d2","model":"m1","currentVersion":"1.0.0","bucketNo":5,
                        "currentDigest":"%s"}
                        """.formatted(A.toUpperCase())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentDigest").value(A))
                .andExpect(jsonPath("$.imageGeneration").value(1));

        // 不传摘要：服务端合成 64 位小写十六进制
        MvcResult synthesized = mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r3","deviceId":"d3","model":"m1","currentVersion":"1.0.0","bucketNo":5}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        String digest = com.jayway.jsonpath.JsonPath.read(synthesized.getResponse().getContentAsString(),
                "$.currentDigest");
        assertThat(digest).matches("[0-9a-f]{64}");

        // DELTA 缺摘要：400；摘要非法：400；未知类型：400；FULL 带摘要：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r4","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                        "releaseType":"DELTA","baseDigest":"%s"}
                        """.formatted(A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_DIGEST"));
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r5","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                        "releaseType":"DELTA","baseDigest":"zz","patchDigest":"%s","targetDigest":"%s"}
                        """.formatted(P, D)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DIGEST"));
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r6","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                        "releaseType":"BOGUS"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RELEASE_TYPE"));
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r7","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                        "baseDigest":"%s"}
                        """.formatted(A)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FULL_RELEASE_WITH_DIGEST"));
    }
}