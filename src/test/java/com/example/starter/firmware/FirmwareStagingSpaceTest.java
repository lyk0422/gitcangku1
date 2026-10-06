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
 * 任务暂存峰值核验 API 测试（H2 内存库，MODE=MySQL）：
 * 合成场景（容量100/旧镜像40/包20/新镜像50）、登记与维护校验、FAILED 终结释放。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareStagingSpaceTest {

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

    private void register(String requestId, String deviceId, long total, long image) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":5,
                "totalSpaceBytes":%d,"currentImageBytes":%d}
                """.formatted(requestId, deviceId, total, image)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, long packageBytes, long imageBytes) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "packageBytes":%d,"imageBytes":%d}
                """.formatted(requestId, packageBytes, imageBytes)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageBytes").value(packageBytes))
                .andExpect(jsonPath("$.imageBytes").value(imageBytes))
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.releaseId");
        return id.longValue();
    }

    private void maintain(String requestId, String deviceId, long total, long image, int expectedVersion)
            throws Exception {
        mockMvc.perform(post("/api/devices/" + deviceId + "/space").contentType("application/json").content("""
                {"requestId":"%s","operator":"ops","totalSpaceBytes":%d,"currentImageBytes":%d,
                "expectedDeviceVersion":%d}
                """.formatted(requestId, total, image, expectedVersion)))
                .andExpect(status().isOk());
    }

    @Test
    void 合成场景_容量不足422_维护扩容后领取_成功已用50余量60() throws Exception {
        register("r1", "d1", 100, 40);
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.spaceVersion").value(1))
                .andExpect(jsonPath("$.availableBytes").value(60));
        createRelease("r2", 20, 50);

        // 40+20+50=110 > 100：422，不生成任务或预留
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STAGING_SPACE"));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM staging_reservation", Long.class)).isZero();

        // 容量改 110 后可领取，峰值 110，当前余量 0
        maintain("r4", "d1", 110, 40, 1);
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.peakBytes").value(110))
                .andExpect(jsonPath("$.task.currentAvailableBytes").value(0))
                .andReturn();
        Number taskId = com.jayway.jsonpath.JsonPath.read(pull.getResponse().getContentAsString(),
                "$.task.taskId");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.reservedBytes").value(70))
                .andExpect(jsonPath("$.availableBytes").value(0))
                .andExpect(jsonPath("$.spaceVersion").value(2));

        // SUCCESS：原子删除旧镜像、压缩包及预留，当前镜像改为 50，已用 50、余量 60
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.currentImageBytes").value(50))
                .andExpect(jsonPath("$.reservedBytes").value(0))
                .andExpect(jsonPath("$.availableBytes").value(60))
                .andExpect(jsonPath("$.spaceVersion").value(3));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM staging_reservation", Long.class)).isZero();
    }

    @Test
    void 登记与发布_容量字段校验() throws Exception {
        // 只传一个容量字段：400
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r1","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":5,
                        "totalSpaceBytes":100}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SPACE_PROFILE_INCOMPLETE"));
        // 当前镜像大于总空间：400
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r2","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":5,
                        "totalSpaceBytes":40,"currentImageBytes":50}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_EXCEEDS_TOTAL"));
        // 非正数：400
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r3","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":5,
                        "totalSpaceBytes":0,"currentImageBytes":0}
                        """))
                .andExpect(status().isBadRequest());
        // 发布只传一个容量字段：400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r4","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                        "packageBytes":20}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STAGING_PROFILE_INCOMPLETE"));
    }

    @Test
    void 维护_版本冲突与容量低于活跃预留_409带expectedDeviceVersion() throws Exception {
        register("r1", "d1", 100, 40);
        createRelease("r2", 20, 50);
        maintain("r3", "d1", 110, 40, 1);
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r4\"}"))
                .andExpect(status().isOk());

        // 期望版本不符：409，响应携带当前档案版本 2
        mockMvc.perform(post("/api/devices/d1/space").contentType("application/json").content("""
                        {"requestId":"r5","operator":"ops","totalSpaceBytes":200,"currentImageBytes":40,
                        "expectedDeviceVersion":1}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.expectedDeviceVersion").value(2));

        // 调小容量低于当前镜像加活跃预留（40+70=110）：409，携带当前档案版本
        mockMvc.perform(post("/api/devices/d1/space").contentType("application/json").content("""
                        {"requestId":"r6","operator":"ops","totalSpaceBytes":100,"currentImageBytes":40,
                        "expectedDeviceVersion":2}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPACITY_BELOW_RESERVATION"))
                .andExpect(jsonPath("$.expectedDeviceVersion").value(2));

        // 镜像大于总空间：400
        mockMvc.perform(post("/api/devices/d1/space").contentType("application/json").content("""
                        {"requestId":"r7","operator":"ops","totalSpaceBytes":110,"currentImageBytes":120,
                        "expectedDeviceVersion":2}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_EXCEEDS_TOTAL"));

        // 维护成功：版本加一，余量按新容量计算
        maintain("r8", "d1", 200, 40, 2);
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.spaceVersion").value(3))
                .andExpect(jsonPath("$.reservedBytes").value(70))
                .andExpect(jsonPath("$.availableBytes").value(90));

        // 设备不存在：404
        mockMvc.perform(post("/api/devices/ghost/space").contentType("application/json").content("""
                        {"requestId":"r9","operator":"ops","totalSpaceBytes":100,"currentImageBytes":40,
                        "expectedDeviceVersion":1}
                        """))
                .andExpect(status().isNotFound());
    }

    @Test
    void FAILED_只释放包与新镜像预留_旧镜像与版本不变() throws Exception {
        register("r1", "d1", 110, 40);
        long releaseId = createRelease("r2", 20, 50);
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Number taskId = com.jayway.jsonpath.JsonPath.read(pull.getResponse().getContentAsString(),
                "$.task.taskId");

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        // 预留释放、旧镜像不变、档案版本加一、余量恢复
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"))
                .andExpect(jsonPath("$.currentImageBytes").value(40))
                .andExpect(jsonPath("$.reservedBytes").value(0))
                .andExpect(jsonPath("$.availableBytes").value(70))
                .andExpect(jsonPath("$.spaceVersion").value(2));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM staging_reservation", Long.class)).isZero();

        // 历史查询：当时峰值 110 不漂移，当前余量 70
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].peakBytes").value(110))
                .andExpect(jsonPath("$.tasks[0].currentAvailableBytes").value(70));
    }
}
