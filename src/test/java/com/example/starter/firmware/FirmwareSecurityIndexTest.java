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
 * 安全索引 API 测试（H2 内存库，MODE=MySQL）：登记底线、维护提升、拉取裁决、回执证明索引。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareSecurityIndexTest {

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

    private long createRelease(String requestId, Integer targetIndex) throws Exception {
        String target = targetIndex == null ? "" : ",\"targetIndex\":" + targetIndex;
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100%s}
                """.formatted(requestId, target)))
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
    void 登记_安全底线与安全档案版本() throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r1","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                        "securityFloor":3}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityFloor").value(3))
                .andExpect(jsonPath("$.securityVersion").value(1));

        // 旧客户端不传底线：缺省 0
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r2","deviceId":"d2","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityFloor").value(0))
                .andExpect(jsonPath("$.securityVersion").value(1));

        // 负底线：400
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r3","deviceId":"d3","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                        "securityFloor":-1}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 维护_提升底线版本加一_下降400_版本不符409_幂等() throws Exception {
        registerDevice("r1", "d1", 3);

        // expectedDeviceVersion 不符：409
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"m0","operator":"ops","expectedDeviceVersion":9,"securityFloor":4}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 底线下降：400
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"m1","operator":"ops","expectedDeviceVersion":1,"securityFloor":2}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FLOOR_DECREASE"));

        // 失败不占键：同 requestId 换合法参数成功，版本 1->2
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"m1","operator":"ops","expectedDeviceVersion":1,"securityFloor":5}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityFloor").value(5))
                .andExpect(jsonPath("$.securityVersion").value(2))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 同键同参重放：版本不再增加
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"m1","operator":"ops","expectedDeviceVersion":1,"securityFloor":5}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityVersion").value(2));

        // 同键异参（操作者不同）：409
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                        {"requestId":"m1","operator":"other","expectedDeviceVersion":1,"securityFloor":5}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 设备不存在：404
        mockMvc.perform(post("/api/devices/ghost/maintenance").contentType("application/json").content("""
                        {"requestId":"m9","expectedDeviceVersion":1,"securityFloor":1}
                        """))
                .andExpect(status().isNotFound());
    }

    @Test
    void 拉取_底线高于目标不投放_等于可投放_旧发布不适用() throws Exception {
        registerDevice("r1", "d1", 5);
        registerDevice("r2", "d2", 4);
        long releaseId = createRelease("r3", 4);

        // 底线 5 > 目标 4：不投放
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());

        // 底线 4 == 目标 4：投放并冻结目标索引
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.targetIndex").value(4));

        // 旧发布未填目标索引：不适用，底线再高也投放
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r6\"}"))
                .andExpect(status().isOk());
        createRelease("r7", null);
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r8\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.targetIndex").doesNotExist());
    }

    @Test
    void 回执_缺证明或异证明409_FAILED不推进底线() throws Exception {
        registerDevice("r1", "d1", 3);
        registerDevice("r2", "d2", 3);
        long releaseId = createRelease("r3", 4);
        long t1 = pullTask("r4", "d1");
        long t2 = pullTask("r5", "d2");

        // 缺证明索引：409
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROOF_INDEX_MISMATCH"));

        // 证明索引与冻结目标不同：409
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r7\",\"result\":\"SUCCESS\",\"proofIndex\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROOF_INDEX_MISMATCH"));

        // 拒绝后任务仍 PENDING，监控样本未计数
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));

        // 携带同一索引：成功并写入安装证据
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r8\",\"result\":\"SUCCESS\",\"proofIndex\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.proofIndex").value(4));

        // FAILED 不推进底线与档案版本
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r9\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        mockMvc.perform(get("/api/devices/d2"))
                .andExpect(jsonPath("$.securityFloor").value(3))
                .andExpect(jsonPath("$.securityVersion").value(1))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));
    }
}
