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
 * 安全索引主流程与维护入口测试（H2 内存库，MODE=MySQL）：
 * 登记底线、发布目标索引、拉取冻结、成功安装推进底线与档案版本、维护提升底线。
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

    static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    void registerDevice(String requestId, String deviceId, String model, int bucket, int floor)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":%d,
                "securityFloor":%d}
                """.formatted(requestId, deviceId, model, bucket, floor)))
                .andExpect(status().isOk());
    }

    long createRelease(String requestId, String model, int ratio, Integer targetIndex) throws Exception {
        String indexField = targetIndex == null ? "" : ",\"targetSecurityIndex\":" + targetIndex;
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d%s}
                """.formatted(requestId, model, ratio, indexField)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    long pullTask(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    @Test
    void 主流程_登记底线发布索引_成功安装推进底线与档案版本() throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r1","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":5,
                "securityFloor":3}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityFloor").value(3))
                .andExpect(jsonPath("$.securityProfileVersion").value(1));

        MvcResult release = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "targetSecurityIndex":4}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetSecurityIndex").value(4))
                .andReturn();
        long releaseId = idOf(release, "$.releaseId");

        // 拉取冻结目标索引
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.targetSecurityIndex").value(4))
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");

        // SUCCESS 携带同一索引：固件版本更新、底线取最大、档案版本加一、证据冻结
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"SUCCESS\",\"securityIndex\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.receiptSecurityIndex").value(4));

        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.securityFloor").value(4))
                .andExpect(jsonPath("$.securityProfileVersion").value(2));

        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1));
    }

    @Test
    void 维护_提升底线版本加一_下降400_版本不符409_幂等重放() throws Exception {
        registerDevice("r1", "d1", "m1", 5, 3);

        // expectedDeviceVersion 不符：409
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                {"requestId":"m0","operator":"ops","expectedDeviceVersion":2,"securityFloor":5}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_VERSION_CONFLICT"));

        // 底线下降：400
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                {"requestId":"m1","operator":"ops","expectedDeviceVersion":1,"securityFloor":2}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SECURITY_FLOOR_DECREASE"));

        // 失败不占键：同 requestId 修正参数后成功
        MvcResult first = mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json")
                        .content("""
                                {"requestId":"m1","operator":"ops","expectedDeviceVersion":1,"securityFloor":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securityFloor").value(5))
                .andExpect(jsonPath("$.securityProfileVersion").value(2))
                .andReturn();

        // 同键同参重放：响应一致，版本不再加
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                {"requestId":"m1","operator":"ops","expectedDeviceVersion":1,"securityFloor":5}
                """))
                .andExpect(status().isOk())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.securityProfileVersion").value(2));

        // 同键异参（操作者不同也算异参）：409
        mockMvc.perform(post("/api/devices/d1/maintenance").contentType("application/json").content("""
                {"requestId":"m1","operator":"other","expectedDeviceVersion":1,"securityFloor":5}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 设备不存在：404
        mockMvc.perform(post("/api/devices/ghost/maintenance").contentType("application/json").content("""
                {"requestId":"m9","operator":"ops","expectedDeviceVersion":1,"securityFloor":5}
                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void 拉取_底线高于目标索引不创建任务_旧发布未填索引不适用() throws Exception {
        registerDevice("r1", "d1", "m1", 5, 6);
        registerDevice("r2", "d2", "m2", 5, 9);

        // 底线 6 高于目标索引 4：不投放
        createRelease("r3", "m1", 100, 4);
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();

        // 旧发布未填索引：索引约束不适用，可拉取可成功，底线与档案版本不动
        long releaseId = createRelease("r5", "m2", 100, null);
        long taskId = pullTask("r6", "d2");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r7\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d2"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.securityFloor").value(9))
                .andExpect(jsonPath("$.securityProfileVersion").value(1));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1));
    }
}
