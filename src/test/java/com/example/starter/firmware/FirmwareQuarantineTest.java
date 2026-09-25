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
 * 设备异常隔离 API 测试（H2 内存库，MODE=MySQL）：
 * 隔离持续门禁、任务回查、双人解除、失败率隔离、发布启动预检、历史查询与幂等。
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
        jdbc.update("DELETE FROM task_cancel_reason");
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

    private void registerDevice(String requestId, String deviceId, String model, int bucket) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":%d}
                """.formatted(requestId, deviceId, model, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, int ratio) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d}
                """.formatted(requestId, model, ratio)))
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

    private void startTask(long taskId, String requestId) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STARTED"));
    }

    private void quarantine(String deviceId, String requestId, String operator, String reasonCode)
            throws Exception {
        mockMvc.perform(post("/api/devices/" + deviceId + "/quarantine").contentType("application/json")
                        .content("""
                                {"requestId":"%s","operator":"%s","reasonCode":"%s","expectedVersion":"1.0.0"}
                                """.formatted(requestId, operator, reasonCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("QUARANTINE"))
                .andExpect(jsonPath("$.deviceId").value(deviceId));
    }

    @Test
    void 隔离主流程_未开始任务取消写原因_拉取与开始被门禁拒绝() throws Exception {
        registerDevice("r-d1", "d1", "m1", 1);
        registerDevice("r-d2", "d2", "m1", 2);
        long releaseId = createRelease("r-r1", "m1", 100);
        long t1 = pullTask("r-p1", "d1");
        long t2 = pullTask("r-p2", "d2");
        startTask(t2, "r-s2");

        quarantine("d1", "r-q1", "op1", "OVERHEAT");

        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.status").value("QUARANTINED"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 未开始任务转 CANCELLED 并写入不可变取消原因
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "CANCELLED"))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].taskId").value(t1));
        mockMvc.perform(get("/api/tasks/" + t1 + "/cancel-reason"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasonCode").value("DEVICE_QUARANTINED"))
                .andExpect(jsonPath("$.operator").value("op1"))
                .andExpect(jsonPath("$.detail").value("设备隔离取消未开始任务，隔离原因: OVERHEAT"));
        mockMvc.perform(get("/api/devices/d1/cancel-reasons"))
                .andExpect(jsonPath("$.reasons.length()").value(1));

        // 隔离设备拉取：422 DEVICE_QUARANTINED
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1b\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_QUARANTINED"));

        // 隔离设备开始任务：422 DEVICE_QUARANTINED
        mockMvc.perform(post("/api/tasks/" + t1 + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r-s1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_QUARANTINED"));

        // 隔离历史可查
        mockMvc.perform(get("/api/devices/d1/quarantines"))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].action").value("QUARANTINE"))
                .andExpect(jsonPath("$.records[0].reasonCode").value("OVERHEAT"))
                .andExpect(jsonPath("$.records[0].expectedVersion").value("1.0.0"))
                .andExpect(jsonPath("$.records[0].operator").value("op1"));

        // 未隔离设备不受影响
        mockMvc.perform(get("/api/devices/d2"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 已取消任务的后到回执仍是 409，不可改写
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
    }

    @Test
    void 隔离中任务_成功回执422留痕不计样本_失败回执终结但不计失败率() throws Exception {
        registerDevice("r-d1", "d1", "m1", 1);
        registerDevice("r-d2", "d2", "m1", 2);
        long releaseId = createRelease("r-r1", "m1", 100);
        long t1 = pullTask("r-p1", "d1");
        startTask(t1, "r-s1");
        quarantine("d1", "r-q1", "op1", "SENSOR_FAULT");

        // 成功回执：422，任务保持 STARTED，设备版本保留，不计样本
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_QUARANTINED"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "STARTED"))
                .andExpect(jsonPath("$.tasks.length()").value(1));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 被拒回执留痕可查
        mockMvc.perform(get("/api/devices/d1/rejected-receipts"))
                .andExpect(jsonPath("$.receipts.length()").value(1))
                .andExpect(jsonPath("$.receipts[0].taskId").value(t1))
                .andExpect(jsonPath("$.receipts[0].submittedResult").value("SUCCESS"))
                .andExpect(jsonPath("$.receipts[0].rejectCode").value("DEVICE_QUARANTINED"));

        // 失败回执：终结任务但不计失败率样本、不触发暂停
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc2\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 无进行中任务后由不同运维解除
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json")
                        .content("""
                                {"requestId":"r-rel1","operator":"op2","reasonCode":"FIXED","expectedVersion":"1.0.0"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("RELEASE"));

        // 解除后历史被拒回执不可改写、仍可查
        mockMvc.perform(get("/api/devices/d1/rejected-receipts"))
                .andExpect(jsonPath("$.receipts.length()").value(1));
        // 解除后允许后续拉取：已 FAILED 任务返回原任务，不重开
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1c\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(t1))
                .andExpect(jsonPath("$.task.status").value("FAILED"));
    }

    @Test
    void 解除隔离_双人确认_无进行中任务_状态校验() throws Exception {
        registerDevice("r-d1", "d1", "m1", 1);
        quarantine("d1", "r-q1", "op1", "OVERHEAT");

        // 同一运维不能解除：409
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json")
                        .content("""
                                {"requestId":"r-rel1","operator":"op1","reasonCode":"FIXED","expectedVersion":"1.0.0"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAME_OPERATOR"));

        // 版本不匹配：409
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json")
                        .content("""
                                {"requestId":"r-rel2","operator":"op2","reasonCode":"FIXED","expectedVersion":"9.9.9"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 不同运维确认解除：200
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json")
                        .content("""
                                {"requestId":"r-rel3","operator":"op2","reasonCode":"FIXED","expectedVersion":"1.0.0"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("RELEASE"))
                .andExpect(jsonPath("$.operator").value("op2"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 未隔离设备解除：409
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json")
                        .content("""
                                {"requestId":"r-rel4","operator":"op3","reasonCode":"FIXED","expectedVersion":"1.0.0"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_QUARANTINED"));

        // 历史完整：QUARANTINE + RELEASE 各一条
        mockMvc.perform(get("/api/devices/d1/quarantines"))
                .andExpect(jsonPath("$.records.length()").value(2))
                .andExpect(jsonPath("$.records[0].action").value("QUARANTINE"))
                .andExpect(jsonPath("$.records[1].action").value("RELEASE"))
                .andExpect(jsonPath("$.records[1].reasonCode").value("FIXED"));

        // 解除后可再次隔离（新一轮双人确认）
        quarantine("d1", "r-q2", "op2", "AGAIN");
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json")
                        .content("""
                                {"requestId":"r-rel5","operator":"op1","reasonCode":"OK","expectedVersion":"1.0.0"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/d1/quarantines"))
                .andExpect(jsonPath("$.records.length()").value(4));
    }

    @Test
    void 解除隔离_存在进行中任务409_任务终结后可解除() throws Exception {
        registerDevice("r-d1", "d1", "m1", 1);
        long releaseId = createRelease("r-r1", "m1", 100);
        long t1 = pullTask("r-p1", "d1");
        startTask(t1, "r-s1");
        quarantine("d1", "r-q1", "op1", "OVERHEAT");

        // 进行中任务存在：解除 409
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json")
                        .content("""
                                {"requestId":"r-rel1","operator":"op2","reasonCode":"FIXED","expectedVersion":"1.0.0"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASKS_IN_PROGRESS"));

        // 隔离中失败回执终结任务（不计样本）后可解除
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc1\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(0));
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json")
                        .content("""
                                {"requestId":"r-rel2","operator":"op2","reasonCode":"FIXED","expectedVersion":"1.0.0"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void 发布启动_全部候选设备隔离422_预检可查() throws Exception {
        registerDevice("r-d1", "d1", "m1", 1);
        registerDevice("r-d2", "d2", "m1", 2);
        quarantine("d1", "r-q1", "op1", "FAULT");
        quarantine("d2", "r-q2", "op1", "FAULT");

        // 预检：全部候选隔离，不可启动
        mockMvc.perform(get("/api/releases/precheck")
                        .param("model", "m1").param("fromVersion", "1.0.0").param("ratio", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateCount").value(2))
                .andExpect(jsonPath("$.quarantinedCount").value(2))
                .andExpect(jsonPath("$.deployableCount").value(0))
                .andExpect(jsonPath("$.launchable").value(false));

        // 启动：422
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-r1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ALL_CANDIDATES_QUARANTINED"));

        // 解除一台后可启动
        mockMvc.perform(post("/api/devices/d2/quarantine/release").contentType("application/json")
                        .content("""
                                {"requestId":"r-rel1","operator":"op2","reasonCode":"FIXED","expectedVersion":"1.0.0"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/releases/precheck")
                        .param("model", "m1").param("fromVersion", "1.0.0").param("ratio", "100"))
                .andExpect(jsonPath("$.deployableCount").value(1))
                .andExpect(jsonPath("$.launchable").value(true));
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-r2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 预检参数校验：比例越界 400
        mockMvc.perform(get("/api/releases/precheck")
                        .param("model", "m1").param("fromVersion", "1.0.0").param("ratio", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 隔离幂等_同键重放_异参409_失败不占键() throws Exception {
        registerDevice("r-d1", "d1", "m1", 1);

        MvcResult first = mockMvc.perform(post("/api/devices/d1/quarantine").contentType("application/json")
                        .content("""
                                {"requestId":"q1","operator":"op1","reasonCode":"FAULT","expectedVersion":"1.0.0"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        // 同键同参重放：响应一致，记录不重复
        mockMvc.perform(post("/api/devices/d1/quarantine").contentType("application/json")
                        .content("""
                                {"requestId":"q1","operator":"op1","reasonCode":"FAULT","expectedVersion":"1.0.0"}
                                """))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));
        mockMvc.perform(get("/api/devices/d1/quarantines"))
                .andExpect(jsonPath("$.records.length()").value(1));

        // 同键异参：409
        mockMvc.perform(post("/api/devices/d1/quarantine").contentType("application/json")
                        .content("""
                                {"requestId":"q1","operator":"op9","reasonCode":"FAULT","expectedVersion":"1.0.0"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 已隔离设备重复隔离（新键）：409 ALREADY_QUARANTINED
        mockMvc.perform(post("/api/devices/d1/quarantine").contentType("application/json")
                        .content("""
                                {"requestId":"q2","operator":"op2","reasonCode":"FAULT","expectedVersion":"1.0.0"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_QUARANTINED"));

        // 失败不占键：错误版本触发 409 后，同键正确参数可成功
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json")
                        .content("""
                                {"requestId":"q3","operator":"op2","reasonCode":"FIXED","expectedVersion":"9.9.9"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(post("/api/devices/d1/quarantine/release").contentType("application/json")
                        .content("""
                                {"requestId":"q3","operator":"op2","reasonCode":"FIXED","expectedVersion":"1.0.0"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("RELEASE"));

        // 设备不存在：404
        mockMvc.perform(post("/api/devices/ghost/quarantine").contentType("application/json")
                        .content("""
                                {"requestId":"q4","operator":"op1","reasonCode":"FAULT","expectedVersion":"1.0.0"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/devices/ghost/quarantines"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 任务开始_状态流转与边界() throws Exception {
        registerDevice("r-d1", "d1", "m1", 1);
        long releaseId = createRelease("r-r1", "m1", 100);
        long t1 = pullTask("r-p1", "d1");

        // PENDING -> STARTED
        startTask(t1, "r-s1");
        // 已 STARTED：幂等返回当前状态
        mockMvc.perform(post("/api/tasks/" + t1 + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r-s1b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STARTED"));
        // STARTED 任务正常回执成功并更新版本
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        // 已终结任务不能开始
        mockMvc.perform(post("/api/tasks/" + t1 + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r-s1c\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ALREADY_FINISHED"));

        // 已取消任务不能开始（先取消发布单；同设备重复拉取仍返回原 SUCCESS 任务）
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(t1))
                .andExpect(jsonPath("$.task.status").value("SUCCESS"));
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-c1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + t1 + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r-s1d\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ALREADY_FINISHED"));

        // 任务不存在：404
        mockMvc.perform(post("/api/tasks/9999/start").contentType("application/json")
                        .content("{\"requestId\":\"r-s9\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tasks/9999/cancel-reason"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 发布单取消_未终结任务写取消原因() throws Exception {
        registerDevice("r-d1", "d1", "m1", 1);
        registerDevice("r-d2", "d2", "m1", 2);
        long releaseId = createRelease("r-r1", "m1", 100);
        long t1 = pullTask("r-p1", "d1");
        long t2 = pullTask("r-p2", "d2");
        startTask(t2, "r-s2");

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-c1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // PENDING 与 STARTED 任务均取消并写不可变原因
        mockMvc.perform(get("/api/tasks/" + t1 + "/cancel-reason"))
                .andExpect(jsonPath("$.reasonCode").value("RELEASE_CANCELLED"))
                .andExpect(jsonPath("$.operator").value("SYSTEM"));
        mockMvc.perform(get("/api/tasks/" + t2 + "/cancel-reason"))
                .andExpect(jsonPath("$.reasonCode").value("RELEASE_CANCELLED"));
        mockMvc.perform(get("/api/devices/d1/cancel-reasons"))
                .andExpect(jsonPath("$.reasons.length()").value(1));
    }
}
