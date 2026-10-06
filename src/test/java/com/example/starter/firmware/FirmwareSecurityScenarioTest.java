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
 * 题干合成场景与终态/重放规则测试（H2 内存库，MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareSecurityScenarioTest {

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

    private void registerDevice(String requestId, String deviceId, int floor) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "securityFloor":%d}
                """.formatted(requestId, deviceId, floor)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, int targetIndex) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "targetIndex":%d}
                """.formatted(requestId, targetIndex)))
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

    @Test
    void 合成场景_底线3领目标4维护到5回执拒绝_取消重建目标5成功底线仍5() throws Exception {
        registerDevice("r1", "d1", 3);
        long releaseId = createRelease("r2", 4);
        long taskId = pullTask("r3", "d1");

        // 维护提升底线到 5：安全档案版本 1->2
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"r4","operator":"ops","expectedDeviceVersion":1,"securityFloor":5}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityFloor").value(5))
                .andExpect(jsonPath("$.securityVersion").value(2));

        // 目标 4 的成功回执被拒绝：不更新固件版本、不计监控样本、不伪造失败
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"result\":\"SUCCESS\",\"proofIndex\":4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECURITY_FLOOR_RAISED"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"))
                .andExpect(jsonPath("$.securityFloor").value(5))
                .andExpect(jsonPath("$.securityVersion").value(2));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].status").value("PENDING"));

        // 取消旧发布：PENDING 任务按原流程终结
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r6\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].status").value("CANCELLED"));

        // 创建目标 5 新发布：同设备不复用旧任务，领取新任务并成功
        long releaseId2 = createRelease("r7", 5);
        long taskId2 = pullTask("r8", "d1");
        assertThat(taskId2).isNotEqualTo(taskId);
        mockMvc.perform(post("/api/tasks/" + taskId2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r9\",\"result\":\"SUCCESS\",\"proofIndex\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.targetIndex").value(5))
                .andExpect(jsonPath("$.proofIndex").value(5));

        // 成功后底线仍 5（取两者最大），固件版本更新，档案版本 2->3
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.securityFloor").value(5))
                .andExpect(jsonPath("$.securityVersion").value(3));
        mockMvc.perform(get("/api/releases/" + releaseId2 + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1));
    }

    @Test
    void 终态_新键异证明409_同证明200_旧成功键重放不回写较低底线() throws Exception {
        registerDevice("r1", "d1", 3);
        long releaseId = createRelease("r2", 4);
        long taskId = pullTask("r3", "d1");

        MvcResult first = mockMvc.perform(post("/api/tasks/" + taskId + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"SUCCESS\",\"proofIndex\":4}"))
                .andExpect(status().isOk())
                .andReturn();
        // 成功安装：底线 max(3,4)=4，档案版本 1->2
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.securityFloor").value(4))
                .andExpect(jsonPath("$.securityVersion").value(2));

        // 维护把底线提到 5
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"r5","expectedDeviceVersion":2,"securityFloor":5}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityFloor").value(5));

        // 旧成功键重放：只重放原响应，不回写较低底线
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"SUCCESS\",\"proofIndex\":4}"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.securityFloor").value(5))
                .andExpect(jsonPath("$.securityVersion").value(3));

        // 终态收到新键且证明索引不同：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"result\":\"SUCCESS\",\"proofIndex\":9}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROOF_INDEX_MISMATCH"));

        // 终态收到新键、同结果同证明：成功且不重复计数
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r7\",\"result\":\"SUCCESS\",\"proofIndex\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.securityFloor").value(5))
                .andExpect(jsonPath("$.securityVersion").value(3));
    }
}
