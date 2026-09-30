package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
 * 硬件画像兼容门禁集成测试（真实 H2 内存库 MODE=MySQL）：
 * 合成验收、排除原因、画像版本冻结与不追改、批量投放幂等/409、失败率审计快照与设备自查。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareHardwareProfileTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollout_task_profile_snapshot");
        jdbc.update("DELETE FROM rollout_exclusion");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_profile_tag");
        jdbc.update("DELETE FROM device_hardware_profile");
        jdbc.update("DELETE FROM device_profile_head");
        jdbc.update("DELETE FROM hardware_requirement_tag");
        jdbc.update("DELETE FROM hardware_requirement_model");
        jdbc.update("DELETE FROM hardware_requirement");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private void register(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    /** 声明版本 V：型号 M、最低内存 4096MB、必需标签 BLE。 */
    private void declareV(String requestId) throws Exception {
        mockMvc.perform(post("/api/hardware-requirements").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"%s","firmwareVersion":"V","minMemoryMb":4096,
                 "models":["M"],"tags":["BLE"]}
                """.formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firmwareVersion").value("V"))
                .andExpect(jsonPath("$.minMemoryMb").value(4096))
                .andExpect(jsonPath("$.models[0]").value("M"))
                .andExpect(jsonPath("$.tags[0]").value("BLE"));
    }

    private void profile(String requestId, String deviceId, Integer expectedVersion,
                         String model, Integer memoryMb, String tagsJson) throws Exception {
        mockMvc.perform(put("/api/devices/" + deviceId + "/profile").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"%s","expectedVersion":%s,"model":%s,"memoryMb":%s,"tags":%s}
                """.formatted(requestId, String.valueOf(expectedVersion),
                        model == null ? "null" : "\"" + model + "\"",
                        memoryMb == null ? "null" : String.valueOf(memoryMb),
                        tagsJson)))
                .andExpect(status().isOk());
    }

    private long createReleaseToV(String requestId, int ratio) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"%s","model":"M","fromVersion":"1.0.0","toVersion":"V","ratio":%d}
                """.formatted(requestId, ratio)))
                .andExpect(status().isOk())
                .andReturn();
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),
                "$.releaseId");
        return value.longValue();
    }

    @Test
    void 合成验收_版本V要求M_4GB_BLE_设备D缺BLE不建任务也不算失败() throws Exception {
        declareV("req-hw");
        register("req-d1", "d-compatible", "M", "1.0.0", 1);
        register("req-d2", "D", "M", "1.0.0", 2);
        // 兼容设备：M / 4096MB / 含 BLE
        profile("req-p1", "d-compatible", 0, "M", 4096, "[\"BLE\"]");
        // 题干设备 D：M / 4GB 但无 BLE
        profile("req-p2", "D", 0, "M", 4096, "[]");
        long releaseId = createReleaseToV("req-r", 100);

        MvcResult dispatch = mockMvc.perform(post("/api/releases/" + releaseId + "/dispatch")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"req-dispatch","expectedVersion":1,
                                 "deviceIds":["D","d-compatible"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created.length()").value(1))
                .andExpect(jsonPath("$.created[0].deviceId").value("d-compatible"))
                .andExpect(jsonPath("$.created[0].profileVersion").value(1))
                .andExpect(jsonPath("$.excluded.length()").value(1))
                .andExpect(jsonPath("$.excluded[0].deviceId").value("D"))
                .andExpect(jsonPath("$.excluded[0].reasonCode").value("REQUIRED_TAG_MISSING"))
                .andExpect(jsonPath("$.excluded[0].profileVersion").value(1))
                .andReturn();
        assertThat(dispatch.getResponse().getContentAsString()).doesNotContain("\"task\":null");

        // D 没有任务
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = 'D'",
                Long.class, releaseId)).isEqualTo(0L);
        // 排除清单可查
        mockMvc.perform(get("/api/releases/" + releaseId + "/exclusions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exclusions.length()").value(1))
                .andExpect(jsonPath("$.exclusions[0].reasonCode").value("REQUIRED_TAG_MISSING"));
        // 不兼容不计入失败率：本轮统计为 0，发布单仍 ACTIVE
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roundFailed").value(0))
                .andExpect(jsonPath("$.roundSuccess").value(0));

        // 设备端：D 只看自己任务（空），兼容设备只看到自己的任务
        mockMvc.perform(get("/api/devices/D/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(0));
        mockMvc.perform(get("/api/devices/d-compatible/tasks"))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].deviceId").value("d-compatible"))
                .andExpect(jsonPath("$.tasks[0].profileVersion").value(1));
    }

    @Test
    void 硬件要求_重复声明409_型号集合不能为空_未声明查询404() throws Exception {
        declareV("req-hw1");
        mockMvc.perform(post("/api/hardware-requirements").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"req-hw2","firmwareVersion":"V","minMemoryMb":1024,"models":["X"],"tags":[]}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HARDWARE_REQUIREMENT_EXISTS"));
        mockMvc.perform(post("/api/hardware-requirements").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"req-hw3","firmwareVersion":"V2","minMemoryMb":1024,"models":[],"tags":[]}
                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/hardware-requirements/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HARDWARE_REQUIREMENT_NOT_FOUND"));
        // 同 requestId 同参重放
        MvcResult first = mockMvc.perform(post("/api/hardware-requirements")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"req-hw4","firmwareVersion":"W","minMemoryMb":null,
                                 "models":["M"],"tags":[]}
                                """))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/hardware-requirements")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"req-hw4","firmwareVersion":"W","minMemoryMb":null,
                                 "models":["M"],"tags":[]}
                                """))
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hardware_requirement", Long.class)).isEqualTo(2);
    }

    @Test
    void 画像写入_首次版本0_更新带版本号_版本不符409_缺字段给原因() throws Exception {
        register("req-d", "d1", "M", "1.0.0", 1);
        // 设备不存在
        mockMvc.perform(put("/api/devices/ghost/profile").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"req-x","expectedVersion":0,"model":"M","memoryMb":4096,"tags":["BLE"]}
                """))
                .andExpect(status().isNotFound());
        // 首次提交带非0版本：409
        mockMvc.perform(put("/api/devices/d1/profile").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"req-p0","expectedVersion":5,"model":"M","memoryMb":4096,"tags":["BLE"]}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_VERSION_CONFLICT"));
        // 首次提交
        mockMvc.perform(put("/api/devices/d1/profile").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"req-p1","model":"M","memoryMb":4096,"tags":["BLE"]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        // 再次以首次语义提交：409
        mockMvc.perform(put("/api/devices/d1/profile").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"req-p2","expectedVersion":0,"model":"M","memoryMb":8192,"tags":["BLE"]}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_VERSION_CONFLICT"));
        // 版本号过期：409
        mockMvc.perform(put("/api/devices/d1/profile").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"req-p3","expectedVersion":99,"model":"M","memoryMb":8192,"tags":["BLE"]}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_VERSION_CONFLICT"));
        // 正确更新：版本 1 -> 2
        mockMvc.perform(put("/api/devices/d1/profile").contentType(MediaType.APPLICATION_JSON).content("""
                {"requestId":"req-p4","expectedVersion":1,"model":"M","memoryMb":8192,"tags":["BLE","NFC"]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.memoryMb").value(8192))
                .andExpect(jsonPath("$.tags.length()").value(2));
        // 历史版本行仍在且不可变
        assertThat(jdbc.queryForObject(
                "SELECT memory_mb FROM device_hardware_profile WHERE device_id = 'd1' AND profile_version = 1",
                Integer.class)).isEqualTo(4096);
        // 未提交画像的设备查询 404
        register("req-d2", "d2", "M", "1.0.0", 1);
        mockMvc.perform(get("/api/devices/d2/profile"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
    }

    @Test
    void 拉取门禁_兼容建任务带画像版本_不兼容不建任务记排除_缺画像与缺字段原因() throws Exception {
        declareV("req-hw");
        register("req-d1", "d-ok", "M", "1.0.0", 1);
        register("req-d2", "d-noprofile", "M", "1.0.0", 2);
        register("req-d3", "d-nomodel", "M", "1.0.0", 3);
        register("req-d4", "d-nomem", "M", "1.0.0", 4);
        register("req-d5", "d-lowmem", "M", "1.0.0", 5);
        profile("req-p1", "d-ok", 0, "M", 4096, "[\"BLE\"]");
        profile("req-p3", "d-nomodel", 0, null, 4096, "[\"BLE\"]");
        profile("req-p4", "d-nomem", 0, "M", null, "[\"BLE\"]");
        profile("req-p5", "d-lowmem", 0, "M", 2048, "[\"BLE\"]");
        long releaseId = createReleaseToV("req-r", 100);

        mockMvc.perform(post("/api/devices/d-ok/pull").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-pull1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.profileVersion").value(1));
        for (var entry : java.util.Map.of(
                "d-noprofile", "PROFILE_MISSING",
                "d-nomodel", "MODEL_MISSING",
                "d-nomem", "MEMORY_MISSING",
                "d-lowmem", "MEMORY_INSUFFICIENT").entrySet()) {
            mockMvc.perform(post("/api/devices/" + entry.getKey() + "/pull")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"req-pull-" + entry.getKey() + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.task").doesNotExist());
            mockMvc.perform(get("/api/releases/" + releaseId + "/exclusions"))
                    .andExpect(jsonPath("$.exclusions[?(@.deviceId == '" + entry.getKey()
                            + "')].reasonCode").value(org.hamcrest.Matchers.hasItem(entry.getValue())));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task WHERE release_id = ?",
                Long.class, releaseId)).isEqualTo(1);
        // 无画像的排除记录画像版本为空
        assertThat(jdbc.queryForObject(
                "SELECT profile_version FROM rollout_exclusion WHERE device_id = 'd-noprofile'",
                Object.class)).isNull();
    }

    @Test
    void 画像更新只影响后续新任务_已下发任务与统计口径快照不变() throws Exception {
        declareV("req-hw");
        register("req-d", "d1", "M", "1.0.0", 1);
        profile("req-p1", "d1", 0, "M", 4096, "[\"BLE\"]");
        long releaseId = createReleaseToV("req-r", 100);
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-pull1\"}"))
                .andExpect(jsonPath("$.task.profileVersion").value(1)).andReturn();
        Number taskIdNumber = com.jayway.jsonpath.JsonPath.read(pull.getResponse().getContentAsString(),
                "$.task.taskId");
        long taskId = taskIdNumber.longValue();

        // 更新画像到 v2：内存变大、标签变化
        profile("req-p2", "d1", 1, "M", 8192, "[\"BLE\",\"NFC\"]");

        // 已下发任务仍引用画像 v1，快照内容不被追改
        mockMvc.perform(post("/api/devices/d1/pull").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-pull2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(taskId))
                .andExpect(jsonPath("$.task.profileVersion").value(1));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks/" + taskId + "/profile-snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileVersion").value(1))
                .andExpect(jsonPath("$.model").value("M"))
                .andExpect(jsonPath("$.memoryMb").value(4096))
                .andExpect(jsonPath("$.capabilityTags.length()").value(1))
                .andExpect(jsonPath("$.capabilityTags[0]").value("BLE"));
        // 任务总数仍为 1，未因画像更新产生新任务
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);
    }

    @Test
    void 画像转为兼容后后续投放建任务并移除排除记录_排除设备不固化() throws Exception {
        declareV("req-hw");
        register("req-d", "d1", "M", "1.0.0", 1);
        profile("req-p1", "d1", 0, "M", 4096, "[]");
        long releaseId = createReleaseToV("req-r", 100);

        mockMvc.perform(post("/api/devices/d1/pull").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-pull1\"}"))
                .andExpect(jsonPath("$.task").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_exclusion", Long.class)).isEqualTo(1);

        // 更新画像补齐 BLE，后续新请求建任务
        profile("req-p2", "d1", 1, "M", 4096, "[\"BLE\"]");
        mockMvc.perform(post("/api/devices/d1/pull").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-pull2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.profileVersion").value(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_exclusion", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);
    }

    @Test
    void 批量投放_重放首次清单_换候选集合409_换版本409() throws Exception {
        declareV("req-hw");
        register("req-d1", "d1", "M", "1.0.0", 1);
        profile("req-p1", "d1", 0, "M", 4096, "[]");
        long releaseId = createReleaseToV("req-r", 100);

        String body = """
                {"requestId":"req-dispatch","expectedVersion":1,"deviceIds":["d1"]}
                """;
        MvcResult first = mockMvc.perform(post("/api/releases/" + releaseId + "/dispatch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excluded[0].deviceId").value("d1"))
                .andReturn();
        // 画像随后变为兼容
        profile("req-p2", "d1", 1, "M", 4096, "[\"BLE\"]");
        // 同 requestId 同参重放：首次清单原样返回，不重新筛选、不建任务
        MvcResult replay = mockMvc.perform(post("/api/releases/" + releaseId + "/dispatch")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        // 同 requestId 换候选集合：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/dispatch")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"req-dispatch","expectedVersion":1,"deviceIds":["d1","d2"]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 新 requestId 在新画像下投放：建任务
        mockMvc.perform(post("/api/releases/" + releaseId + "/dispatch")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"req-dispatch2","expectedVersion":1,"deviceIds":["d1"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created.length()").value(1));

        // 发布单扩量后版本变为 2，旧 expectedVersion=1 再投放：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/expand").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-exp\",\"expectedVersion\":1,\"ratio\":100}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/releases/" + releaseId + "/dispatch")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"req-dispatch3","expectedVersion":1,"deviceIds":["d1"]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void 批量投放_业务条件不满足的设备静默跳过_已建任务设备回到created_不存在设备404() throws Exception {
        declareV("req-hw");
        register("req-d1", "d1", "M", "1.0.0", 50);
        register("req-d2", "d2", "M", "9.9.9", 1);
        register("req-d3", "d3", "M", "1.0.0", 1);
        profile("req-p1", "d1", 0, "M", 4096, "[\"BLE\"]");
        profile("req-p2", "d2", 0, "M", 4096, "[\"BLE\"]");
        profile("req-p3", "d3", 0, "M", 4096, "[\"BLE\"]");
        long releaseId = createReleaseToV("req-r", 10);

        mockMvc.perform(post("/api/releases/" + releaseId + "/dispatch")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"req-dispatch-1","expectedVersion":1,
                                 "deviceIds":["d1","d2","d3"]}
                                """))
                .andExpect(status().isOk())
                // d1 分桶不达标、d2 来源版本不符：静默跳过；d3 建任务
                .andExpect(jsonPath("$.created.length()").value(1))
                .andExpect(jsonPath("$.created[0].deviceId").value("d3"))
                .andExpect(jsonPath("$.excluded.length()").value(0));

        // 再次投放：d3 已有任务，回到 created，不重复建
        mockMvc.perform(post("/api/releases/" + releaseId + "/dispatch")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"req-dispatch-2","expectedVersion":1,"deviceIds":["d3"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);

        // 候选设备不存在：404
        mockMvc.perform(post("/api/releases/" + releaseId + "/dispatch")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"req-dispatch-3","expectedVersion":1,"deviceIds":["ghost"]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
        // 发布单不存在：404
        mockMvc.perform(post("/api/releases/9999/dispatch")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"req-dispatch-4","expectedVersion":1,"deviceIds":["d3"]}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void 版本未声明硬件要求时不启用门禁_兼容旧行为且任务无画像版本与快照() throws Exception {
        register("req-d", "d1", "M", "1.0.0", 1);
        // to_version 为未声明硬件要求的版本：门禁不启用
        MvcResult release = mockMvc.perform(post("/api/releases").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-r","model":"M","fromVersion":"1.0.0",
                                 "toVersion":"2.0.0","ratio":100}
                                """))
                .andExpect(status().isOk()).andReturn();
        Number releaseIdNumber = com.jayway.jsonpath.JsonPath.read(release.getResponse().getContentAsString(),
                "$.releaseId");
        long releaseId = releaseIdNumber.longValue();
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-pull1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.profileVersion").doesNotExist())
                .andReturn();
        Number taskIdNumber = com.jayway.jsonpath.JsonPath.read(pull.getResponse().getContentAsString(),
                "$.task.taskId");
        long taskId = taskIdNumber.longValue();
        // 无快照可审计：404
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks/" + taskId + "/profile-snapshot"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROFILE_SNAPSHOT_NOT_FOUND"));
    }
}
