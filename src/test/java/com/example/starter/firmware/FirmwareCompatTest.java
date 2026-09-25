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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固件硬件兼容矩阵 API 测试（H2 内存库，MODE=MySQL）：
 * 矩阵版本管理、拉取拦截、失败率隔离、矩阵缩窄语义、发布预检与按型号统计。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareCompatTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM incompatible_record");
        jdbc.update("DELETE FROM firmware_compat");
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

    private void registerDevice(String requestId, String deviceId, String model,
                                String hardwareModel, int bucket) throws Exception {
        String hardwareField = hardwareModel == null ? "" : "\"hardwareModel\":\"%s\",".formatted(hardwareModel);
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s",%s"currentVersion":"1.0.0","bucketNo":%d}
                """.formatted(requestId, deviceId, model, hardwareField, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, String toVersion, int ratio) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"%s","ratio":%d}
                """.formatted(requestId, model, toVersion, ratio)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private void configureCompat(String requestId, String firmwareVersion, int expectedVersion,
                                 String allowedModelsJson) throws Exception {
        mockMvc.perform(put("/api/firmware/" + firmwareVersion + "/compat")
                        .contentType("application/json").content("""
                        {"requestId":"%s","expectedVersion":%d,"allowedModels":%s}
                        """.formatted(requestId, expectedVersion, allowedModelsJson)))
                .andExpect(status().isOk());
    }

    @Test
    void 矩阵配置_版本递增_换序同参_查询矩阵版本() throws Exception {
        registerDevice("r-d1", "d1", "m1", "h1", 1);
        registerDevice("r-d2", "d2", "m1", "h2", 2);

        // 未配置：版本 0，空集合（兼容全部）
        mockMvc.perform(get("/api/firmware/2.0.0/compat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matrixVersion").value(0))
                .andExpect(jsonPath("$.allowedModels.length()").value(0));

        // 首次配置：expectedVersion=0 -> 版本 1
        mockMvc.perform(put("/api/firmware/2.0.0/compat").contentType("application/json")
                        .content("{\"requestId\":\"r-c1\",\"expectedVersion\":0,\"allowedModels\":[\"h2\",\"h1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matrixVersion").value(1))
                .andExpect(jsonPath("$.allowedModels[0]").value("h1"))
                .andExpect(jsonPath("$.allowedModels[1]").value("h2"));

        // 同 requestId 换序重放：视为同参，返回首个响应，版本不再增加
        mockMvc.perform(put("/api/firmware/2.0.0/compat").contentType("application/json")
                        .content("{\"requestId\":\"r-c1\",\"expectedVersion\":0,\"allowedModels\":[\"h1\",\"h2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matrixVersion").value(1));

        // 修改：expectedVersion=1 -> 版本 2
        mockMvc.perform(put("/api/firmware/2.0.0/compat").contentType("application/json")
                        .content("{\"requestId\":\"r-c2\",\"expectedVersion\":1,\"allowedModels\":[\"h1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matrixVersion").value(2))
                .andExpect(jsonPath("$.allowedModels.length()").value(1));

        // 查询矩阵版本
        mockMvc.perform(get("/api/firmware/2.0.0/compat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matrixVersion").value(2))
                .andExpect(jsonPath("$.allowedModels[0]").value("h1"));

        // 版本冲突：409
        mockMvc.perform(put("/api/firmware/2.0.0/compat").contentType("application/json")
                        .content("{\"requestId\":\"r-c3\",\"expectedVersion\":1,\"allowedModels\":[\"h1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 清空集合：兼容全部
        mockMvc.perform(put("/api/firmware/2.0.0/compat").contentType("application/json")
                        .content("{\"requestId\":\"r-c4\",\"expectedVersion\":2,\"allowedModels\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matrixVersion").value(3))
                .andExpect(jsonPath("$.allowedModels.length()").value(0));
    }

    @Test
    void 矩阵配置_型号重复或未知422_失败不占键() throws Exception {
        registerDevice("r-d1", "d1", "m1", "h1", 1);

        // 型号重复：422
        mockMvc.perform(put("/api/firmware/2.0.0/compat").contentType("application/json")
                        .content("{\"requestId\":\"r-c1\",\"expectedVersion\":0,\"allowedModels\":[\"h1\",\"h1\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MODEL"));

        // 未知型号：422
        mockMvc.perform(put("/api/firmware/2.0.0/compat").contentType("application/json")
                        .content("{\"requestId\":\"r-c2\",\"expectedVersion\":0,\"allowedModels\":[\"ghost\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNKNOWN_MODEL"));

        // 失败不占键：同 requestId 修正参数后成功
        mockMvc.perform(put("/api/firmware/2.0.0/compat").contentType("application/json")
                        .content("{\"requestId\":\"r-c2\",\"expectedVersion\":0,\"allowedModels\":[\"h1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matrixVersion").value(1));
    }

    @Test
    void 拉取拦截_不创建任务不计样本不改设备状态_记录可查() throws Exception {
        registerDevice("r-d1", "d1", "m1", "h1", 1);
        registerDevice("r-d2", "d2", "m1", "h2", 2);
        configureCompat("r-c1", "2.0.0", 0, "[\"h1\"]");
        long releaseId = createRelease("r-r1", "m1", "2.0.0", 100);

        // 兼容设备：正常创建任务，任务固化矩阵版本 1
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("TASK"))
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.compatVersion").value(1))
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");

        // 不兼容设备：INCOMPATIBLE，不创建任务
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("INCOMPATIBLE"))
                .andExpect(jsonPath("$.task").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);

        // 不计入失败率样本
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));

        // 不改变设备状态
        mockMvc.perform(get("/api/devices/d2"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 不兼容记录可查，重复拉取不重复记录
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2b\"}"))
                .andExpect(jsonPath("$.result").value("INCOMPATIBLE"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/incompatible"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].deviceId").value("d2"))
                .andExpect(jsonPath("$.records[0].hardwareModel").value("h2"))
                .andExpect(jsonPath("$.records[0].firmwareVersion").value("2.0.0"))
                .andExpect(jsonPath("$.records[0].matrixVersion").value(1))
                .andExpect(jsonPath("$.records[0].blockedAtUtc").isString());

        // 已下发任务不受矩阵影响：回执成功并更新设备版本
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
    }

    @Test
    void 矩阵缩窄_只影响后续拉取_已下发任务可回执() throws Exception {
        registerDevice("r-d1", "d1", "m1", "h1", 1);
        registerDevice("r-d2", "d2", "m1", "h1", 2);
        registerDevice("r-d3", "d3", "m1", "h2", 3);
        configureCompat("r-c1", "2.0.0", 0, "[\"h1\",\"h2\"]");
        long releaseId = createRelease("r-r1", "m1", "2.0.0", 100);

        // 缩窄前：h1 设备拉取成功，任务固化矩阵版本 1
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1\"}"))
                .andExpect(jsonPath("$.result").value("TASK"))
                .andExpect(jsonPath("$.task.compatVersion").value(1))
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");

        // 缩窄矩阵：仅允许 h2
        configureCompat("r-c2", "2.0.0", 1, "[\"h2\"]");

        // 缩窄后：h1 新设备被拦截，h2 设备可拉取且固化矩阵版本 2
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2\"}"))
                .andExpect(jsonPath("$.result").value("INCOMPATIBLE"));
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3\"}"))
                .andExpect(jsonPath("$.result").value("TASK"))
                .andExpect(jsonPath("$.task.compatVersion").value(2));

        // 缩窄前已下发的任务仍可回执，成功更新设备版本
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 按型号投放统计：h1 成功 1，h2 待回执 1，h1 被拦截 1
        mockMvc.perform(get("/api/releases/" + releaseId + "/model-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.length()").value(2))
                .andExpect(jsonPath("$.stats[0].hardwareModel").value("h1"))
                .andExpect(jsonPath("$.stats[0].success").value(1))
                .andExpect(jsonPath("$.stats[0].pending").value(0))
                .andExpect(jsonPath("$.stats[0].incompatible").value(1))
                .andExpect(jsonPath("$.stats[1].hardwareModel").value("h2"))
                .andExpect(jsonPath("$.stats[1].pending").value(1))
                .andExpect(jsonPath("$.stats[1].incompatible").value(0));
    }

    @Test
    void 发布预检_全部候选不兼容422并按型号汇总_部分不兼容不阻断() throws Exception {
        registerDevice("r-d1", "d1", "m1", "h1", 1);
        registerDevice("r-d2", "d2", "m1", "h2", 2);
        registerDevice("r-d3", "d3", "m1", "h2", 3);
        // 矩阵仅允许 h1：候选 d1(h1) 兼容、d2/d3(h2) 不兼容，部分不兼容不阻断
        configureCompat("r-c1", "2.0.0", 0, "[\"h1\"]");
        long releaseId = createRelease("r-r1", "m1", "2.0.0", 100);
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-x1\"}"))
                .andExpect(status().isOk());

        // 矩阵仅允许 h4（无候选设备使用该型号）：全部候选不兼容，422 且 details 按硬件型号汇总
        registerDevice("r-d4", "d4", "m9", "h4", 1);
        configureCompat("r-c2", "3.0.0", 0, "[\"h4\"]");
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-r2","model":"m1","fromVersion":"1.0.0","toVersion":"3.0.0","ratio":100}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ALL_CANDIDATES_INCOMPATIBLE"))
                .andExpect(jsonPath("$.details.h1").value(1))
                .andExpect(jsonPath("$.details.h2").value(2));

        // 无候选设备的型号：不阻断
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-r3","model":"m-empty","fromVersion":"1.0.0","toVersion":"3.0.0","ratio":100}
                """))
                .andExpect(status().isOk());
    }

    @Test
    void 不兼容拉取不触发失败率暂停() throws Exception {
        // sampleFloor=2, threshold=50：若不兼容拉取被计入样本将误触暂停
        registerDevice("r-d1", "d1", "m1", "h1", 1);
        registerDevice("r-d2", "d2", "m1", "h2", 2);
        registerDevice("r-d3", "d3", "m1", "h2", 3);
        configureCompat("r-c1", "2.0.0", 0, "[\"h1\"]");
        MvcResult release = mockMvc.perform(post("/api/releases").contentType("application/json")
                        .content("""
                        {"requestId":"r-r1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                        "sampleFloor":2,"failureThresholdPercent":50}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        long releaseId = idOf(release, "$.releaseId");

        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2\"}"))
                .andExpect(jsonPath("$.result").value("INCOMPATIBLE"));
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3\"}"))
                .andExpect(jsonPath("$.result").value("INCOMPATIBLE"));

        // 两次拦截均未计入样本，发布单保持 ACTIVE
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));
        mockMvc.perform(get("/api/releases/" + releaseId + "/history"))
                .andExpect(jsonPath("$.pauses.length()").value(0));
    }

    @Test
    void 设备登记_缺省硬件型号等于设备型号_不可变() throws Exception {
        registerDevice("r-d1", "d1", "m1", null, 1);
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("m1"))
                .andExpect(jsonPath("$.hardwareModel").value("m1"));

        registerDevice("r-d2", "d2", "m1", "h2", 2);
        mockMvc.perform(get("/api/devices/d2"))
                .andExpect(jsonPath("$.hardwareModel").value("h2"));
    }
}
