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
 * 取消握手 API 测试（H2 内存库，MODE=MySQL）：显式开始占槽、仅 INSTALLING 受理回执、
 * 取消请求生成代次、同代次停止确认释放槽、发布整体取消不伪造已停、合成场景。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareCancelHandshakeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM device_install_slot");
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

    private long createRelease(String requestId, String model, String toVersion) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"%s","ratio":100}
                """.formatted(requestId, model, toVersion)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private long pullTask(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.cancelGeneration").doesNotExist())
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    private void startTask(long taskId, String requestId, String operator, int expectedVersion)
            throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/start").contentType("application/json").content("""
                {"requestId":"%s","operator":"%s","expectedVersion":%d}
                """.formatted(requestId, operator, expectedVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLING"));
    }

    private long slotCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM device_install_slot", Long.class);
        return count == null ? 0 : count;
    }

    @Test
    void 主流程_显式开始占槽_回执成功释放槽并更新版本() throws Exception {
        registerDevice("r-d1", "d1", "m1", 1);
        long releaseId = createRelease("r-r1", "m1", "2.0.0");
        long taskId = pullTask("r-p1", "d1");

        // expectedVersion 不符：409；operator 为空：400
        mockMvc.perform(post("/api/tasks/" + taskId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r-s0\",\"operator\":\"op1\",\"expectedVersion\":99}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r-s0b\",\"operator\":\"\",\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());

        startTask(taskId, "r-s1", "op1", 1);
        org.assertj.core.api.Assertions.assertThat(slotCount()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT task_id FROM device_install_slot WHERE device_id = 'd1'", Long.class))
                .isEqualTo(taskId);

        // 非 PENDING 重复开始：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r-s2\",\"operator\":\"op1\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_PENDING"));

        // INSTALLING 受理普通回执：成功终态、版本更新、槽释放、计样本
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        org.assertj.core.api.Assertions.assertThat(slotCount()).isZero();
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1));
    }

    @Test
    void 合成场景_安装中取消发布_停止确认后新任务可开始_旧成功回执拒绝() throws Exception {
        registerDevice("r-d1", "d1", "m1", 1);
        long releaseId1 = createRelease("r-r1", "m1", "2.0.0");
        long t1 = pullTask("r-p1", "d1");
        startTask(t1, "r-s1", "op1", 1);

        // 安装中取消发布：任务转 CANCEL_REQUESTED（代次1），仍占槽，设备 v1 不变，禁止新领取
        mockMvc.perform(post("/api/releases/" + releaseId1 + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-x1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/releases/" + releaseId1 + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].status").value("CANCEL_REQUESTED"))
                .andExpect(jsonPath("$.tasks[0].cancelGeneration").value(1));
        org.assertj.core.api.Assertions.assertThat(slotCount()).isEqualTo(1);
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());

        // CANCEL_REQUESTED 拒绝安装回执 409，设备版本不变
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCEL_REQUESTED"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 新发布单可创建并领取，但旧槽未释放不能开始：422
        long releaseId2 = createRelease("r-r2", "m1", "3.0.0");
        long t2 = pullTask("r-p2", "d1");
        mockMvc.perform(post("/api/tasks/" + t2 + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r-s2\",\"operator\":\"op2\",\"expectedVersion\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_SLOT_BUSY"));

        // 错误代次停止确认 409；正确代次确认后释放槽
        mockMvc.perform(post("/api/tasks/" + t1 + "/stop-confirmation").contentType("application/json")
                        .content("""
                        {"requestId":"r-k0","operator":"op1","expectedVersion":1,"generation":2}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_GENERATION_MISMATCH"));
        mockMvc.perform(post("/api/tasks/" + t1 + "/stop-confirmation").contentType("application/json")
                        .content("""
                        {"requestId":"r-k1","operator":"op1","expectedVersion":1,"generation":1}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelGeneration").value(1));
        org.assertj.core.api.Assertions.assertThat(slotCount()).isZero();

        // 槽释放后新任务可开始；旧任务 SUCCESS 回执拒绝，设备不切换 v2
        startTask(t2, "r-s2b", "op2", 1);
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc2\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 新任务正常完成，设备切换到新目标版本
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc3\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("3.0.0"));
        org.assertj.core.api.Assertions.assertThat(releaseId2).isPositive();
    }
}
