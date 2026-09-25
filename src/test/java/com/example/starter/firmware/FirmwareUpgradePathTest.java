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
 * 版本链登记与环校验、PATH_BLOCKED 拦截、跳级开关、拦截历史与逐级安装主流程。
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
        jdbc.update("DELETE FROM path_blocked_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM firmware_version");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerVersion(String requestId, String version, String predecessor) throws Exception {
        String body = predecessor == null
                ? "{\"requestId\":\"%s\",\"version\":\"%s\"}".formatted(requestId, version)
                : "{\"requestId\":\"%s\",\"version\":\"%s\",\"predecessorVersion\":\"%s\"}"
                        .formatted(requestId, version, predecessor);
        mockMvc.perform(post("/api/versions").contentType("application/json").content(body))
                .andExpect(status().isOk());
    }

    private void registerChain123() throws Exception {
        registerVersion("v1", "1.0", null);
        registerVersion("v2", "2.0", "1.0");
        registerVersion("v3", "3.0", "2.0");
    }

    private void registerDevice(String requestId, String deviceId, String model, String version)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":1}
                """.formatted(requestId, deviceId, model, version)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, String from, String to) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":100}
                """.formatted(requestId, model, from, to)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private MvcResult pull(String deviceId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/pull").contentType("application/json")
                        .content("{\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void 版本登记_前置未登记422_自环422_重复登记409_链明细查询() throws Exception {
        // 前置版本未登记：422
        mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v1","version":"2.0","predecessorVersion":"1.0"}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PREDECESSOR_NOT_FOUND"));

        // 自环：422
        mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v2","version":"1.0","predecessorVersion":"1.0"}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VERSION_CHAIN_CYCLE"));

        // 链起点（无前置）登记成功
        mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v3","version":"1.0"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0"))
                .andExpect(jsonPath("$.predecessorVersion").doesNotExist())
                .andExpect(jsonPath("$.chain.length()").value(1));

        registerVersion("v4", "2.0", "1.0");
        registerVersion("v5", "3.0", "2.0");

        // 重复登记：409
        mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v6","version":"2.0","predecessorVersion":"1.0"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_EXISTS"));

        // 链明细：从自身回溯到链起点
        mockMvc.perform(get("/api/versions/3.0/chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("3.0"))
                .andExpect(jsonPath("$.predecessorVersion").value("2.0"))
                .andExpect(jsonPath("$.chain.length()").value(3))
                .andExpect(jsonPath("$.chain[0]").value("3.0"))
                .andExpect(jsonPath("$.chain[1]").value("2.0"))
                .andExpect(jsonPath("$.chain[2]").value("1.0"));

        // 未登记版本查询：404
        mockMvc.perform(get("/api/versions/9.9/chain"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));
    }

    @Test
    void 版本登记_幂等重放_异参409_失败不占键() throws Exception {
        registerVersion("v1", "1.0", null);
        MvcResult first = mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v2","version":"2.0","predecessorVersion":"1.0"}
                        """))
                .andExpect(status().isOk())
                .andReturn();

        // 同键同参重放：响应一致，不重复登记
        MvcResult replay = mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v2","version":"2.0","predecessorVersion":"1.0"}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM firmware_version", Long.class)).isEqualTo(2);

        // 同键异参：409
        mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v2","version":"3.0","predecessorVersion":"2.0"}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：422 之后同 requestId 以合法参数成功
        mockMvc.perform(post("/api/versions").contentType("application/json").content("""
                        {"requestId":"v3","version":"4.0","predecessorVersion":"9.9"}
                        """))
                .andExpect(status().isUnprocessableEntity());
        registerVersion("v3", "4.0", "2.0");
        mockMvc.perform(get("/api/versions/4.0/chain"))
                .andExpect(jsonPath("$.predecessorVersion").value("2.0"));
    }

    @Test
    void 跳级拦截_返回下一中间版本_不建任务不计样本不改状态_历史可查() throws Exception {
        registerChain123();
        registerDevice("d1", "dev1", "m1", "1.0");
        long releaseId = createRelease("r1", "m1", "1.0", "3.0");

        // 设备在 1.0，目标 3.0 的前置链上存在未安装的 2.0：PATH_BLOCKED
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PATH_BLOCKED"))
                .andExpect(jsonPath("$.requiredVersion").value("2.0"))
                .andExpect(jsonPath("$.task").doesNotExist());

        // 不建任务、不计失败率样本、设备版本不变
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));
        mockMvc.perform(get("/api/devices/dev1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0"));

        // 同 requestId 重放：结果一致，历史不重复追加
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PATH_BLOCKED"))
                .andExpect(jsonPath("$.requiredVersion").value("2.0"));

        // PATH_BLOCKED 历史查询
        mockMvc.perform(get("/api/releases/" + releaseId + "/path-blocked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(releaseId))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].deviceId").value("dev1"))
                .andExpect(jsonPath("$.records[0].currentVersion").value("1.0"))
                .andExpect(jsonPath("$.records[0].requiredVersion").value("2.0"))
                .andExpect(jsonPath("$.records[0].targetVersion").value("3.0"));

        // 发布单不存在：404
        mockMvc.perform(get("/api/releases/9999/path-blocked"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 链上判定_直接前置与目标本身按既有规则_不在链上从起点开始() throws Exception {
        registerChain123();

        // 设备在直接前置 2.0：按既有规则下发
        registerDevice("d1", "dev-a", "ma", "2.0");
        createRelease("r1", "ma", "2.0", "3.0");
        mockMvc.perform(post("/api/devices/dev-a/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ISSUED"))
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.toVersion").value("3.0"));

        // 设备已在目标版本 3.0：按既有规则（版本不匹配 fromVersion），不投放也不拦截
        registerDevice("d2", "dev-b", "mb", "3.0");
        createRelease("r2", "mb", "2.0", "3.0");
        mockMvc.perform(post("/api/devices/dev-b/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("NONE"))
                .andExpect(jsonPath("$.task").doesNotExist());

        // 设备不在目标链上：从链起点 1.0 开始安装
        registerDevice("d3", "dev-c", "mc", "0.9");
        long releaseId = createRelease("r3", "mc", "0.9", "3.0");
        mockMvc.perform(post("/api/devices/dev-c/pull").contentType("application/json")
                        .content("{\"requestId\":\"p3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PATH_BLOCKED"))
                .andExpect(jsonPath("$.requiredVersion").value("1.0"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/path-blocked"))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].requiredVersion").value("1.0"));

        // 目标版本未登记链信息：沿用既有规则直接下发
        registerDevice("d4", "dev-d", "md", "1.0");
        createRelease("r4", "md", "1.0", "9.9");
        mockMvc.perform(post("/api/devices/dev-d/pull").contentType("application/json")
                        .content("{\"requestId\":\"p4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ISSUED"))
                .andExpect(jsonPath("$.task.toVersion").value("9.9"));
    }

    @Test
    void 跳级开关_声明后直接下发_版本冲突409_只影响后续拉取() throws Exception {
        registerChain123();
        registerDevice("d1", "dev-a", "m1", "1.0");
        registerDevice("d2", "dev-b", "m1", "1.0");
        long releaseId = createRelease("r1", "m1", "1.0", "3.0");

        // 开关未声明：dev-a 被拦截
        mockMvc.perform(post("/api/devices/dev-a/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(jsonPath("$.result").value("PATH_BLOCKED"))
                .andExpect(jsonPath("$.requiredVersion").value("2.0"));

        // expectedVersion 不匹配：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/skip-level").contentType("application/json")
                        .content("{\"requestId\":\"s1\",\"expectedVersion\":99,\"allowSkip\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 声明允许跳级：版本加一
        mockMvc.perform(post("/api/releases/" + releaseId + "/skip-level").contentType("application/json")
                        .content("{\"requestId\":\"s2\",\"expectedVersion\":1,\"allowSkip\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.allowSkip").value(true));

        // 声明后忽略前置链直接下发
        MvcResult pullB = mockMvc.perform(post("/api/devices/dev-b/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ISSUED"))
                .andExpect(jsonPath("$.task.toVersion").value("3.0"))
                .andReturn();
        long taskB = idOf(pullB, "$.task.taskId");

        // 关闭开关（版本 2 → 3）
        mockMvc.perform(post("/api/releases/" + releaseId + "/skip-level").contentType("application/json")
                        .content("{\"requestId\":\"s3\",\"expectedVersion\":2,\"allowSkip\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.allowSkip").value(false));

        // 已下发任务不改写：dev-b 再拉取仍返回原任务
        mockMvc.perform(post("/api/devices/dev-b/pull").contentType("application/json")
                        .content("{\"requestId\":\"p3\"}"))
                .andExpect(jsonPath("$.result").value("ISSUED"))
                .andExpect(jsonPath("$.task.taskId").value(taskB));

        // 开关关闭后，dev-a 后续拉取重新被拦截
        mockMvc.perform(post("/api/devices/dev-a/pull").contentType("application/json")
                        .content("{\"requestId\":\"p4\"}"))
                .andExpect(jsonPath("$.result").value("PATH_BLOCKED"))
                .andExpect(jsonPath("$.requiredVersion").value("2.0"));

        // 回执成功后设备版本更新为任务版本
        mockMvc.perform(post("/api/tasks/" + taskB + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/dev-b"))
                .andExpect(jsonPath("$.currentVersion").value("3.0"));

        // 已取消发布单不能修改开关；发布单不存在 404
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"c1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/releases/" + releaseId + "/skip-level").contentType("application/json")
                        .content("{\"requestId\":\"s4\",\"expectedVersion\":3,\"allowSkip\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_CANCELLED"));
        mockMvc.perform(post("/api/releases/9999/skip-level").contentType("application/json")
                        .content("{\"requestId\":\"s5\",\"expectedVersion\":1,\"allowSkip\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 逐级安装_安装中间版本后链上前进一步() throws Exception {
        registerChain123();
        registerDevice("d1", "dev1", "m1", "1.0");

        // 直达 3.0 被拦截，要求先装 2.0
        long jumpRelease = createRelease("r1", "m1", "1.0", "3.0");
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(jsonPath("$.result").value("PATH_BLOCKED"))
                .andExpect(jsonPath("$.requiredVersion").value("2.0"));
        mockMvc.perform(post("/api/releases/" + jumpRelease + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"c1\"}"))
                .andExpect(status().isOk());

        // 安装中间版本 2.0
        long stepOne = createRelease("r2", "m1", "1.0", "2.0");
        MvcResult pull1 = pull("dev1", "p2");
        long task1 = idOf(pull1, "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + task1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/dev1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0"));
        mockMvc.perform(post("/api/releases/" + stepOne + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"c2\"}"))
                .andExpect(status().isOk());

        // 设备已在直接前置 2.0：直达 3.0 正常下发
        createRelease("r3", "m1", "2.0", "3.0");
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ISSUED"))
                .andExpect(jsonPath("$.task.toVersion").value("3.0"));
    }
}
