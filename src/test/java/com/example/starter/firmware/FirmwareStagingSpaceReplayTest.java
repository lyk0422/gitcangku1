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
 * 暂存空间：取消发布释放全部未终结暂存、幂等重放不漂移、旧模式兼容、重复回执不重复扣量。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareStagingSpaceReplayTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM staging_reservation");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private void register(String requestId, String deviceId, String model, long total, long image)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":5,
                "totalSpaceBytes":%d,"currentImageBytes":%d}
                """.formatted(requestId, deviceId, model, total, image)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, long packageBytes, long imageBytes)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "packageBytes":%d,"imageBytes":%d}
                """.formatted(requestId, model, packageBytes, imageBytes)))
                .andExpect(status().isOk())
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.releaseId");
        return id.longValue();
    }

    private String pull(String requestId, String deviceId) throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/pull").contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void 取消发布_全部未终结暂存一并释放_档案版本加一() throws Exception {
        register("r1", "d1", "m1", 110, 40);
        register("r2", "d2", "m1", 200, 60);
        long releaseId = createRelease("r3", "m1", 20, 50);
        pull("r4", "d1");
        pull("r5", "d2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staging_reservation", Long.class)).isEqualTo(2);

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r6\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staging_reservation", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE status = 'CANCELLED'", Long.class)).isEqualTo(2);
        // 旧镜像不变，预留释放，档案版本各加一，余量恢复
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentImageBytes").value(40))
                .andExpect(jsonPath("$.reservedBytes").value(0))
                .andExpect(jsonPath("$.availableBytes").value(70))
                .andExpect(jsonPath("$.spaceVersion").value(2));
        mockMvc.perform(get("/api/devices/d2"))
                .andExpect(jsonPath("$.currentImageBytes").value(60))
                .andExpect(jsonPath("$.availableBytes").value(140))
                .andExpect(jsonPath("$.spaceVersion").value(2));
    }

    @Test
    void 幂等_维护重放版本只加一次_异参409_拉取重放不漂移_失败不占键() throws Exception {
        register("r1", "d1", "m1", 100, 40);
        createRelease("r2", "m1", 20, 50);

        // 422 失败不占键：同 requestId 维护扩容后重试成功
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"pull-x\"}"))
                .andExpect(status().isUnprocessableEntity());

        String maintainBody = """
                {"requestId":"m1","operator":"ops","totalSpaceBytes":110,"currentImageBytes":40,
                "expectedDeviceVersion":1}
                """;
        String first = mockMvc.perform(post("/api/devices/d1/space").contentType("application/json")
                        .content(maintainBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaceVersion").value(2))
                .andReturn().getResponse().getContentAsString();
        // 同键同参重放：完整响应一致，版本不再加
        String replay = mockMvc.perform(post("/api/devices/d1/space").contentType("application/json")
                        .content(maintainBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        mockMvc.perform(get("/api/devices/d1")).andExpect(jsonPath("$.spaceVersion").value(2));
        // 同键异参：409
        mockMvc.perform(post("/api/devices/d1/space").contentType("application/json").content("""
                        {"requestId":"m1","operator":"ops","totalSpaceBytes":120,"currentImageBytes":40,
                        "expectedDeviceVersion":1}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键的 requestId 现在可领取成功
        String pullBody = pull("pull-x", "d1");
        assertThat(pullBody).contains("\"peakBytes\":110").contains("\"currentAvailableBytes\":0");

        // 拉取重放不漂移：扩容后重放仍返回首次响应（当时余量 0）
        mockMvc.perform(post("/api/devices/d1/space").contentType("application/json").content("""
                        {"requestId":"m2","operator":"ops","totalSpaceBytes":200,"currentImageBytes":40,
                        "expectedDeviceVersion":2}
                        """))
                .andExpect(status().isOk());
        String pullReplay = pull("pull-x", "d1");
        assertThat(pullReplay).isEqualTo(pullBody);
    }

    @Test
    void 旧模式_设备或发布无容量字段_保持原行为不做核验() throws Exception {
        // 旧设备 + 旧发布
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r1","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":5}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSpaceBytes").doesNotExist())
                .andExpect(jsonPath("$.availableBytes").doesNotExist());
        MvcResult release = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageBytes").doesNotExist())
                .andReturn();
        Number releaseId = com.jayway.jsonpath.JsonPath.read(release.getResponse().getContentAsString(),
                "$.releaseId");

        String pullBody = pull("r3", "d1");
        assertThat(pullBody).contains("\"peakBytes\":null").contains("\"currentAvailableBytes\":null");
        Number taskId = com.jayway.jsonpath.JsonPath.read(pullBody, "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staging_reservation", Long.class)).isZero();

        // 有容量设备 + 无容量发布：不核验、无预留
        register("r5", "d2", "m2", 10, 5);
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r6","model":"m2","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                        """))
                .andExpect(status().isOk());
        String mixedPull = pull("r7", "d2");
        assertThat(mixedPull).contains("\"peakBytes\":null");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staging_reservation", Long.class)).isZero();
    }

    @Test
    void 重复回执_不重复扣量_空间状态不二次变更() throws Exception {
        register("r1", "d1", "m1", 110, 40);
        long releaseId = createRelease("r2", "m1", 20, 50);
        String pullBody = pull("r3", "d1");
        Number taskId = com.jayway.jsonpath.JsonPath.read(pullBody, "$.task.taskId");

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.spaceVersion").value(2))
                .andExpect(jsonPath("$.availableBytes").value(60));

        // 同结果重复回执：成功但不重复计数、不二次变更空间
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.spaceVersion").value(2))
                .andExpect(jsonPath("$.currentImageBytes").value(50))
                .andExpect(jsonPath("$.availableBytes").value(60));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1));
    }
}
