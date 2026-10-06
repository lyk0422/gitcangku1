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
 * 启动挑战（BOOT_CHALLENGE）API 测试（H2 内存库，MODE=MySQL）：
 * 交付场景（b=4 领取后维护到 6，c=6 拒绝、c=7 成功、新键错挑战拒绝）、
 * 证明校验失败分支、维护版本校验、挑战历史保留。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareBootChallengeTest {

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

    private void registerDevice(String requestId, String deviceId) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                """.formatted(requestId, deviceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bootCount").value(0))
                .andExpect(jsonPath("$.deviceVersion").value(1));
    }

    private String maintain(String deviceId, String requestId, String operator,
                            int expectedVersion, long bootCount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/maintenance")
                        .contentType("application/json").content("""
                                {"requestId":"%s","operator":"%s","expectedDeviceVersion":%d,"bootCount":%d}
                                """.formatted(requestId, operator, expectedVersion, bootCount)))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private long createChallengeRelease(String requestId, String from, String to) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"%s","toVersion":"%s","ratio":100,
                "taskType":"BOOT_CHALLENGE"}
                """.formatted(requestId, from, to)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType").value("BOOT_CHALLENGE"))
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private MvcResult pull(String deviceId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/pull").contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private MvcResult receipt(long taskId, String body) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content(body))
                .andReturn();
    }

    private long longQuery(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? -1 : value;
    }

    @Test
    void 交付场景_领取后维护到6_c6拒绝_c7成功且版本更新一次_新键改挑战拒绝() throws Exception {
        registerDevice("r1", "d1");
        // 合成 b=4：维护计数到 4，档案版本推进到 2
        maintain("d1", "r2", "op-a", 1, 4);
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.bootCount").value(4))
                .andExpect(jsonPath("$.deviceVersion").value(2));

        long releaseId = createChallengeRelease("r3", "1.0.0", "2.0.0");
        MvcResult pull = pull("d1", "r4");
        long taskId = idOf(pull, "$.task.taskId");
        String challenge = com.jayway.jsonpath.JsonPath.read(pull.getResponse().getContentAsString(),
                "$.task.challenge");
        assertThat(challenge).isNotBlank();
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r4x\"}"))
                .andExpect(jsonPath("$.task.frozenBootCount").value(4))
                .andExpect(jsonPath("$.task.taskType").value("BOOT_CHALLENGE"));

        // 领取后维护计数到 6，档案版本推进到 3
        maintain("d1", "r5", "op-a", 2, 6);

        // 提交 c=6：不大于提交时设备实际计数，409；设备、任务、失败率均不更新
        MvcResult rejected = receipt(taskId, """
                {"requestId":"r6","result":"SUCCESS","operator":"op-b","challenge":"%s","bootCount":6}
                """.formatted(challenge));
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
        assertThat(rejected.getResponse().getContentAsString()).contains("STALE_BOOT_COUNT");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.bootCount").value(6))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"))
                .andExpect(jsonPath("$.deviceVersion").value(3));
        assertThat(longQuery("SELECT COUNT(*) FROM rollout_task WHERE id = ? AND status = 'PENDING'", taskId))
                .isEqualTo(1);
        assertThat(longQuery("SELECT round_success FROM release_order WHERE id = ?", releaseId)).isZero();

        // 提交 c=7：成功，设备计数与固件版本原子更新，任务与监控样本各记一次
        MvcResult success = receipt(taskId, """
                {"requestId":"r7","result":"SUCCESS","operator":"op-b","challenge":"%s","bootCount":7}
                """.formatted(challenge));
        assertThat(success.getResponse().getStatus()).isEqualTo(200);
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.bootCount").value(7))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.deviceVersion").value(3));
        assertThat(longQuery("SELECT round_success FROM release_order WHERE id = ?", releaseId)).isEqualTo(1);

        // 旧成功键重放首次响应：设备计数不回写、档案版本不再推进
        MvcResult replay = receipt(taskId, """
                {"requestId":"r7","result":"SUCCESS","operator":"op-b","challenge":"%s","bootCount":7}
                """.formatted(challenge));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(success.getResponse().getContentAsString());
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.bootCount").value(7))
                .andExpect(jsonPath("$.deviceVersion").value(3));
        assertThat(longQuery("SELECT round_success FROM release_order WHERE id = ?", releaseId)).isEqualTo(1);

        // 终态新键改 challenge：409；新键正确挑战同结果：200 且不重复计数
        MvcResult wrongProof = receipt(taskId, """
                {"requestId":"r8","result":"SUCCESS","operator":"op-b","challenge":"chg-forged","bootCount":9}
                """);
        assertThat(wrongProof.getResponse().getStatus()).isEqualTo(409);
        assertThat(wrongProof.getResponse().getContentAsString()).contains("CHALLENGE_MISMATCH");
        MvcResult terminalRepeat = receipt(taskId, """
                {"requestId":"r9","result":"SUCCESS","operator":"op-b","challenge":"%s","bootCount":9}
                """.formatted(challenge));
        assertThat(terminalRepeat.getResponse().getStatus()).isEqualTo(200);
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.bootCount").value(7));
        assertThat(longQuery("SELECT round_success FROM release_order WHERE id = ?", releaseId)).isEqualTo(1);
    }

    @Test
    void 挑战回执_错误或缺失挑战409且无副作用_缺操作者400_缺计数证明400() throws Exception {
        registerDevice("r1", "d1");
        long releaseId = createChallengeRelease("r2", "1.0.0", "2.0.0");
        long taskId = idOf(pull("d1", "r3"), "$.task.taskId");
        String challenge = com.jayway.jsonpath.JsonPath.read(
                pull("d1", "r3x").getResponse().getContentAsString(), "$.task.challenge");

        // 错误挑战：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r4","result":"SUCCESS","operator":"op","challenge":"bad","bootCount":1}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_MISMATCH"));

        // 缺失挑战：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"result\":\"SUCCESS\",\"operator\":\"op\",\"bootCount\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_MISMATCH"));

        // 缺操作者：400
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r6","result":"SUCCESS","challenge":"%s","bootCount":1}
                        """.formatted(challenge)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPERATOR_REQUIRED"));

        // SUCCESS 缺计数证明：400
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r7","result":"SUCCESS","operator":"op","challenge":"%s"}
                        """.formatted(challenge)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOOT_COUNT_REQUIRED"));

        // 全部失败分支无副作用：任务仍 PENDING、设备与统计未变
        assertThat(longQuery("SELECT COUNT(*) FROM rollout_task WHERE id = ? AND status = 'PENDING'", taskId))
                .isEqualTo(1);
        assertThat(longQuery("SELECT boot_count FROM device WHERE device_id = 'd1'")).isZero();
        assertThat(longQuery("SELECT round_success FROM release_order WHERE id = ?", releaseId)).isZero();
        assertThat(longQuery("SELECT round_failed FROM release_order WHERE id = ?", releaseId)).isZero();
    }

    @Test
    void 回执_计数不超过冻结值409_超过long范围400() throws Exception {
        registerDevice("r1", "d1");
        maintain("d1", "r2", "op", 1, 4);
        createChallengeRelease("r3", "1.0.0", "2.0.0");
        long taskId = idOf(pull("d1", "r4"), "$.task.taskId");
        String challenge = com.jayway.jsonpath.JsonPath.read(
                pull("d1", "r4x").getResponse().getContentAsString(), "$.task.challenge");

        // c 等于冻结值 b=4：不严格大于，409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r5","result":"SUCCESS","operator":"op","challenge":"%s","bootCount":4}
                        """.formatted(challenge)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_BOOT_COUNT"));

        // c 小于 b：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r6","result":"SUCCESS","operator":"op","challenge":"%s","bootCount":2}
                        """.formatted(challenge)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_BOOT_COUNT"));

        // c 超过 long 范围：400
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r7","result":"SUCCESS","operator":"op","challenge":"%s","bootCount":9223372036854775808}
                        """.formatted(challenge)))
                .andExpect(status().isBadRequest());

        // 维护计数超过 long 范围：400
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"r8","operator":"op","expectedDeviceVersion":2,"bootCount":9223372036854775808}
                        """))
                .andExpect(status().isBadRequest());

        // 合法 c=5 成功
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r9","result":"SUCCESS","operator":"op","challenge":"%s","bootCount":5}
                        """.formatted(challenge)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.bootCount").value(5))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
    }

    @Test
    void FAILED_携带正确挑战但不提升计数_错误挑战409() throws Exception {
        registerDevice("r1", "d1");
        maintain("d1", "r2", "op", 1, 4);
        long releaseId = createChallengeRelease("r3", "1.0.0", "2.0.0");
        long taskId = idOf(pull("d1", "r4"), "$.task.taskId");
        String challenge = com.jayway.jsonpath.JsonPath.read(
                pull("d1", "r4x").getResponse().getContentAsString(), "$.task.challenge");

        // FAILED 错误挑战：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r5","result":"FAILED","operator":"op","challenge":"bad"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_MISMATCH"));

        // FAILED 正确挑战、不携带计数：成功，设备计数与版本不提升，失败样本记一次
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r6","result":"FAILED","operator":"op","challenge":"%s"}
                        """.formatted(challenge)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.bootCount").value(4))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        assertThat(longQuery("SELECT round_failed FROM release_order WHERE id = ?", releaseId)).isEqualTo(1);

        // 终态新键正确挑战同结果：200 不重复计数；改结果：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r7","result":"FAILED","operator":"op","challenge":"%s"}
                        """.formatted(challenge)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(longQuery("SELECT round_failed FROM release_order WHERE id = ?", releaseId)).isEqualTo(1);
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json").content("""
                        {"requestId":"r8","result":"SUCCESS","operator":"op","challenge":"%s","bootCount":9}
                        """.formatted(challenge)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_RESULT_CONFLICT"));
    }

    @Test
    void 维护_版本冲突409_计数倒退409_负值400_失败不占键_重放不重复推进() throws Exception {
        registerDevice("r1", "d1");
        maintain("d1", "r2", "op", 1, 4);

        // expectedDeviceVersion 不一致：409
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"r3","operator":"op","expectedDeviceVersion":1,"bootCount":6}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_VERSION_CONFLICT"));

        // 计数倒退：409
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"r4","operator":"op","expectedDeviceVersion":2,"bootCount":3}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOT_COUNT_REGRESS"));

        // 负计数：400；缺操作者：400；设备不存在：404
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"r5","operator":"op","expectedDeviceVersion":2,"bootCount":-1}
                        """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"r6","expectedDeviceVersion":2,"bootCount":6}
                        """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/devices/ghost/maintenance").contentType("application/json").content("""
                        {"requestId":"r7","operator":"op","expectedDeviceVersion":1,"bootCount":1}
                        """))
                .andExpect(status().isNotFound());

        // 失败不占键：r3 以正确版本重试成功，档案版本推进到 3
        String body = maintain("d1", "r3", "op", 2, 6);
        assertThat(body).contains("\"deviceVersion\":3").contains("\"bootCount\":6");

        // 同键同参重放：响应一致，版本不重复推进
        String replay = maintain("d1", "r3", "op", 2, 6);
        assertThat(replay).isEqualTo(body);
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.deviceVersion").value(3))
                .andExpect(jsonPath("$.bootCount").value(6));

        // 同键异参：409
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"r3","operator":"op","expectedDeviceVersion":2,"bootCount":7}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 挑战历史_不被下一任务覆盖_终态新键仍校验首次挑战() throws Exception {
        registerDevice("r1", "d1");
        long release1 = createChallengeRelease("r2", "1.0.0", "2.0.0");
        long task1 = idOf(pull("d1", "r3"), "$.task.taskId");
        String challenge1 = com.jayway.jsonpath.JsonPath.read(
                pull("d1", "r3x").getResponse().getContentAsString(), "$.task.challenge");
        mockMvc.perform(post("/api/tasks/" + task1 + "/receipt").contentType("application/json").content("""
                        {"requestId":"r4","result":"SUCCESS","operator":"op","challenge":"%s","bootCount":1}
                        """.formatted(challenge1)))
                .andExpect(status().isOk());

        // 第二张发布单（设备已到 2.0.0）：新任务生成新挑战，旧任务挑战不被覆盖
        mockMvc.perform(post("/api/releases/" + release1 + "/cancel").contentType("application/json")
                .content("{\"requestId\":\"r5\"}"));
        createChallengeRelease("r6", "2.0.0", "3.0.0");
        long task2 = idOf(pull("d1", "r7"), "$.task.taskId");
        String challenge2 = com.jayway.jsonpath.JsonPath.read(
                pull("d1", "r7x").getResponse().getContentAsString(), "$.task.challenge");
        assertThat(task2).isNotEqualTo(task1);
        assertThat(challenge2).isNotEqualTo(challenge1);
        String stored1 = jdbc.queryForObject("SELECT challenge FROM rollout_task WHERE id = ?",
                String.class, task1);
        assertThat(stored1).isEqualTo(challenge1);

        // 旧任务终态新键：首次挑战一致放行同结果，新任务挑战不能用于旧任务
        mockMvc.perform(post("/api/tasks/" + task1 + "/receipt").contentType("application/json").content("""
                        {"requestId":"r8","result":"SUCCESS","operator":"op","challenge":"%s","bootCount":2}
                        """.formatted(challenge2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHALLENGE_MISMATCH"));
        mockMvc.perform(post("/api/tasks/" + task1 + "/receipt").contentType("application/json").content("""
                        {"requestId":"r9","result":"SUCCESS","operator":"op","challenge":"%s","bootCount":2}
                        """.formatted(challenge1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        // 旧任务重放不回写设备计数（当前为 1，不被改为 2）
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.bootCount").value(1));
    }

    @Test
    void 普通任务_无证明字段_原流程不变() throws Exception {
        registerDevice("r1", "d1");
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskType").value("NORMAL"));
        MvcResult pull = pull("d1", "r3");
        long taskId = idOf(pull, "$.task.taskId");
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3x\"}"))
                .andExpect(jsonPath("$.task.taskType").value("NORMAL"))
                .andExpect(jsonPath("$.task.challenge").doesNotExist())
                .andExpect(jsonPath("$.task.frozenBootCount").doesNotExist());

        // 原回执入口无需证明字段
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.bootCount").value(0));
    }
}
