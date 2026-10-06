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
 * 安全索引联合裁决测试（H2 内存库，MODE=MySQL）：维护提高底线后低索引 PENDING 任务回执 409、
 * 取消终结旧任务、新发布新建任务、FAILED 不推进底线、终态异索引 409、成功键重放不回写底线。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareSecurityAdjudicationTest {

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

    private void registerDevice(String requestId, String deviceId, String model, int floor) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":5,
                "securityFloor":%d}
                """.formatted(requestId, deviceId, model, floor)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, int targetIndex) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "targetSecurityIndex":%d}
                """.formatted(requestId, model, targetIndex)))
                .andExpect(status().isOk())
                .andReturn();
        return FirmwareSecurityIndexTest.idOf(result, "$.releaseId");
    }

    private long pullTask(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        return FirmwareSecurityIndexTest.idOf(result, "$.task.taskId");
    }

    private void maintain(String deviceId, String requestId, int expectedVersion, int floor) throws Exception {
        mockMvc.perform(post("/api/devices/" + deviceId + "/maintenance").contentType("application/json")
                        .content("""
                                {"requestId":"%s","operator":"ops","expectedDeviceVersion":%d,"securityFloor":%d}
                                """.formatted(requestId, expectedVersion, floor)))
                .andExpect(status().isOk());
    }

    @Test
    void 题干场景_底线3领目标4任务维护到5回执拒绝_取消重建目标5成功底线仍5() throws Exception {
        registerDevice("r1", "d1", "m1", 3);
        long releaseId = createRelease("r2", "m1", 4);
        long taskId = pullTask("r3", "d1");

        // 维护提升底线到 5
        maintain("d1", "m1", 1, 5);

        // 目标 4 成功回执拒绝：409，任务仍 PENDING，不更新版本与监控样本，不伪造失败
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\",\"securityIndex\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECURITY_FLOOR_CONFLICT"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc2\",\"result\":\"FAILED\",\"securityIndex\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECURITY_FLOOR_CONFLICT"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].status").value("PENDING"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"))
                .andExpect(jsonPath("$.securityFloor").value(5));

        // 取消旧发布：PENDING 任务按原流程终结，不可复用
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].status").value("CANCELLED"));

        // 创建目标 5 新发布：同设备生成新任务（不复用旧任务）
        long releaseId2 = createRelease("r4", "m1", 5);
        assertThat(releaseId2).isNotEqualTo(releaseId);
        long taskId2 = pullTask("r5", "d1");
        assertThat(taskId2).isNotEqualTo(taskId);

        // 目标 5 成功：底线仍 5（取最大），档案版本再加一
        mockMvc.perform(post("/api/tasks/" + taskId2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc3\",\"result\":\"SUCCESS\",\"securityIndex\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.securityFloor").value(5))
                .andExpect(jsonPath("$.securityProfileVersion").value(3));
    }

    @Test
    void FAILED不推进底线_终态新键异索引409_同结果同索引重放无副作用() throws Exception {
        registerDevice("r1", "d1", "m1", 3);
        long releaseId = createRelease("r2", "m1", 4);
        long taskId = pullTask("r3", "d1");

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"FAILED\",\"securityIndex\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.receiptSecurityIndex").value(4));

        // FAILED 不推进底线与档案版本
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"))
                .andExpect(jsonPath("$.securityFloor").value(3))
                .andExpect(jsonPath("$.securityProfileVersion").value(1));

        // 终态收到新键且证明索引不同：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc2\",\"result\":\"FAILED\",\"securityIndex\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECURITY_INDEX_MISMATCH"));

        // 新键同结果同索引：成功重放且不重复计数
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc3\",\"result\":\"FAILED\",\"securityIndex\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(0));
    }

    @Test
    void 旧成功键只重放原响应_不回写较低底线() throws Exception {
        registerDevice("r1", "d1", "m1", 3);
        createRelease("r2", "m1", 4);
        long taskId = pullTask("r3", "d1");

        MvcResult first = mockMvc.perform(post("/api/tasks/" + taskId + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\",\"securityIndex\":4}"))
                .andExpect(status().isOk())
                .andReturn();
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.securityFloor").value(4))
                .andExpect(jsonPath("$.securityProfileVersion").value(2));

        // 维护把底线提到 6
        maintain("d1", "m1", 2, 6);

        // 旧成功键重放：返回首次完整响应，不回写较低底线
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\",\"securityIndex\":4}"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));

        // 新键同结果同索引：成功重放，同样不回写底线
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc2\",\"result\":\"SUCCESS\",\"securityIndex\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.securityFloor").value(6))
                .andExpect(jsonPath("$.securityProfileVersion").value(3));
    }

    @Test
    void SUCCESS缺索引或异索引409_任务不终结_失败不占键() throws Exception {
        registerDevice("r1", "d1", "m1", 1);
        long releaseId = createRelease("r2", "m1", 4);
        long taskId = pullTask("r3", "d1");

        // SUCCESS 未携带证明索引：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECURITY_INDEX_MISMATCH"));

        // 证明索引与冻结目标不同：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc2\",\"result\":\"SUCCESS\",\"securityIndex\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECURITY_INDEX_MISMATCH"));

        // 任务未终结、样本未计、设备未变；失败不占键，正确回执仍成功
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].status").value("PENDING"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc2\",\"result\":\"SUCCESS\",\"securityIndex\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.securityFloor").value(4));
    }
}
