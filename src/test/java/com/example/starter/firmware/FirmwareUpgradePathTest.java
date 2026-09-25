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
 * 固件升级路径前置版本链 API 测试（H2 内存库，MODE=MySQL）：
 * 版本链登记与环校验、PATH_BLOCKED 拦截、跳级开关、链式升级主流程与幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareUpgradePathTest {

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
        jdbc.update("DELETE FROM path_blocked_record");
        jdbc.update("DELETE FROM firmware_version");
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerVersion(String requestId, String version, String predecessor) throws Exception {
        String body = predecessor == null
                ? "{\"requestId\":\"%s\",\"version\":\"%s\"}".formatted(requestId, version)
                : "{\"requestId\":\"%s\",\"version\":\"%s\",\"predecessor\":\"%s\"}"
                        .formatted(requestId, version, predecessor);
        mockMvc.perform(post("/api/versions").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(version));
    }

    private void registerDevice(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, String from, String to, int ratio)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":%d}
                """.formatted(requestId, model, from, to, ratio)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private MvcResult pull(String requestId, String deviceId) throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/pull").contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andReturn();
    }

    private void receipt(long taskId, String requestId, String result) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"result\":\"%s\"}".formatted(requestId, result)))
                .andExpect(status().isOk());
    }

    private void registerChain123() throws Exception {
        registerVersion("v-1", "1.0.0", null);
        registerVersion("v-2", "2.0.0", "1.0.0");
        registerVersion("v-3", "3.0.0", "2.0.0");
    }

    @Test
    void 版本登记_前置不存在422_自环与多节点环422_链明细查询() throws Exception {
        // 前置版本未登记：422
        mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v-a","version":"2.0.0","predecessor":"9.9.9"}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PREDECESSOR_NOT_FOUND"));

        // 自环：422
        mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v-b","version":"1.0.0","predecessor":"1.0.0"}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VERSION_CHAIN_CYCLE"));

        registerChain123();

        // 多节点环：将链起点 1.0.0 的前置改为 3.0.0，形成 1.0.0->3.0.0->2.0.0->1.0.0
        mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v-c","version":"1.0.0","predecessor":"3.0.0"}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VERSION_CHAIN_CYCLE"));

        // 环校验失败后前置保持不变
        mockMvc.perform(get("/api/versions/1.0.0/chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chain.length()").value(1))
                .andExpect(jsonPath("$.chain[0].version").value("1.0.0"))
                .andExpect(jsonPath("$.chain[0].predecessor").doesNotExist());

        // 链明细：从 3.0.0 到链起点
        mockMvc.perform(get("/api/versions/3.0.0/chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("3.0.0"))
                .andExpect(jsonPath("$.chain.length()").value(3))
                .andExpect(jsonPath("$.chain[0].version").value("3.0.0"))
                .andExpect(jsonPath("$.chain[0].predecessor").value("2.0.0"))
                .andExpect(jsonPath("$.chain[1].version").value("2.0.0"))
                .andExpect(jsonPath("$.chain[2].version").value("1.0.0"));

        // 未登记版本链查询：404
        mockMvc.perform(get("/api/versions/9.9.9/chain"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));
    }

    @Test
    void 版本登记_修改前置生效_幂等同键重放与异参409() throws Exception {
        registerVersion("v-1", "1.0.0", null);
        registerVersion("v-2", "2.0.0", "1.0.0");
        registerVersion("v-3", "3.0.0", null);

        // 修改 3.0.0 的前置为 2.0.0
        mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v-4","version":"3.0.0","predecessor":"2.0.0"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predecessor").value("2.0.0"));
        mockMvc.perform(get("/api/versions/3.0.0/chain"))
                .andExpect(jsonPath("$.chain.length()").value(3));

        // 同 requestId 同参重放：返回首次结果，不重复登记
        String first = mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v-4","version":"3.0.0","predecessor":"2.0.0"}
                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(first).contains("\"version\":\"3.0.0\"");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM firmware_version", Long.class)).isEqualTo(3);

        // 同 requestId 异参：409
        mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v-4","version":"3.0.0","predecessor":"1.0.0"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：422 之后同 requestId 以合法参数成功
        mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v-5","version":"4.0.0","predecessor":"9.9.9"}
                        """))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v-5","version":"4.0.0","predecessor":"3.0.0"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.predecessor").value("3.0.0"));
    }

    @Test
    void 跳级拦截_返回PATH_BLOCKED与下一版本_不计样本不改状态_历史可查() throws Exception {
        registerChain123();
        registerDevice("d-1", "dev1", "m1", "1.0.0", 1);
        long releaseId = createRelease("r-1", "m1", "2.0.0", "3.0.0", 100);

        // 设备当前 1.0.0，目标 3.0.0 的前置链上 2.0.0 未安装：PATH_BLOCKED，下一版本 2.0.0
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PATH_BLOCKED"))
                .andExpect(jsonPath("$.nextVersion").value("2.0.0"))
                .andExpect(jsonPath("$.task").doesNotExist());

        // 不产生任务、不计入失败率样本、设备版本不变
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));
        mockMvc.perform(get("/api/devices/dev1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // PATH_BLOCKED 历史可查
        mockMvc.perform(get("/api/releases/" + releaseId + "/path-blocked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(releaseId))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].deviceId").value("dev1"))
                .andExpect(jsonPath("$.records[0].deviceVersion").value("1.0.0"))
                .andExpect(jsonPath("$.records[0].targetVersion").value("3.0.0"))
                .andExpect(jsonPath("$.records[0].nextVersion").value("2.0.0"));

        // 同 requestId 重放：返回首次结果，不重复落历史
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PATH_BLOCKED"))
                .andExpect(jsonPath("$.nextVersion").value("2.0.0"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM path_blocked_record", Long.class)).isEqualTo(1);

        // 设备版本不在链上：下一个必须安装的版本为链起点
        registerDevice("d-2", "dev2", "m1", "0.9.0", 1);
        mockMvc.perform(post("/api/devices/dev2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PATH_BLOCKED"))
                .andExpect(jsonPath("$.nextVersion").value("1.0.0"));

        // 设备当前版本为目标版本本身：按既有规则处理（无新任务）
        registerDevice("d-3", "dev3", "m1", "3.0.0", 1);
        mockMvc.perform(post("/api/devices/dev3/pull").contentType("application/json")
                        .content("{\"requestId\":\"p-3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("NONE"))
                .andExpect(jsonPath("$.task").doesNotExist());

        // 发布单不存在时查询 PATH_BLOCKED 历史：404
        mockMvc.perform(get("/api/releases/9999/path-blocked"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 直接前置正常下发_逐级升级_回执成功更新设备版本() throws Exception {
        registerChain123();
        registerDevice("d-1", "dev1", "m1", "1.0.0", 1);

        // 第一级：1.0.0 -> 2.0.0
        long releaseA = createRelease("r-1", "m1", "1.0.0", "2.0.0", 100);
        MvcResult pullA = pull("p-1", "dev1");
        long taskA = idOf(pullA, "$.task.taskId");
        receipt(taskA, "rc-1", "SUCCESS");
        mockMvc.perform(get("/api/devices/dev1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        mockMvc.perform(post("/api/releases/" + releaseA + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"x-1\"}"))
                .andExpect(status().isOk());

        // 第二级：设备当前 2.0.0 为目标 3.0.0 的直接前置，按既有规则直接下发
        long releaseB = createRelease("r-2", "m1", "2.0.0", "3.0.0", 100);
        MvcResult pullB = mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("TASK"))
                .andExpect(jsonPath("$.task.toVersion").value("3.0.0"))
                .andReturn();
        long taskB = idOf(pullB, "$.task.taskId");
        receipt(taskB, "rc-2", "SUCCESS");
        mockMvc.perform(get("/api/devices/dev1"))
                .andExpect(jsonPath("$.currentVersion").value("3.0.0"));

        // 失败回执不更新设备版本
        registerDevice("d-2", "dev2", "m1", "2.0.0", 2);
        MvcResult pullC = pull("p-3", "dev2");
        long taskC = idOf(pullC, "$.task.taskId");
        receipt(taskC, "rc-3", "FAILED");
        mockMvc.perform(get("/api/devices/dev2"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
    }

    @Test
    void 跳级开关_声明后直接下发_版本冲突409_不改写已下发任务() throws Exception {
        registerChain123();
        registerDevice("d-1", "dev1", "m1", "1.0.0", 1);
        registerDevice("d-2", "dev2", "m1", "2.0.0", 2);
        long releaseId = createRelease("r-1", "m1", "2.0.0", "3.0.0", 100);

        // dev2 在直接前置版本：开关前已正常下发
        MvcResult existing = pull("p-1", "dev2");
        long existingTaskId = idOf(existing, "$.task.taskId");

        // dev1 被拦截
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p-2\"}"))
                .andExpect(jsonPath("$.result").value("PATH_BLOCKED"));

        // expectedVersion 不一致：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/skip").contentType("application/json")
                        .content("{\"requestId\":\"s-1\",\"expectedVersion\":9,\"allowSkip\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 声明允许跳级：版本加一
        mockMvc.perform(post("/api/releases/" + releaseId + "/skip").contentType("application/json")
                        .content("{\"requestId\":\"s-2\",\"expectedVersion\":1,\"allowSkip\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.allowSkip").value(true));

        // 同 requestId 重放：版本不再增加
        mockMvc.perform(post("/api/releases/" + releaseId + "/skip").contentType("application/json")
                        .content("{\"requestId\":\"s-2\",\"expectedVersion\":1,\"allowSkip\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 开关后 dev1 直接下发目标任务
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p-3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("TASK"))
                .andExpect(jsonPath("$.task.fromVersion").value("2.0.0"))
                .andExpect(jsonPath("$.task.toVersion").value("3.0.0"));

        // 已下发任务不改写：dev2 仍是原任务
        mockMvc.perform(post("/api/devices/dev2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p-4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(existingTaskId))
                .andExpect(jsonPath("$.task.status").value("PENDING"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ?", Long.class, releaseId)).isEqualTo(2);

        // 关闭跳级后再次拦截新设备
        mockMvc.perform(post("/api/releases/" + releaseId + "/skip").contentType("application/json")
                        .content("{\"requestId\":\"s-3\",\"expectedVersion\":2,\"allowSkip\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowSkip").value(false));
        registerDevice("d-3", "dev3", "m1", "1.0.0", 3);
        mockMvc.perform(post("/api/devices/dev3/pull").contentType("application/json")
                        .content("{\"requestId\":\"p-5\"}"))
                .andExpect(jsonPath("$.result").value("PATH_BLOCKED"))
                .andExpect(jsonPath("$.nextVersion").value("2.0.0"));

        // 已取消发布单不能修改开关：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"x-1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/releases/" + releaseId + "/skip").contentType("application/json")
                        .content("{\"requestId\":\"s-4\",\"expectedVersion\":3,\"allowSkip\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_CANCELLED"));
    }

    @Test
    void 创建发布单声明跳级_拉取幂等异参409() throws Exception {
        registerChain123();
        registerDevice("d-1", "dev1", "m1", "1.0.0", 1);

        // 创建时声明允许跳级：直接下发
        MvcResult created = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r-1","model":"m1","fromVersion":"2.0.0","toVersion":"3.0.0","ratio":100,
                        "allowSkip":true}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowSkip").value(true))
                .andReturn();
        long releaseId = idOf(created, "$.releaseId");
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("TASK"))
                .andExpect(jsonPath("$.task.toVersion").value("3.0.0"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM path_blocked_record WHERE release_id = ?",
                Long.class, releaseId)).isZero();

        // 拉取同 requestId 异参（不同设备）：409
        registerDevice("d-2", "dev2", "m1", "1.0.0", 2);
        mockMvc.perform(post("/api/devices/dev2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }
}
