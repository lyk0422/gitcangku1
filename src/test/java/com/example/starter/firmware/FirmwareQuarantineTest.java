package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设备异常隔离 API 测试：隔离持续门禁、任务回查、双人解除、失败率隔离、
 * 发布启动预检、历史查询与幂等边界（H2 内存库，MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareQuarantineTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rejected_receipt");
        jdbc.update("DELETE FROM device_quarantine_record");
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

    private void registerDevice(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, String from, String to, int ratio)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"%s","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":%d}
                        """.formatted(requestId, model, from, to, ratio)))
                .andExpect(status().isOk())
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

    private void quarantine(String deviceId, String requestId, String operator, String reasonCode,
                            String expectedVersion) throws Exception {
        mockMvc.perform(post("/api/devices/" + deviceId + "/quarantine")
                        .contentType("application/json").content("""
                        {"requestId":"%s","operator":"%s","reasonCode":"%s","expectedVersion":"%s"}
                        """.formatted(requestId, operator, reasonCode, expectedVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("QUARANTINE"));
    }

    @Test
    void 隔离主流程_拉取422_双人解除后恢复拉取() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        createRelease("r2", "m1", "1.0.0", "2.0.0", 100);

        quarantine("d1", "q1", "op-a", "HW_SUSPECT", "1.0.0");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"));

        // 隔离设备不得拉取新任务：422 DEVICE_QUARANTINED
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_QUARANTINED"));

        // 同一运维人不能解除自己提交的隔离
        mockMvc.perform(post("/api/devices/d1/unquarantine").contentType("application/json").content("""
                        {"requestId":"q2","operator":"op-a","reason":"原因已消除","expectedVersion":"1.0.0"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAME_OPERATOR"));

        // 不同运维人确认后解除成功
        mockMvc.perform(post("/api/devices/d1/unquarantine").contentType("application/json").content("""
                        {"requestId":"q3","operator":"op-b","reason":"原因已消除","expectedVersion":"1.0.0"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("UNQUARANTINE"))
                .andExpect(jsonPath("$.operator").value("op-b"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.status").value("NORMAL"));

        // 解除后允许后续拉取
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"));

        // 隔离历史两条：QUARANTINE + UNQUARANTINE
        mockMvc.perform(get("/api/devices/d1/quarantines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(2))
                .andExpect(jsonPath("$.records[0].operation").value("QUARANTINE"))
                .andExpect(jsonPath("$.records[0].operator").value("op-a"))
                .andExpect(jsonPath("$.records[0].reasonCode").value("HW_SUSPECT"))
                .andExpect(jsonPath("$.records[1].operation").value("UNQUARANTINE"))
                .andExpect(jsonPath("$.records[1].operator").value("op-b"));
    }

    @Test
    void 隔离回查_未开始任务取消写原因_已开始任务保持进行中且回执被拒() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        registerDevice("r2", "d2", "m1", "1.0.0", 2);
        long releaseId = createRelease("r3", "m1", "1.0.0", "2.0.0", 100);
        long pendingTask = pullTask("d1", "r4");
        long startedTask = pullTask("d2", "r5");
        mockMvc.perform(post("/api/tasks/" + startedTask + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r6\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        quarantine("d1", "q1", "op-a", "HW_SUSPECT", "1.0.0");
        quarantine("d2", "q2", "op-a", "HW_SUSPECT", "1.0.0");

        // 未开始任务转 CANCELLED 并写入不可变取消原因
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "CANCELLED"))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].taskId").value(pendingTask))
                .andExpect(jsonPath("$.tasks[0].cancelReason").value("HW_SUSPECT"));
        // 已开始任务保持进行中
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "IN_PROGRESS"))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].taskId").value(startedTask));

        // 已开始任务的成功回执被拒：422、保留原版本、记录被拒回执、不计失败率样本
        mockMvc.perform(post("/api/tasks/" + startedTask + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r7\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_QUARANTINED"));
        mockMvc.perform(get("/api/devices/d2"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/devices/d2/rejected-receipts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipts.length()").value(1))
                .andExpect(jsonPath("$.receipts[0].taskId").value(startedTask))
                .andExpect(jsonPath("$.receipts[0].result").value("SUCCESS"))
                .andExpect(jsonPath("$.receipts[0].reasonCode").value("DEVICE_QUARANTINED"));

        // 存在进行中任务时解除隔离：409
        mockMvc.perform(post("/api/devices/d2/unquarantine").contentType("application/json").content("""
                        {"requestId":"q3","operator":"op-b","reason":"原因已消除","expectedVersion":"1.0.0"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_TASKS_IN_PROGRESS"));

        // 取消发布单终结进行中任务后可解除
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r8\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/d2/unquarantine").contentType("application/json").content("""
                        {"requestId":"q4","operator":"op-b","reason":"原因已消除","expectedVersion":"1.0.0"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("UNQUARANTINE"));
    }

    @Test
    void 隔离参数校验与失败分支() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);

        // 设备不存在：404
        mockMvc.perform(post("/api/devices/ghost/quarantine").contentType("application/json").content("""
                        {"requestId":"q1","operator":"op-a","reasonCode":"HW","expectedVersion":"1.0.0"}
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));

        // expectedVersion 与设备当前版本不一致：409
        mockMvc.perform(post("/api/devices/d1/quarantine").contentType("application/json").content("""
                        {"requestId":"q2","operator":"op-a","reasonCode":"HW","expectedVersion":"9.9.9"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 缺参数：400
        mockMvc.perform(post("/api/devices/d1/quarantine").contentType("application/json")
                        .content("{\"requestId\":\"q3\",\"operator\":\"op-a\"}"))
                .andExpect(status().isBadRequest());

        quarantine("d1", "q4", "op-a", "HW", "1.0.0");

        // 重复隔离：409
        mockMvc.perform(post("/api/devices/d1/quarantine").contentType("application/json").content("""
                        {"requestId":"q5","operator":"op-a","reasonCode":"HW","expectedVersion":"1.0.0"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_ALREADY_QUARANTINED"));

        // 解除时版本不符：409
        mockMvc.perform(post("/api/devices/d1/unquarantine").contentType("application/json").content("""
                        {"requestId":"q6","operator":"op-b","reason":"已消除","expectedVersion":"9.9.9"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 未隔离设备解除：409
        registerDevice("r2", "d2", "m1", "1.0.0", 2);
        mockMvc.perform(post("/api/devices/d2/unquarantine").contentType("application/json").content("""
                        {"requestId":"q7","operator":"op-b","reason":"已消除","expectedVersion":"1.0.0"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_QUARANTINED"));

        // 查询不存在设备的隔离历史与被拒回执：404
        mockMvc.perform(get("/api/devices/ghost/quarantines"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/devices/ghost/rejected-receipts"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 隔离幂等_同键重放首次结果_异参409_失败不占键() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        registerDevice("r2", "d2", "m1", "1.0.0", 2);

        MvcResult first = mockMvc.perform(post("/api/devices/d1/quarantine")
                        .contentType("application/json").content("""
                        {"requestId":"rid-q","operator":"op-a","reasonCode":"HW","expectedVersion":"1.0.0"}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        String firstBody = first.getResponse().getContentAsString();

        // 同键同参重放首次完整结果，隔离记录只落一条
        mockMvc.perform(post("/api/devices/d1/quarantine").contentType("application/json").content("""
                        {"requestId":"rid-q","operator":"op-a","reasonCode":"HW","expectedVersion":"1.0.0"}
                        """))
                .andExpect(status().isOk())
                .andExpect(content -> org.assertj.core.api.Assertions
                        .assertThat(content.getResponse().getContentAsString()).isEqualTo(firstBody));
        mockMvc.perform(get("/api/devices/d1/quarantines"))
                .andExpect(jsonPath("$.records.length()").value(1));

        // 同键异参：409
        mockMvc.perform(post("/api/devices/d1/quarantine").contentType("application/json").content("""
                        {"requestId":"rid-q","operator":"op-a","reasonCode":"OTHER","expectedVersion":"1.0.0"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：先以错误版本触发 409，再用同 requestId 正确参数成功
        mockMvc.perform(post("/api/devices/d2/quarantine").contentType("application/json").content("""
                        {"requestId":"rid-q2","operator":"op-a","reasonCode":"HW","expectedVersion":"9.9.9"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(post("/api/devices/d2/quarantine").contentType("application/json").content("""
                        {"requestId":"rid-q2","operator":"op-a","reasonCode":"HW","expectedVersion":"1.0.0"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("QUARANTINE"));
    }

    @Test
    void 发布启动_全部候选隔离422_预检按可投放集合计算() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        registerDevice("r2", "d2", "m1", "1.0.0", 2);
        registerDevice("r3", "d3", "m1", "9.9.9", 3);

        quarantine("d1", "q1", "op-a", "HW", "1.0.0");

        // 预检：候选2台（d3版本不匹配不计），隔离1台，可投放1台，可启动
        mockMvc.perform(get("/api/releases/precheck")
                        .param("model", "m1").param("fromVersion", "1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateCount").value(2))
                .andExpect(jsonPath("$.quarantinedCount").value(1))
                .andExpect(jsonPath("$.deliverableCount").value(1))
                .andExpect(jsonPath("$.startable").value(true));

        long releaseId = createRelease("r4", "m1", "1.0.0", "2.0.0", 100);
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r5\"}"))
                .andExpect(status().isOk());

        // 全部候选隔离后：预检不可启动，创建发布单 422
        quarantine("d2", "q2", "op-a", "HW", "1.0.0");
        mockMvc.perform(get("/api/releases/precheck")
                        .param("model", "m1").param("fromVersion", "1.0.0"))
                .andExpect(jsonPath("$.candidateCount").value(2))
                .andExpect(jsonPath("$.quarantinedCount").value(2))
                .andExpect(jsonPath("$.deliverableCount").value(0))
                .andExpect(jsonPath("$.startable").value(false));
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r6","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ALL_CANDIDATES_QUARANTINED"));

        // 无候选设备的型号仍可启动
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r7","model":"m9","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                        """))
                .andExpect(status().isOk());
    }

    @Test
    void 失败率隔离_隔离设备回执不计样本不触发暂停() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        registerDevice("r2", "d2", "m1", "1.0.0", 2);
        MvcResult release = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r3","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,"sampleFloor":2,"failureThresholdPercent":50}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        long releaseId = idOf(release, "$.releaseId");
        long t1 = pullTask("d1", "r4");
        long t2 = pullTask("d2", "r5");

        quarantine("d2", "q1", "op-a", "HW", "1.0.0");

        // 正常设备失败回执计入样本
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        // 隔离设备失败回执同样被拒且不计样本：样本数不达下限，发布单保持 ACTIVE
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r7\",\"result\":\"FAILED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_QUARANTINED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/devices/d2/rejected-receipts"))
                .andExpect(jsonPath("$.receipts.length()").value(1))
                .andExpect(jsonPath("$.receipts[0].result").value("FAILED"));
    }

    @Test
    void 历史不可改写_解除后取消任务回执仍409且拉取返回原取消任务() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r2", "m1", "1.0.0", "2.0.0", 100);
        long taskId = pullTask("d1", "r3");

        quarantine("d1", "q1", "op-a", "HW", "1.0.0");
        mockMvc.perform(post("/api/devices/d1/unquarantine").contentType("application/json").content("""
                        {"requestId":"q2","operator":"op-b","reason":"已消除","expectedVersion":"1.0.0"}
                        """))
                .andExpect(status().isOk());

        // 历史取消不可改写：回执仍 409，拉取返回原 CANCELLED 任务
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(taskId))
                .andExpect(jsonPath("$.task.status").value("CANCELLED"))
                .andExpect(jsonPath("$.task.cancelReason").value("HW"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].cancelReason").value("HW"));
    }

    @Test
    void 任务开始_状态流转_终态409_隔离设备422() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        registerDevice("r2", "d2", "m1", "1.0.0", 2);
        createRelease("r3", "m1", "1.0.0", "2.0.0", 100);
        long t1 = pullTask("d1", "r4");
        long t2 = pullTask("d2", "r5");

        // PENDING → IN_PROGRESS；重复开始（新 requestId）返回当前状态
        mockMvc.perform(post("/api/tasks/" + t1 + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r6\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mockMvc.perform(post("/api/tasks/" + t1 + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        // 已开始任务可回执成功并更新版本
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r8\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 终态任务不能开始：409
        mockMvc.perform(post("/api/tasks/" + t1 + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r9\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_STARTABLE"));

        // 隔离设备上的任务不能开始：422（隔离已将该 PENDING 任务取消）
        quarantine("d2", "q1", "op-a", "HW", "1.0.0");
        mockMvc.perform(post("/api/tasks/" + t2 + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r10\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_QUARANTINED"));

        // 任务不存在：404
        mockMvc.perform(post("/api/tasks/9999/start").contentType("application/json")
                        .content("{\"requestId\":\"r11\"}"))
                .andExpect(status().isNotFound());
    }
}
