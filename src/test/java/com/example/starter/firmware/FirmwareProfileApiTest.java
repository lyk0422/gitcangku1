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
 * 硬件画像兼容门禁 API 测试：主流程、失败分支、幂等与画像版本冻结（H2 内存库，MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareProfileApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM profile_task");
        jdbc.update("DELETE FROM profile_exclusion");
        jdbc.update("DELETE FROM profile_launch");
        jdbc.update("DELETE FROM device_profile");
        jdbc.update("DELETE FROM firmware_version");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private void registerDevice(String requestId, String deviceId) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                """.formatted(requestId, deviceId))).andExpect(status().isOk());
    }

    private void upsertProfile(String requestId, String deviceId, String body) throws Exception {
        mockMvc.perform(put("/api/hw/devices/" + deviceId + "/profile")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
    }

    private String createVersion(String requestId, String name, String models, int minMemoryMb,
                                 String requiredTags) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/hw/versions")
                        .contentType("application/json").content("""
                                {"requestId":"%s","name":"%s","models":%s,"minMemoryMb":%d,"requiredTags":%s}
                                """.formatted(requestId, name, models, minMemoryMb, requiredTags)))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private long versionIdOf(String body) {
        Number id = com.jayway.jsonpath.JsonPath.read(body, "$.versionId");
        return id.longValue();
    }

    @Test
    void 合成验收_型号内存达标但无BLE_不建任务也不算投放失败() throws Exception {
        registerDevice("r1", "D");
        upsertProfile("r2", "D", """
                {"requestId":"r2","model":"M","memoryMb":4096,"tags":[]}
                """);
        long versionId = versionIdOf(createVersion("r3", "V", "[\"M\"]", 4096, "[\"BLE\"]"));

        mockMvc.perform(post("/api/hw/launches").contentType("application/json").content("""
                        {"requestId":"r4","versionId":%d}
                        """.formatted(versionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionName").value("V"))
                .andExpect(jsonPath("$.tasks.length()").value(0))
                .andExpect(jsonPath("$.exclusions.length()").value(1))
                .andExpect(jsonPath("$.exclusions[0].deviceId").value("D"))
                .andExpect(jsonPath("$.exclusions[0].profileVersion").value(1))
                .andExpect(jsonPath("$.exclusions[0].reason").value("缺少必需标签: BLE"))
                .andExpect(jsonPath("$.stats.taskCount").value(0))
                .andExpect(jsonPath("$.stats.failedCount").value(0))
                .andExpect(jsonPath("$.stats.failureRatePercent").value(0))
                .andExpect(jsonPath("$.stats.excludedCount").value(1));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profile_task", Long.class)).isZero();
        // 设备端只看自己的任务：无任务
        mockMvc.perform(get("/api/hw/devices/D/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(0));
    }

    @Test
    void 主流程_兼容设备建任务_查询携带冻结画像版本_失败率只统计任务() throws Exception {
        registerDevice("r1", "d1");
        registerDevice("r2", "d2");
        registerDevice("r3", "d3");
        upsertProfile("r4", "d1", """
                {"requestId":"r4","model":"M","memoryMb":4096,"tags":["BLE"]}
                """);
        upsertProfile("r5", "d2", """
                {"requestId":"r5","model":"M","memoryMb":8192,"tags":["BLE","WIFI"]}
                """);
        upsertProfile("r6", "d3", """
                {"requestId":"r6","model":"M","memoryMb":2048,"tags":["BLE"]}
                """);
        long versionId = versionIdOf(createVersion("r7", "V", "[\"M\"]", 4096, "[\"BLE\"]"));

        MvcResult launch = mockMvc.perform(post("/api/hw/launches")
                        .contentType("application/json").content("""
                                {"requestId":"r8","versionId":%d}
                                """.formatted(versionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(2))
                .andExpect(jsonPath("$.tasks[0].profileVersion").value(1))
                .andExpect(jsonPath("$.tasks[0].status").value("PENDING"))
                .andExpect(jsonPath("$.exclusions.length()").value(1))
                .andExpect(jsonPath("$.exclusions[0].deviceId").value("d3"))
                .andExpect(jsonPath("$.exclusions[0].reason").value("内存不足: 2048MB < 4096MB"))
                .andReturn();
        long launchId = com.jayway.jsonpath.JsonPath
                .<Number>read(launch.getResponse().getContentAsString(), "$.launchId").longValue();
        long taskId = com.jayway.jsonpath.JsonPath
                .<Number>read(launch.getResponse().getContentAsString(), "$.tasks[0].taskId").longValue();

        // 设备端只看自己的任务
        mockMvc.perform(get("/api/hw/devices/d1/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].deviceId").value("d1"));
        mockMvc.perform(get("/api/hw/devices/d3/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(0));

        // 一台失败一台成功：失败率 50%，排除设备不计入
        mockMvc.perform(post("/api/hw/tasks/" + taskId + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r9\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        mockMvc.perform(get("/api/hw/launches/" + launchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.taskCount").value(2))
                .andExpect(jsonPath("$.stats.failedCount").value(1))
                .andExpect(jsonPath("$.stats.failureRatePercent").value(50))
                .andExpect(jsonPath("$.stats.excludedCount").value(1));
    }

    @Test
    void 缺画像或缺字段_按不兼容处理并给出原因() throws Exception {
        registerDevice("r1", "no-profile");
        registerDevice("r2", "no-model");
        registerDevice("r3", "no-memory");
        registerDevice("r4", "no-tags");
        registerDevice("r5", "wrong-model");
        upsertProfile("r6", "no-model", """
                {"requestId":"r6","memoryMb":4096,"tags":["BLE"]}
                """);
        upsertProfile("r7", "no-memory", """
                {"requestId":"r7","model":"M","tags":["BLE"]}
                """);
        upsertProfile("r8", "no-tags", """
                {"requestId":"r8","model":"M","memoryMb":4096}
                """);
        upsertProfile("r9", "wrong-model", """
                {"requestId":"r9","model":"X","memoryMb":4096,"tags":["BLE"]}
                """);
        long versionId = versionIdOf(createVersion("r10", "V", "[\"M\"]", 4096, "[\"BLE\"]"));

        mockMvc.perform(post("/api/hw/launches").contentType("application/json").content("""
                        {"requestId":"r11","versionId":%d}
                        """.formatted(versionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(0))
                .andExpect(jsonPath("$.exclusions.length()").value(5))
                .andExpect(jsonPath("$.exclusions[0].deviceId").value("no-memory"))
                .andExpect(jsonPath("$.exclusions[0].reason").value("画像缺少内存字段"))
                .andExpect(jsonPath("$.exclusions[1].deviceId").value("no-model"))
                .andExpect(jsonPath("$.exclusions[1].reason").value("画像缺少型号字段"))
                .andExpect(jsonPath("$.exclusions[2].deviceId").value("no-profile"))
                .andExpect(jsonPath("$.exclusions[2].reason").value("设备无画像"))
                .andExpect(jsonPath("$.exclusions[2].profileVersion").doesNotExist())
                .andExpect(jsonPath("$.exclusions[3].deviceId").value("no-tags"))
                .andExpect(jsonPath("$.exclusions[3].reason").value("画像缺少能力标签字段"))
                .andExpect(jsonPath("$.exclusions[4].deviceId").value("wrong-model"))
                .andExpect(jsonPath("$.exclusions[4].reason").value("型号不匹配: X"));
    }

    @Test
    void 幂等_同requestId重放首次目标清单_换版本409() throws Exception {
        registerDevice("r1", "d1");
        upsertProfile("r2", "d1", """
                {"requestId":"r2","model":"M","memoryMb":4096,"tags":["BLE"]}
                """);
        long v1 = versionIdOf(createVersion("r3", "V1", "[\"M\"]", 4096, "[\"BLE\"]"));
        long v2 = versionIdOf(createVersion("r4", "V2", "[\"M\"]", 2048, "[]"));

        MvcResult first = mockMvc.perform(post("/api/hw/launches")
                        .contentType("application/json").content("""
                                {"requestId":"rid-launch","versionId":%d}
                                """.formatted(v1)))
                .andExpect(status().isOk())
                .andReturn();
        String firstBody = first.getResponse().getContentAsString();

        // 画像更新后重放：仍返回首次目标清单，不重新筛选
        upsertProfile("r5", "d1", """
                {"requestId":"r5","model":"M","memoryMb":4096,"tags":[]}
                """);
        MvcResult replay = mockMvc.perform(post("/api/hw/launches")
                        .contentType("application/json").content("""
                                {"requestId":"rid-launch","versionId":%d}
                                """.formatted(v1)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(firstBody);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profile_launch", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profile_task", Long.class)).isEqualTo(1);

        // 同 requestId 换版本：409
        mockMvc.perform(post("/api/hw/launches").contentType("application/json").content("""
                        {"requestId":"rid-launch","versionId":%d}
                        """.formatted(v2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 画像更新只影响后续新任务_不追改已下发任务() throws Exception {
        registerDevice("r1", "d1");
        upsertProfile("r2", "d1", """
                {"requestId":"r2","model":"M","memoryMb":4096,"tags":["BLE"]}
                """);
        long versionId = versionIdOf(createVersion("r3", "V", "[\"M\"]", 4096, "[\"BLE\"]"));

        MvcResult launch1 = mockMvc.perform(post("/api/hw/launches")
                        .contentType("application/json").content("""
                                {"requestId":"r4","versionId":%d}
                                """.formatted(versionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].profileVersion").value(1))
                .andReturn();
        long launchId1 = com.jayway.jsonpath.JsonPath
                .<Number>read(launch1.getResponse().getContentAsString(), "$.launchId").longValue();

        // 画像更新为不兼容（去掉BLE），版本加一
        mockMvc.perform(put("/api/hw/devices/d1/profile").contentType("application/json").content("""
                        {"requestId":"r5","model":"M","memoryMb":4096,"tags":[]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileVersion").value(2));

        // 已下发任务不被追改：目标、画像版本与统计口径不变
        mockMvc.perform(get("/api/hw/launches/" + launchId1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].profileVersion").value(1))
                .andExpect(jsonPath("$.exclusions.length()").value(0));

        // 新投放按新画像筛选：不兼容，排除记录引用画像版本2
        mockMvc.perform(post("/api/hw/launches").contentType("application/json").content("""
                        {"requestId":"r6","versionId":%d}
                        """.formatted(versionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(0))
                .andExpect(jsonPath("$.exclusions.length()").value(1))
                .andExpect(jsonPath("$.exclusions[0].profileVersion").value(2))
                .andExpect(jsonPath("$.exclusions[0].reason").value("缺少必需标签: BLE"));
    }

    @Test
    void 回执_同结果重放成功_改结果409_任务不存在404() throws Exception {
        registerDevice("r1", "d1");
        upsertProfile("r2", "d1", """
                {"requestId":"r2","model":"M","memoryMb":4096,"tags":["BLE"]}
                """);
        long versionId = versionIdOf(createVersion("r3", "V", "[\"M\"]", 4096, "[\"BLE\"]"));
        MvcResult launch = mockMvc.perform(post("/api/hw/launches")
                        .contentType("application/json").content("""
                                {"requestId":"r4","versionId":%d}
                                """.formatted(versionId)))
                .andReturn();
        long taskId = com.jayway.jsonpath.JsonPath
                .<Number>read(launch.getResponse().getContentAsString(), "$.tasks[0].taskId").longValue();

        mockMvc.perform(post("/api/hw/tasks/" + taskId + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 同结果重复回执成功
        mockMvc.perform(post("/api/hw/tasks/" + taskId + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 改结果 409
        mockMvc.perform(post("/api/hw/tasks/" + taskId + "/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r7\",\"result\":\"FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_RESULT_CONFLICT"));

        // 任务不存在 404
        mockMvc.perform(post("/api/hw/tasks/9999/receipt")
                        .contentType("application/json")
                        .content("{\"requestId\":\"r8\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 输入与状态不合法_校验400_不存在404_重名409() throws Exception {
        // 版本不存在：404
        mockMvc.perform(post("/api/hw/launches").contentType("application/json").content("""
                        {"requestId":"r1","versionId":9999}
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));

        // 型号集合为空：400
        mockMvc.perform(post("/api/hw/versions").contentType("application/json").content("""
                        {"requestId":"r2","name":"V","models":[],"minMemoryMb":0,"requiredTags":[]}
                        """))
                .andExpect(status().isBadRequest());

        // 最低内存为负：400
        mockMvc.perform(post("/api/hw/versions").contentType("application/json").content("""
                        {"requestId":"r3","name":"V","models":["M"],"minMemoryMb":-1,"requiredTags":[]}
                        """))
                .andExpect(status().isBadRequest());

        // 版本重名：409
        createVersion("r4", "V", "[\"M\"]", 4096, "[]");
        mockMvc.perform(post("/api/hw/versions").contentType("application/json").content("""
                        {"requestId":"r5","name":"V","models":["M"],"minMemoryMb":4096,"requiredTags":[]}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_NAME_EXISTS"));

        // 未登记设备上报画像：404
        mockMvc.perform(put("/api/hw/devices/ghost/profile").contentType("application/json").content("""
                        {"requestId":"r6","model":"M","memoryMb":4096,"tags":["BLE"]}
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));

        // 投放单不存在：404
        mockMvc.perform(get("/api/hw/launches/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LAUNCH_NOT_FOUND"));

        // 设备端查询未登记设备：404
        mockMvc.perform(get("/api/hw/devices/ghost/tasks"))
                .andExpect(status().isNotFound());

        // 画像不存在：404
        registerDevice("r7", "d1");
        mockMvc.perform(get("/api/hw/devices/d1/profile"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
    }

    @Test
    void 画像上报_幂等重放不加版本_异参409() throws Exception {
        registerDevice("r1", "d1");
        mockMvc.perform(put("/api/hw/devices/d1/profile").contentType("application/json").content("""
                        {"requestId":"rid-p","model":"M","memoryMb":4096,"tags":["BLE"]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileVersion").value(1));

        // 同 requestId 同参重放：版本不加
        mockMvc.perform(put("/api/hw/devices/d1/profile").contentType("application/json").content("""
                        {"requestId":"rid-p","model":"M","memoryMb":4096,"tags":["BLE"]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileVersion").value(1));

        // 同 requestId 异参：409
        mockMvc.perform(put("/api/hw/devices/d1/profile").contentType("application/json").content("""
                        {"requestId":"rid-p","model":"M","memoryMb":8192,"tags":["BLE"]}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 新 requestId 正常更新：版本加一
        mockMvc.perform(put("/api/hw/devices/d1/profile").contentType("application/json").content("""
                        {"requestId":"rid-p2","model":"M","memoryMb":8192,"tags":["BLE"]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileVersion").value(2))
                .andExpect(jsonPath("$.memoryMb").value(8192));
    }
}
