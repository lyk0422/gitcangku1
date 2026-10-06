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
 * 任务暂存峰值核验测试（H2 内存库，MODE=MySQL）：
 * 空间档案登记、领取 422、维护 409 带 expectedDeviceVersion、终结时空间原子切换/释放、
 * 历史峰值与当前余量分离、幂等重放不漂移、旧模式兼容。
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

    private void registerDevice(String requestId, String deviceId, String model, String version,
                                int bucket, long totalSpace, long currentImage) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d,
                         "totalSpaceBytes":%d,"currentImageBytes":%d}
                        """.formatted(requestId, deviceId, model, version, bucket, totalSpace, currentImage)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaceVersion").value(1));
    }

    private long createRelease(String requestId, String model, String from, String to, int ratio,
                               long packageBytes, long imageBytes) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"%s","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":%d,
                         "packageBytes":%d,"imageBytes":%d}
                        """.formatted(requestId, model, from, to, ratio, packageBytes, imageBytes)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageBytes").value(packageBytes))
                .andExpect(jsonPath("$.imageBytes").value(imageBytes))
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private long pull(String deviceId, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json")
                        .content("{\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    @Test
    void 合成场景_容量100镜像40包20镜像50_领取422_扩容110后成功_已用50余量60() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1, 100, 40);
        long releaseId = createRelease("r2", "m1", "1.0.0", "2.0.0", 100, 20, 50);

        // 峰值 40+20+50=110 > 100：422，不生成任务或预留
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SPACE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.availableBytes").value(60))
                .andExpect(jsonPath("$.spaceVersion").value(1));

        // 维护容量到 110：档案版本加一
        mockMvc.perform(post("/api/devices/d1/space").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"totalSpaceBytes\":110,\"expectedDeviceVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSpaceBytes").value(110))
                .andExpect(jsonPath("$.spaceVersion").value(2))
                .andExpect(jsonPath("$.availableBytes").value(70));

        // 领取成功：预留 20+50=70，当时峰值 110
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.reservedBytes").value(70))
                .andExpect(jsonPath("$.task.peakBytes").value(110))
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.activeReservedBytes").value(70))
                .andExpect(jsonPath("$.availableBytes").value(0));

        // SUCCESS：原子删除旧镜像、压缩包及预留，当前镜像改为 50，档案版本加一
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.currentImageBytes").value(50))
                .andExpect(jsonPath("$.activeReservedBytes").value(0))
                .andExpect(jsonPath("$.availableBytes").value(60))
                .andExpect(jsonPath("$.spaceVersion").value(3));

        // 历史查询：任务保留当时峰值 110 不漂移，设备余量为当前值 60
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.tasks[0].peakBytes").value(110));
    }

    @Test
    void 登记_容量字段必须成对且镜像不超总量() throws Exception {
        // 只填总空间
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r1","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                         "totalSpaceBytes":100}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SPACE_FIELDS_INCOMPLETE"));

        // 镜像超过总量
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r2","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                         "totalSpaceBytes":100,"currentImageBytes":101}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_EXCEEDS_TOTAL"));

        // 非正总空间
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r3","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                         "totalSpaceBytes":0,"currentImageBytes":0}
                        """))
                .andExpect(status().isBadRequest());

        // 失败不占键：同 requestId 修正参数后成功
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r2","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                         "totalSpaceBytes":100,"currentImageBytes":40}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaceVersion").value(1))
                .andExpect(jsonPath("$.availableBytes").value(60));
    }

    @Test
    void 发布_容量字段必须成对_正整数校验() throws Exception {
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "packageBytes":20}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SPACE_FIELDS_INCOMPLETE"));

        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "packageBytes":0,"imageBytes":50}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 失败回执_只释放预留_旧镜像不变_档案版本加一() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1, 110, 40);
        createRelease("r2", "m1", "1.0.0", "2.0.0", 100, 20, 50);
        long taskId = pull("d1", "r3");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.availableBytes").value(0));

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        // 旧镜像不变、预留释放、档案版本加一；重复回执不再扣量也不再动空间
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"))
                .andExpect(jsonPath("$.currentImageBytes").value(40))
                .andExpect(jsonPath("$.activeReservedBytes").value(0))
                .andExpect(jsonPath("$.availableBytes").value(70))
                .andExpect(jsonPath("$.spaceVersion").value(2));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.spaceVersion").value(2))
                .andExpect(jsonPath("$.availableBytes").value(70));
    }

    @Test
    void 取消发布_未终结暂存一并释放_档案版本加一() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1, 110, 40);
        registerDevice("r2", "d2", "m1", "1.0.0", 2, 200, 40);
        long releaseId = createRelease("r3", "m1", "1.0.0", "2.0.0", 100, 20, 50);
        pull("d1", "r4");
        pull("d2", "r5");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.availableBytes").value(0));

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r6\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.tasks[1].status").value("CANCELLED"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentImageBytes").value(40))
                .andExpect(jsonPath("$.availableBytes").value(70))
                .andExpect(jsonPath("$.spaceVersion").value(2));
        mockMvc.perform(get("/api/devices/d2"))
                .andExpect(jsonPath("$.availableBytes").value(160))
                .andExpect(jsonPath("$.spaceVersion").value(2));
    }

    @Test
    void 维护_调小低于占用409带期望版本_版本不匹配409_旧设备409() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1, 110, 40);
        createRelease("r2", "m1", "1.0.0", "2.0.0", 100, 20, 50);
        pull("d1", "r3");

        // 活跃预留 70，占用 40+70=110：调小到 105 拒绝并携带当前档案版本
        mockMvc.perform(post("/api/devices/d1/space").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"totalSpaceBytes\":105,\"expectedDeviceVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SPACE_BELOW_IN_USE"))
                .andExpect(jsonPath("$.expectedDeviceVersion").value(1));

        // 版本不匹配：409 携带当前档案版本
        mockMvc.perform(post("/api/devices/d1/space").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"totalSpaceBytes\":200,\"expectedDeviceVersion\":9}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.expectedDeviceVersion").value(1));

        // 调小到恰等于占用：允许
        mockMvc.perform(post("/api/devices/d1/space").contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"totalSpaceBytes\":110,\"expectedDeviceVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaceVersion").value(2))
                .andExpect(jsonPath("$.availableBytes").value(0));

        // 旧模式设备无空间档案：维护 409
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r7","deviceId":"legacy","model":"m9","currentVersion":"1.0.0","bucketNo":1}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaceVersion").doesNotExist());
        mockMvc.perform(post("/api/devices/legacy/space").contentType("application/json")
                        .content("{\"requestId\":\"r8\",\"totalSpaceBytes\":100,\"expectedDeviceVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_SPACE_UNTRACKED"));

        // 设备不存在：404
        mockMvc.perform(post("/api/devices/ghost/space").contentType("application/json")
                        .content("{\"requestId\":\"r9\",\"totalSpaceBytes\":100,\"expectedDeviceVersion\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 幂等_维护重放版本只加一次_异参409_拉取重放不漂移() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1, 100, 40);
        createRelease("r2", "m1", "1.0.0", "2.0.0", 100, 20, 50);

        // 维护重放：同键同参返回首次完整响应，版本只加一次
        String body = "{\"requestId\":\"r3\",\"totalSpaceBytes\":110,\"expectedDeviceVersion\":1}";
        MvcResult first = mockMvc.perform(post("/api/devices/d1/space")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spaceVersion").value(2))
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/devices/d1/space")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.spaceVersion").value(2));

        // 同键异参：409
        mockMvc.perform(post("/api/devices/d1/space").contentType("application/json")
                        .content("{\"requestId\":\"r3\",\"totalSpaceBytes\":120,\"expectedDeviceVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 拉取成功后设备空间变化，重放仍返回首次响应（含当时峰值），不漂移
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.peakBytes").value(110))
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(taskId))
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.peakBytes").value(110));
    }

    @Test
    void 旧模式_无容量字段任一侧保留原行为() throws Exception {
        // 旧设备 + 新发布（带容量）：不核验空间，任务无预留与峰值
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r1","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                        """))
                .andExpect(status().isOk());
        long releaseId = createRelease("r2", "m1", "1.0.0", "2.0.0", 100, 20, 50);
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.reservedBytes").doesNotExist())
                .andExpect(jsonPath("$.task.peakBytes").doesNotExist())
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.spaceVersion").doesNotExist());

        // 新设备 + 旧发布（无容量）：不核验空间
        registerDevice("r5", "d2", "m2", "1.0.0", 1, 100, 40);
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r6","model":"m2","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageBytes").doesNotExist());
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.peakBytes").doesNotExist());
        mockMvc.perform(get("/api/devices/d2"))
                .andExpect(jsonPath("$.availableBytes").value(60))
                .andExpect(jsonPath("$.spaceVersion").value(1));
    }
}
