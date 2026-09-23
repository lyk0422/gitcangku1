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
 * 失败任务显式重试 API 测试（H2 内存库，MODE=MySQL）：
 * 主流程、尝试次数上限、状态分支、监控轮次样本、幂等与任务历史查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareRetryTest {

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

    private void registerDevice(String requestId, String deviceId, String model, int bucket) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":%d}
                """.formatted(requestId, deviceId, model, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, int ratio, int sampleFloor, int threshold)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d,
                "sampleFloor":%d,"failureThresholdPercent":%d}
                """.formatted(requestId, model, ratio, sampleFloor, threshold)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private long pullTask(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    private void receipt(long taskId, String requestId, String result) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"result\":\"%s\"}".formatted(requestId, result)))
                .andExpect(status().isOk());
    }

    private MvcResult retry(long taskId, String requestId, int expectedVersion) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + taskId + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"expectedVersion\":%d}"
                                .formatted(requestId, expectedVersion)))
                .andReturn();
    }

    @Test
    void 重试主流程_失败后显式重试_成功更新设备版本_样本分别计数() throws Exception {
        registerDevice("r1", "d1", "m1", 1);
        long releaseId = createRelease("r2", "m1", 100, 100, 50);
        long t1 = pullTask("r3", "d1");
        receipt(t1, "r4", "FAILED");

        // 未显式重试：拉取仍返回原失败任务（序号1），不自动重开
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(t1))
                .andExpect(jsonPath("$.task.status").value("FAILED"))
                .andExpect(jsonPath("$.task.attemptNo").value(1))
                .andExpect(jsonPath("$.task.predecessorId").doesNotExist());

        // 显式重试：新任务独立ID、序号2、前驱为原任务
        MvcResult retry = mockMvc.perform(post("/api/tasks/" + t1 + "/retry")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andExpect(jsonPath("$.predecessorId").value(t1))
                .andExpect(jsonPath("$.fromVersion").value("1.0.0"))
                .andExpect(jsonPath("$.toVersion").value("2.0.0"))
                .andReturn();
        long t2 = idOf(retry, "$.taskId");
        assertThat(t2).isNotEqualTo(t1);

        // 重试不增加发布版本、不改变投放比例、不立即计入监控样本
        assertThat(jdbc.queryForObject("SELECT version FROM release_order WHERE id = ?",
                Integer.class, releaseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ratio FROM release_order WHERE id = ?",
                Integer.class, releaseId)).isEqualTo(100);
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(1));

        // 设备拉取返回最新尝试
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(t2))
                .andExpect(jsonPath("$.task.attemptNo").value(2));

        // 重试成功才更新设备版本；每次尝试首次回执各计一个样本，原失败样本不扣回
        receipt(t2, "r8", "SUCCESS");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1))
                .andExpect(jsonPath("$.roundFailed").value(1));

        // 旧任务迟到的不同结果回执 409，不改变设备或新任务
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r9\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_RESULT_CONFLICT"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "SUCCESS"))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].taskId").value(t2));
    }

    @Test
    void 重试失败分支_状态与版本校验_次数用尽422_不存在404_参数非法400() throws Exception {
        registerDevice("r1", "d1", "m1", 1);
        registerDevice("r2", "d2", "m1", 2);
        long releaseId = createRelease("r3", "m1", 100, 100, 100);
        long t1 = pullTask("r4", "d1");

        // 任务不存在：404
        mockMvc.perform(post("/api/tasks/9999/retry").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"expectedVersion\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));

        // 最新尝试为 PENDING：409
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FAILED"));

        // expectedVersion 不匹配：409
        receipt(t1, "r7", "FAILED");
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r8\",\"expectedVersion\":99}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 参数非法：缺 requestId / expectedVersion 越界，均 400
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r9\",\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());

        // 设备当前版本不等于来源版本：409（模拟外部变更）
        jdbc.update("UPDATE device SET current_version = '9.9.9' WHERE device_id = 'd1'");
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r10\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_VERSION_CHANGED"));
        jdbc.update("UPDATE device SET current_version = '1.0.0' WHERE device_id = 'd1'");

        // 三次尝试用尽可能：第2、3次失败后第4次重试 422
        long t2 = idOf(retry(t1, "r11", 1), "$.taskId");
        receipt(t2, "r12", "FAILED");
        long t3 = idOf(retry(t2, "r13", 1), "$.taskId");
        receipt(t3, "r14", "FAILED");
        mockMvc.perform(post("/api/tasks/" + t3 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r15\",\"expectedVersion\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RETRY_ATTEMPTS_EXHAUSTED"));

        // 非最新失败任务：409（t1/t2 都不是最新尝试）
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r16\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETRY_NOT_LATEST"));

        // 最新尝试为 SUCCESS：409
        long t4 = pullTask("r17", "d2");
        receipt(t4, "r18", "SUCCESS");
        mockMvc.perform(post("/api/tasks/" + t4 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r19\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FAILED"));

        // 发布单已取消：409（含已有失败任务也不能重试）
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r20\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + t3 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r21\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));
    }

    @Test
    void 重试_暂停时拒绝_暂停后已有重试仍可回执() throws Exception {
        registerDevice("r1", "d1", "m1", 1);
        registerDevice("r2", "d2", "m1", 2);
        long releaseId = createRelease("r3", "m1", 100, 2, 50);
        long t1 = pullTask("r4", "d1");
        long t2 = pullTask("r5", "d2");
        receipt(t1, "r6", "FAILED");

        // 暂停前创建的重试任务
        long t1Retry = idOf(retry(t1, "r7", 1), "$.taskId");

        // d2 失败触发自动暂停
        receipt(t2, "r8", "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // 暂停后不能再创建重试
        mockMvc.perform(post("/api/tasks/" + t1Retry + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r9\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));

        // 暂停后已有重试仍可回执，计入完成时轮次
        receipt(t1Retry, "r10", "SUCCESS");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.roundSuccess").value(1))
                .andExpect(jsonPath("$.roundFailed").value(2));
    }

    @Test
    void 重试回执计入完成时监控轮次_恢复后计入新轮次() throws Exception {
        registerDevice("r1", "d1", "m1", 1);
        registerDevice("r2", "d2", "m1", 2);
        long releaseId = createRelease("r3", "m1", 100, 2, 50);
        long t1 = pullTask("r4", "d1");
        long t2 = pullTask("r5", "d2");
        receipt(t1, "r6", "FAILED");
        receipt(t2, "r7", "FAILED");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.roundFailed").value(2));

        // 人工恢复开启第2轮，统计清零
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"r8\",\"expectedVersion\":1,\"reason\":\"已修复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.monitorRound").value(2));

        // 恢复后可重试（expectedVersion 为当前版本2）；创建不计样本
        long t1Retry = idOf(retry(t1, "r9", 2), "$.taskId");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.monitorRound").value(2))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));

        // 重试回执计入完成时的第2轮；原失败样本留在第1轮不扣回
        receipt(t1Retry, "r10", "SUCCESS");
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.monitorRound").value(2))
                .andExpect(jsonPath("$.roundSuccess").value(1))
                .andExpect(jsonPath("$.roundFailed").value(0));
        mockMvc.perform(get("/api/releases/" + releaseId + "/history"))
                .andExpect(jsonPath("$.pauses.length()").value(1))
                .andExpect(jsonPath("$.pauses[0].failedCount").value(2));
    }

    @Test
    void 重试幂等_同键同参重放_异参409_失败不占键_重放不复活已取消重试() throws Exception {
        registerDevice("r1", "d1", "m1", 1);
        long releaseId = createRelease("r2", "m1", 100, 100, 100);
        long t1 = pullTask("r3", "d1");
        receipt(t1, "r4", "FAILED");

        // 失败不占键：先以错误 expectedVersion 触发 409，再用同 requestId 正确参数成功
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"expectedVersion\":99}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        MvcResult created = mockMvc.perform(post("/api/tasks/" + t1 + "/retry")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andReturn();
        long t2 = idOf(created, "$.taskId");

        // 同 requestId 同参重放：返回首次结果，不新建任务
        MvcResult replay = mockMvc.perform(post("/api/tasks/" + t1 + "/retry")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(t2))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(created.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);

        // 同 requestId 异参（含期望版本不同）：409
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"expectedVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 取消发布单：重试任务一并终结；重放创建不复活已取消重试
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r6\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(t2))
                .andExpect(jsonPath("$.status").value("PENDING"));
        // 数据库中该任务保持 CANCELLED，未新建任何任务
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?",
                String.class, t2)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);
    }

    @Test
    void 任务历史_按设备与尝试序号列出前后继及结果_单次任务兼容序号1() throws Exception {
        registerDevice("r1", "d1", "m1", 1);
        registerDevice("r2", "d2", "m1", 2);
        long releaseId = createRelease("r3", "m1", 100, 100, 100);
        long t1 = pullTask("r4", "d1");
        receipt(t1, "r5", "FAILED");
        long t2 = idOf(retry(t1, "r6", 1), "$.taskId");
        receipt(t2, "r7", "FAILED");
        long t3 = idOf(retry(t2, "r8", 1), "$.taskId");
        receipt(t3, "r9", "SUCCESS");
        long tOther = pullTask("r10", "d2");

        // d1：三次尝试链，前驱/后继/结果齐全
        mockMvc.perform(get("/api/devices/d1/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("d1"))
                .andExpect(jsonPath("$.attempts.length()").value(3))
                .andExpect(jsonPath("$.attempts[0].taskId").value(t1))
                .andExpect(jsonPath("$.attempts[0].attemptNo").value(1))
                .andExpect(jsonPath("$.attempts[0].predecessorId").doesNotExist())
                .andExpect(jsonPath("$.attempts[0].successorId").value(t2))
                .andExpect(jsonPath("$.attempts[0].status").value("FAILED"))
                .andExpect(jsonPath("$.attempts[0].firstResult").value("FAILED"))
                .andExpect(jsonPath("$.attempts[1].taskId").value(t2))
                .andExpect(jsonPath("$.attempts[1].attemptNo").value(2))
                .andExpect(jsonPath("$.attempts[1].predecessorId").value(t1))
                .andExpect(jsonPath("$.attempts[1].successorId").value(t3))
                .andExpect(jsonPath("$.attempts[2].taskId").value(t3))
                .andExpect(jsonPath("$.attempts[2].attemptNo").value(3))
                .andExpect(jsonPath("$.attempts[2].predecessorId").value(t2))
                .andExpect(jsonPath("$.attempts[2].successorId").doesNotExist())
                .andExpect(jsonPath("$.attempts[2].status").value("SUCCESS"))
                .andExpect(jsonPath("$.attempts[2].firstResult").value("SUCCESS"));

        // d2：仅一次投放，兼容为序号1、无前驱后继
        mockMvc.perform(get("/api/devices/d2/tasks").param("releaseId", String.valueOf(releaseId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.attempts[0].taskId").value(tOther))
                .andExpect(jsonPath("$.attempts[0].attemptNo").value(1))
                .andExpect(jsonPath("$.attempts[0].predecessorId").doesNotExist())
                .andExpect(jsonPath("$.attempts[0].successorId").doesNotExist())
                .andExpect(jsonPath("$.attempts[0].status").value("PENDING"))
                .andExpect(jsonPath("$.attempts[0].firstResult").doesNotExist());

        // 设备不存在：404
        mockMvc.perform(get("/api/devices/ghost/tasks"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
    }
}
