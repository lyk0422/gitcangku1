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
 * 固件灰度投放 API 主流程、失败分支与幂等边界测试（H2 内存库，MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM region_wait_record");
        jdbc.update("DELETE FROM region_throttle_event");
    }

    private String registerDevice(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d,"region":"cn-north"}
                                """.formatted(requestId, deviceId, model, version, bucket)))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private String createRelease(String requestId, String model, String from, String to, int ratio)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"%s","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":%d}
                                """.formatted(requestId, model, from, to, ratio)))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private long releaseIdOf(String responseBody) {
        Number id = com.jayway.jsonpath.JsonPath.read(responseBody, "$.releaseId");
        return id.longValue();
    }

    @Test
    void 主流程_登记发布拉取回执成功并更新设备版本() throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r1","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":5,"region":"cn-north"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("d1"))
                .andExpect(jsonPath("$.bucketNo").value(5))
                .andExpect(jsonPath("$.region").value("cn-north"));

        MvcResult release = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        long releaseId = idOf(release, "$.releaseId");

        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.fromVersion").value("1.0.0"))
                .andExpect(jsonPath("$.task.toVersion").value("2.0.0"))
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].status").value("SUCCESS"));
    }

    @Test
    void 设备登记_重复设备冲突_参数校验() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 5);

        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r2","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":5,"region":"cn-north"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_EXISTS"));

        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r3","deviceId":"d2","model":"m1","currentVersion":"1.0.0","bucketNo":100,"region":"cn-north"}
                        """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"deviceId":"d3","model":"m1","currentVersion":"1.0.0","bucketNo":1,"region":"cn-north"}
                        """))
                .andExpect(status().isBadRequest());

        // 缺少区域标识：400
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"r4","deviceId":"d4","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 幂等_同键同参重放原结果_异参409_失败不占键() throws Exception {
        String first = registerDevice("rid-1", "d1", "m1", "1.0.0", 5);
        assertThat(first).contains("\"deviceId\":\"d1\"");

        String replay = registerDevice("rid-1", "d1", "m1", "1.0.0", 5);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device", Long.class)).isEqualTo(1);

        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"rid-1","deviceId":"d9","model":"m1","currentVersion":"1.0.0","bucketNo":5,"region":"cn-north"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：先以错误参数触发 409，再用同 requestId 正确参数成功
        createRelease("rid-x", "m1", "1.0.0", "1.0.0", 10); // 400 在 Service 外，requestId 未入库
        MvcResult created = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"rid-y","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        long releaseId = releaseIdOf(created.getResponse().getContentAsString());
        mockMvc.perform(post("/api/releases/" + releaseId + "/expand").contentType("application/json").content("""
                        {"requestId":"rid-z","expectedVersion":99,"ratio":50}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(post("/api/releases/" + releaseId + "/expand").contentType("application/json").content("""
                        {"requestId":"rid-z","expectedVersion":1,"ratio":50}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.ratio").value(50));
    }

    @Test
    void 发布单_目标来源相同400_同型号第二张ACTIVE409() throws Exception {
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r1","model":"m1","fromVersion":"1.0.0","toVersion":"1.0.0","ratio":10}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_VERSION"));

        createRelease("r2", "m1", "1.0.0", "2.0.0", 10);
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r3","model":"m1","fromVersion":"1.0.0","toVersion":"3.0.0","ratio":20}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_RELEASE_EXISTS"));
    }

    @Test
    void 扩量_版本校验_比例只增不减_重放不再加版本() throws Exception {
        long releaseId = releaseIdOf(createRelease("r1", "m1", "1.0.0", "2.0.0", 10));

        mockMvc.perform(post("/api/releases/" + releaseId + "/expand").contentType("application/json").content("""
                        {"requestId":"r2","expectedVersion":2,"ratio":30}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        mockMvc.perform(post("/api/releases/" + releaseId + "/expand").contentType("application/json").content("""
                        {"requestId":"r3","expectedVersion":1,"ratio":30}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.ratio").value(30));

        // 同 requestId 同参重放：版本不再增加
        mockMvc.perform(post("/api/releases/" + releaseId + "/expand").contentType("application/json").content("""
                        {"requestId":"r3","expectedVersion":1,"ratio":30}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.ratio").value(30));

        // 同 requestId 异参：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/expand").contentType("application/json").content("""
                        {"requestId":"r3","expectedVersion":1,"ratio":40}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 比例下降：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/expand").contentType("application/json").content("""
                        {"requestId":"r4","expectedVersion":2,"ratio":20}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RATIO_DECREASE"));

        // 比例越界：400
        mockMvc.perform(post("/api/releases/" + releaseId + "/expand").contentType("application/json").content("""
                        {"requestId":"r5","expectedVersion":2,"ratio":101}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 拉取_不匹配不创建任务_重复拉取返回已有任务() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 50);
        registerDevice("r2", "d2", "m1", "9.9.9", 1);
        createRelease("r3", "m1", "1.0.0", "2.0.0", 10);

        // 分桶号不小于比例：不投放
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());

        // 当前版本不匹配：不投放
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());

        // 无 ACTIVE 发布单的型号：不投放
        registerDevice("r6", "d3", "m2", "1.0.0", 1);
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());

        // 命中后重复拉取返回同一任务
        registerDevice("r8", "d4", "m1", "1.0.0", 3);
        MvcResult first = mockMvc.perform(post("/api/devices/d4/pull").contentType("application/json")
                        .content("{\"requestId\":\"r9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        long taskId = idOf(first, "$.task.taskId");
        mockMvc.perform(post("/api/devices/d4/pull").contentType("application/json")
                        .content("{\"requestId\":\"r10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(taskId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);

        // 设备不存在：404
        mockMvc.perform(post("/api/devices/ghost/pull").contentType("application/json")
                        .content("{\"requestId\":\"r11\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 回执_失败终结且本轮不再投放_同结果重复成功_改结果409() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        createRelease("r2", "m1", "1.0.0", "2.0.0", 100);
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r3\"}"))
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r4\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        // 设备版本不被 FAILED 更新
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 同结果重复回执成功
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r5\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        // 改结果 409
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_RESULT_CONFLICT"));

        // 已失败任务本轮不重新投放：拉取返回已有 FAILED 任务
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(taskId))
                .andExpect(jsonPath("$.task.status").value("FAILED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);

        // 任务不存在：404
        mockMvc.perform(post("/api/tasks/9999/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r8\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 取消_未终结任务转CANCELLED_后到回执409且不更新版本() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        registerDevice("r2", "d2", "m1", "1.0.0", 2);
        long releaseId = releaseIdOf(createRelease("r3", "m1", "1.0.0", "2.0.0", 100));
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r4\"}"))
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].status").value("CANCELLED"));

        // 取消任务的后到回执：409 且设备版本不更新
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r6\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 取消后不再投放
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());

        // 重复取消（新 requestId）幂等返回当前状态
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r8\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 已取消发布单不能扩量
        mockMvc.perform(post("/api/releases/" + releaseId + "/expand").contentType("application/json").content("""
                        {"requestId":"r9","expectedVersion":1,"ratio":80}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));

        // 取消不存在的发布单：404
        mockMvc.perform(post("/api/releases/9999/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r10\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 任务明细查询_按状态过滤_发布单不存在404() throws Exception {
        registerDevice("r1", "d1", "m1", "1.0.0", 1);
        registerDevice("r2", "d2", "m1", "1.0.0", 2);
        long releaseId = releaseIdOf(createRelease("r3", "m1", "1.0.0", "2.0.0", 100));
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                .content("{\"requestId\":\"r4\"}"));
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                .content("{\"requestId\":\"r5\"}"));

        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(2));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(2));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(0));
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/releases/9999/tasks"))
                .andExpect(status().isNotFound());
    }
}
