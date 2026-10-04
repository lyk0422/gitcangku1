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
 * 增量固件基底摘要门禁 API 测试（H2 内存库，MODE=MySQL）：
 * 主流程、拉取 422、回执三项校验 409、维护更新、FAILED 不改镜像、失败不占键。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareDeltaTest {

    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);
    private static final String DIGEST_P = "d".repeat(64);
    private static final String DIGEST_T = "e".repeat(64);

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

    private void registerDevice(String requestId, String deviceId, String model, String version,
                                String digest, int bucket) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s",
                "currentDigest":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, version, digest, bucket)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentDigest").value(digest))
                .andExpect(jsonPath("$.imageGeneration").value(1));
    }

    private long createDelta(String requestId, String model, String from, String to, int ratio,
                             String base, String patch, String target) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases/delta").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":%d,
                "baseDigest":"%s","patchDigest":"%s","targetDigest":"%s"}
                """.formatted(requestId, model, from, to, ratio, base, patch, target)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseType").value("DELTA"))
                .andExpect(jsonPath("$.baseDigest").value(base))
                .andExpect(jsonPath("$.patchDigest").value(patch))
                .andExpect(jsonPath("$.targetDigest").value(target))
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private MvcResult pull(String deviceId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/pull").contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andReturn();
    }

    private long pullDeltaTask(String deviceId, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.imageGeneration").value(1))
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    private MvcResult receipt(long taskId, String body) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content(body))
                .andReturn();
    }

    private String successReceiptBody(String requestId, int generation, String base, String target) {
        return "{\"requestId\":\"%s\",\"result\":\"SUCCESS\",\"taskGeneration\":%d,"
                .formatted(requestId, generation)
                + "\"actualBaseDigest\":\"%s\",\"actualTargetDigest\":\"%s\"}".formatted(base, target);
    }

    private void assertDevice(String deviceId, String version, String digest, int generation) throws Exception {
        mockMvc.perform(get("/api/devices/" + deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(version))
                .andExpect(jsonPath("$.currentDigest").value(digest))
                .andExpect(jsonPath("$.imageGeneration").value(generation));
    }

    private long taskCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class);
        return count == null ? 0 : count;
    }
    @Test
    void 增量主流程_拉取冻结摘要与代次_合法SUCCESS同事务写设备版本摘要任务终态与统计() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", DIGEST_A, 1);
        long releaseId = createDelta("r2", "m1", "1.0.0", "2.0.0", 100, DIGEST_A, DIGEST_P, DIGEST_T);

        long taskId = pullDeltaTask("d1", "r3");
        // 任务冻结三个摘要与设备镜像代次
        assertThat(jdbc.queryForObject(
                "SELECT base_digest FROM rollout_task WHERE id = ?", String.class, taskId))
                .isEqualTo(DIGEST_A);
        assertThat(jdbc.queryForObject(
                "SELECT patch_digest FROM rollout_task WHERE id = ?", String.class, taskId))
                .isEqualTo(DIGEST_P);
        assertThat(jdbc.queryForObject(
                "SELECT target_digest FROM rollout_task WHERE id = ?", String.class, taskId))
                .isEqualTo(DIGEST_T);
        assertThat(jdbc.queryForObject(
                "SELECT image_generation FROM rollout_task WHERE id = ?", Integer.class, taskId))
                .isEqualTo(1);

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content(successReceiptBody("r4", 1, DIGEST_A, DIGEST_T)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 合法 SUCCESS 同时写 currentVersion、目标摘要、任务终态；镜像代次加一，型号与灰度桶不变
        assertDevice("d1", "2.0.0", DIGEST_T, 2);
        assertThat(jdbc.queryForObject("SELECT model FROM device WHERE device_id = 'd1'", String.class))
                .isEqualTo("m1");
        assertThat(jdbc.queryForObject("SELECT bucket_no FROM device WHERE device_id = 'd1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?", String.class, taskId))
                .isEqualTo("SUCCESS");

        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roundSuccess").value(1))
                .andExpect(jsonPath("$.roundFailed").value(0));
    }

    @Test
    void 场景验收_基底全b不能拉取_匹配全a后维护为全c_原成功回执拒绝() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", DIGEST_A, 1);

        // 基底全 b 的补丁不能拉取：422 且不生成任务
        long releaseB = createDelta("r2", "m1", "1.0.0", "2.0.0", 100, DIGEST_B, DIGEST_P, DIGEST_T);
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DIGEST_MISMATCH"));
        assertThat(taskCount()).isZero();
        mockMvc.perform(post("/api/releases/" + releaseB + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r4\"}"))
                .andExpect(status().isOk());

        // 匹配全 a 后拉取，任务冻结代次 1
        long releaseId = createDelta("r5", "m1", "1.0.0", "2.0.0", 100, DIGEST_A, DIGEST_P, DIGEST_T);
        long taskId = pullDeltaTask("d1", "r6");

        // 本地维护为全 c，镜像代次加一
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                {"requestId":"r7","operator":"ops","newVersion":"1.0.1","newDigest":"%s",
                "expectedImageGeneration":1}
                """.formatted(DIGEST_C)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentDigest").value(DIGEST_C))
                .andExpect(jsonPath("$.imageGeneration").value(2));

        // 原成功回执应拒绝：409，不更新设备、不统计成功
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content(successReceiptBody("r8", 1, DIGEST_A, DIGEST_T)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_IMAGE_CHANGED"));
        assertDevice("d1", "1.0.1", DIGEST_C, 2);
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?", String.class, taskId))
                .isEqualTo("PENDING");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));
    }

    @Test
    void 拉取_版本不匹配422_摘要不匹配422_均不生成任务() throws Exception {
        registerDevice("r1", "d1", "m1", "9.9.9", DIGEST_A, 1);
        registerDevice("r2", "d2", "m1", "1.0.0", DIGEST_B, 1);
        createDelta("r3", "m1", "1.0.0", "2.0.0", 100, DIGEST_A, DIGEST_P, DIGEST_T);

        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r4\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VERSION_MISMATCH"));
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r5\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DIGEST_MISMATCH"));
        assertThat(taskCount()).isZero();
    }
    @Test
    void 回执_代次基底目标任一不符409_不更新设备不统计_失败不占键() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", DIGEST_A, 1);
        long releaseId = createDelta("r2", "m1", "1.0.0", "2.0.0", 100, DIGEST_A, DIGEST_P, DIGEST_T);
        long taskId = pullDeltaTask("d1", "r3");

        // 缺任务代次
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"SUCCESS\","
                                + "\"actualBaseDigest\":\"" + DIGEST_A + "\","
                                + "\"actualTargetDigest\":\"" + DIGEST_T + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_GENERATION_MISMATCH"));
        // 任务代次不符
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content(successReceiptBody("r5", 99, DIGEST_A, DIGEST_T)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_GENERATION_MISMATCH"));
        // 实际基底摘要不符
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content(successReceiptBody("r6", 1, DIGEST_B, DIGEST_T)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_BASE_MISMATCH"));
        // 实际目标摘要不符
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content(successReceiptBody("r7", 1, DIGEST_A, DIGEST_B)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_TARGET_MISMATCH"));

        // 任一不符均不更新设备、不统计成功
        assertDevice("d1", "1.0.0", DIGEST_A, 1);
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?", String.class, taskId))
                .isEqualTo("PENDING");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));

        // 失败不占键：同一 requestId 修正参数后可成功
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content(successReceiptBody("r7", 1, DIGEST_A, DIGEST_T)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertDevice("d1", "2.0.0", DIGEST_T, 2);
    }

    @Test
    void 增量FAILED_只终结任务不改镜像() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", DIGEST_A, 1);
        long releaseId = createDelta("r2", "m1", "1.0.0", "2.0.0", 100, DIGEST_A, DIGEST_P, DIGEST_T);
        long taskId = pullDeltaTask("d1", "r3");

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        assertDevice("d1", "1.0.0", DIGEST_A, 1);
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(1));
    }

    @Test
    void 维护更新_型号灰度桶不可改_期望代次校验_幂等重放与异参409() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", DIGEST_A, 7);

        MvcResult first = mockMvc.perform(post("/api/devices/d1/maintenance")
                        .contentType("application/json").content("""
                        {"requestId":"m1","operator":"ops","newVersion":"1.0.1","newDigest":"%s",
                        "expectedImageGeneration":1}
                        """.formatted(DIGEST_C)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value("1.0.1"))
                .andExpect(jsonPath("$.currentDigest").value(DIGEST_C))
                .andExpect(jsonPath("$.imageGeneration").value(2))
                .andExpect(jsonPath("$.model").value("m1"))
                .andExpect(jsonPath("$.bucketNo").value(7))
                .andReturn();

        // 同 requestId 同参重放：返回首次完整响应，代次不重复加
        MvcResult replay = mockMvc.perform(post("/api/devices/d1/maintenance")
                        .contentType("application/json").content("""
                        {"requestId":"m1","operator":"ops","newVersion":"1.0.1","newDigest":"%s",
                        "expectedImageGeneration":1}
                        """.formatted(DIGEST_C)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertDevice("d1", "1.0.1", DIGEST_C, 2);

        // 同 requestId 异参：409
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                {"requestId":"m1","operator":"ops","newVersion":"1.0.2","newDigest":"%s",
                "expectedImageGeneration":1}
                """.formatted(DIGEST_C)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 期望代次不符：409，设备不变
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                {"requestId":"m2","operator":"ops","newVersion":"1.0.2","newDigest":"%s",
                "expectedImageGeneration":1}
                """.formatted(DIGEST_B)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENERATION_CONFLICT"));
        assertDevice("d1", "1.0.1", DIGEST_C, 2);

        // 设备不存在：404
        mockMvc.perform(post("/api/devices/ghost/maintenance").contentType("application/json").content("""
                {"requestId":"m3","operator":"ops","newVersion":"1.0.2","newDigest":"%s",
                "expectedImageGeneration":1}
                """.formatted(DIGEST_B)))
                .andExpect(status().isNotFound());
    }
    @Test
    void 增量发布_摘要格式校验400_同型号未终结冲突409_相同版本400() throws Exception {
        // 大写摘要：400（统一小写）
        mockMvc.perform(post("/api/releases/delta").contentType("application/json").content("""
                {"requestId":"r1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "baseDigest":"%s","patchDigest":"%s","targetDigest":"%s"}
                """.formatted(DIGEST_A.toUpperCase(), DIGEST_P, DIGEST_T)))
                .andExpect(status().isBadRequest());
        // 长度不足：400
        mockMvc.perform(post("/api/releases/delta").contentType("application/json").content("""
                {"requestId":"r2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "baseDigest":"%s","patchDigest":"%s","targetDigest":"%s"}
                """.formatted("a".repeat(63), DIGEST_P, DIGEST_T)))
                .andExpect(status().isBadRequest());
        // 非十六进制字符：400
        mockMvc.perform(post("/api/releases/delta").contentType("application/json").content("""
                {"requestId":"r3","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "baseDigest":"%s","patchDigest":"%s","targetDigest":"%s"}
                """.formatted("g".repeat(64), DIGEST_P, DIGEST_T)))
                .andExpect(status().isBadRequest());
        // 目标版本与来源版本相同：400
        mockMvc.perform(post("/api/releases/delta").contentType("application/json").content("""
                {"requestId":"r4","model":"m1","fromVersion":"1.0.0","toVersion":"1.0.0","ratio":10,
                "baseDigest":"%s","patchDigest":"%s","targetDigest":"%s"}
                """.formatted(DIGEST_A, DIGEST_P, DIGEST_T)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_VERSION"));

        // 同型号已有未终结普通发布单：增量创建 409
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r5","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseType").value("FULL"));
        mockMvc.perform(post("/api/releases/delta").contentType("application/json").content("""
                {"requestId":"r6","model":"m1","fromVersion":"1.0.0","toVersion":"3.0.0","ratio":10,
                "baseDigest":"%s","patchDigest":"%s","targetDigest":"%s"}
                """.formatted(DIGEST_A, DIGEST_P, DIGEST_T)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_RELEASE_EXISTS"));
    }

    @Test
    void 增量任务_重复拉取返回同一冻结任务_普通发布沿用原接口() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", DIGEST_A, 1);
        registerDevice("r2", "d2", "m2", "9.9.9", DIGEST_A, 1);
        createDelta("r3", "m1", "1.0.0", "2.0.0", 100, DIGEST_A, DIGEST_P, DIGEST_T);

        long taskId = pullDeltaTask("d1", "r4");
        MvcResult again = pull("d1", "r5");
        assertThat(idOf(again, "$.task.taskId")).isEqualTo(taskId);
        assertThat(taskCount()).isEqualTo(1);

        // 普通发布沿用原接口：版本不匹配返回空任务而非 422
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r6","model":"m2","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());
    }
}