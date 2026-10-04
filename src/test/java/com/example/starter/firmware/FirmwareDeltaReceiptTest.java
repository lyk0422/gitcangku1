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
 * 增量回执三重校验、设备本地维护串行与幂等测试（H2 内存库，MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareDeltaReceiptTest {

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

    /**
     * 登记全 a 摘要设备并创建基底全 a 的增量发布，返回拉取到的任务 ID。
     */
    private long pulledDeltaTask(String suffix) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"rd%s","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":5,
                "currentDigest":"%s"}
                """.formatted(suffix, A))).andExpect(status().isOk());
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rr%s","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "releaseType":"DELTA","baseDigest":"%s","patchDigest":"%s","targetDigest":"%s"}
                """.formatted(suffix, A, P, D))).andExpect(status().isOk());
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp%s\"}".formatted(suffix)))
                .andExpect(status().isOk()).andReturn();
        return idOf(pull, "$.task.taskId");
    }


    private void assertDeviceUnchanged() throws Exception {
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"))
                .andExpect(jsonPath("$.currentDigest").value(A))
                .andExpect(jsonPath("$.imageGeneration").value(1));
    }

    @Test
    void 回执校验_代次基底目标任一不符409_不更新设备不统计成功() throws Exception {
        long taskId = pulledDeltaTask("1");

        // 代次不符
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r-g2","result":"SUCCESS","imageGeneration":9,
                        "actualBaseDigest":"%s","actualTargetDigest":"%s"}
                        """.formatted(A, D)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_GENERATION_MISMATCH"));

        // 基底摘要不符
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r-b","result":"SUCCESS","imageGeneration":1,
                        "actualBaseDigest":"%s","actualTargetDigest":"%s"}
                        """.formatted(B, D)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_BASE_DIGEST_MISMATCH"));

        // 目标摘要不符
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r-t","result":"SUCCESS","imageGeneration":1,
                        "actualBaseDigest":"%s","actualTargetDigest":"%s"}
                        """.formatted(A, C)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_TARGET_DIGEST_MISMATCH"));

        // 缺失必带字段：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-m\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_GENERATION_MISMATCH"));

        // 均未生效：任务仍 PENDING、设备未更新、统计为零
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?", String.class, taskId)).isEqualTo("PENDING");
        assertDeviceUnchanged();
        assertThat(jdbc.queryForObject(
                "SELECT round_success FROM release_order", Integer.class)).isZero();
    }

    @Test
    void 维护更新_代次加一_不能改型号灰度桶_期望代次不符409() throws Exception {
        pulledDeltaTask("2");

        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"m1","operator":"ops","newVersion":"1.1.0","newDigest":"%s",
                        "expectedImageGeneration":9}
                        """.formatted(C)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENERATION_CONFLICT"));

        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"m2","operator":"ops","newVersion":"1.1.0","newDigest":"%s",
                        "expectedImageGeneration":1}
                        """.formatted(C)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value("1.1.0"))
                .andExpect(jsonPath("$.currentDigest").value(C))
                .andExpect(jsonPath("$.imageGeneration").value(2))
                .andExpect(jsonPath("$.model").value("m1"))
                .andExpect(jsonPath("$.bucketNo").value(5));

        // 设备不存在：404；摘要非法：400
        mockMvc.perform(post("/api/devices/ghost/maintenance").contentType("application/json").content("""
                        {"requestId":"m3","operator":"ops","newVersion":"1.1.0","newDigest":"%s",
                        "expectedImageGeneration":1}
                        """.formatted(C)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"m4","operator":"ops","newVersion":"1.1.0","newDigest":"bad",
                        "expectedImageGeneration":2}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DIGEST"));
    }

    @Test
    void 维护后原成功回执拒绝_任务不终结_设备保持维护结果() throws Exception {
        long taskId = pulledDeltaTask("3");

        // 拉取后设备本地维护为全 c（代次 1 -> 2）
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"m1","operator":"ops","newVersion":"1.1.0","newDigest":"%s",
                        "expectedImageGeneration":1}
                        """.formatted(C)))
                .andExpect(status().isOk());

        // 原 SUCCESS 回执（旧代次、旧基底全 a）应拒绝
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r-s","result":"SUCCESS","imageGeneration":1,
                        "actualBaseDigest":"%s","actualTargetDigest":"%s"}
                        """.formatted(A, D)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_IMAGE_ADVANCED"));

        // 任务未终结、统计为零、设备保持维护结果
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?", String.class, taskId)).isEqualTo("PENDING");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.1.0"))
                .andExpect(jsonPath("$.currentDigest").value(C))
                .andExpect(jsonPath("$.imageGeneration").value(2));
        assertThat(jdbc.queryForObject("SELECT round_success FROM release_order", Integer.class)).isZero();
    }

    @Test
    void FAILED回执_只终结任务不改镜像() throws Exception {
        long taskId = pulledDeltaTask("4");

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-f\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        assertDeviceUnchanged();
        assertThat(jdbc.queryForObject("SELECT round_failed FROM release_order", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT round_success FROM release_order", Integer.class)).isZero();
    }

    @Test
    void 幂等_维护同键重放代次只加一_异参409_指纹结构化防分隔符碰撞() throws Exception {
        pulledDeltaTask("5");

        String body = """
                {"requestId":"m-idem","operator":"ops","newVersion":"1.1.0","newDigest":"%s",
                "expectedImageGeneration":1}
                """.formatted(C);
        MvcResult first = mockMvc.perform(post("/api/devices/d1/maintenance")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/devices/d1/maintenance")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        mockMvc.perform(get("/api/devices/d1")).andExpect(jsonPath("$.imageGeneration").value(2));

        // 同键异参：409
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"m-idem","operator":"ops","newVersion":"1.2.0","newDigest":"%s",
                        "expectedImageGeneration":1}
                        """.formatted(C)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 结构化指纹：参数含分隔符不会与拼接结果碰撞，同键异参必须 409 而非误判重放
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"rid-coll","deviceId":"a|b","model":"c","currentVersion":"1.0.0",
                        "bucketNo":5,"currentDigest":"%s"}
                        """.formatted(A)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"rid-coll","deviceId":"a","model":"b|c","currentVersion":"1.0.0",
                        "bucketNo":5,"currentDigest":"%s"}
                        """.formatted(A)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }
}