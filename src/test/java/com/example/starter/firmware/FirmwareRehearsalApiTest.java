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
 * 固件回滚演练与正式投放门禁 API 测试（H2 内存库，MODE=MySQL）：
 * 主流程、422/409 失败分支、整批事务不留半成品、幂等重放快照、门禁拦截、不可变历史与稳定排序。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareRehearsalApiTest {

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
        jdbc.update("DELETE FROM release_gate");
        jdbc.update("DELETE FROM rollback_rehearsal_device");
        jdbc.update("DELETE FROM rollback_rehearsal");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private void registerDevice(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    private MvcResult startRehearsal(String body) throws Exception {
        return mockMvc.perform(post("/api/rehearsals").contentType("application/json").content(body))
                .andReturn();
    }

    private MvcResult startSuccess(String requestId, String operator, String model, String target,
                                   String rollback, String deviceIdsJson) throws Exception {
        return mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"%s","operator":"%s","model":"%s","targetVersion":"%s",
                "rollbackVersion":"%s","deviceIds":%s}
                """.formatted(requestId, operator, model, target, rollback, deviceIdsJson)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void seedGate(String model, String targetVersion) {
        jdbc.update("INSERT INTO release_gate (model, target_version, status, rehearsal_id,"
                        + " required_device_count, actual_success_count, granted_at_utc)"
                        + " VALUES (?, ?, 'READY_FOR_RELEASE', -1, 1, 1, '2026-09-01T00:00:00Z')",
                model, targetVersion);
    }

    @Test
    void 主流程_演练全部成功授予门禁后才能创建正式投放() throws Exception {
        registerDevice("rd1", "d1", "m1", "2.0.0", 1);
        registerDevice("rd2", "d2", "m1", "2.0.0", 2);

        // 未演练：正式投放创建被门禁拦截，422 且差额信息指向缺失门禁
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rr0","operator":"alice","model":"m1","fromVersion":"1.0.0",
                "toVersion":"2.0.0","ratio":10}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GATE_NOT_READY"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("READY_FOR_RELEASE")));

        // 开始演练：入参乱序，规范化后按设备ID字典序排序
        MvcResult started = startSuccess("rs1", "alice", "m1", "2.0.0", "1.0.0", "[\"d2\",\"d1\"]");
        long rehearsalId = idOf(started, "$.rehearsalId");
        org.assertj.core.api.Assertions.assertThat(started.getResponse().getContentAsString())
                .contains("\"status\":\"RUNNING\"")
                .contains("\"requiredDeviceCount\":2")
                .contains("\"actualSuccessCount\":0")
                .contains("\"actualFailedCount\":0")
                .contains("\"missingCount\":2")
                .contains("\"endedAtUtc\":null")
                .contains("\"durationSeconds\":null");
        org.assertj.core.api.Assertions.assertThat((String)
                com.jayway.jsonpath.JsonPath.read(started.getResponse().getContentAsString(),
                        "$.devices[0].deviceId")).isEqualTo("d1");
        org.assertj.core.api.Assertions.assertThat((String)
                com.jayway.jsonpath.JsonPath.read(started.getResponse().getContentAsString(),
                        "$.devices[1].deviceId")).isEqualTo("d2");
        org.assertj.core.api.Assertions.assertThat((Object)
                com.jayway.jsonpath.JsonPath.read(started.getResponse().getContentAsString(),
                        "$.devices[0].result")).isNull();
        org.assertj.core.api.Assertions.assertThat((String)
                com.jayway.jsonpath.JsonPath.read(started.getResponse().getContentAsString(),
                        "$.startedAtUtc")).endsWith("Z");

        // 提交完整集合且全部成功
        MvcResult finished = mockMvc.perform(
                        post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                                .content("""
                                        {"requestId":"rsub1","operator":"alice","results":[
                                        {"deviceId":"d2","result":"SUCCESS"},
                                        {"deviceId":"d1","result":"SUCCESS"}]}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.requiredDeviceCount").value(2))
                .andExpect(jsonPath("$.actualSuccessCount").value(2))
                .andExpect(jsonPath("$.actualFailedCount").value(0))
                .andExpect(jsonPath("$.missingCount").value(0))
                .andExpect(jsonPath("$.devices[0].deviceId").value("d1"))
                .andExpect(jsonPath("$.devices[0].result").value("SUCCESS"))
                .andExpect(jsonPath("$.devices[1].result").value("SUCCESS"))
                .andExpect(jsonPath("$.endedAtUtc").isString())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat((Object)
                com.jayway.jsonpath.JsonPath.read(finished.getResponse().getContentAsString(),
                        "$.durationSeconds")).isNotNull();
        String endedAt = com.jayway.jsonpath.JsonPath.read(finished.getResponse().getContentAsString(),
                "$.endedAtUtc");
        assertThat(endedAt).endsWith("Z");

        // 门禁诊断：READY_FOR_RELEASE，要求数=实际成功数，证据指向演练ID
        mockMvc.perform(get("/api/rehearsals/gates").param("model", "m1").param("targetVersion", "2.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("READY_FOR_RELEASE"))
                .andExpect(jsonPath("$[0].rehearsalId").value(rehearsalId))
                .andExpect(jsonPath("$[0].requiredDeviceCount").value(2))
                .andExpect(jsonPath("$[0].actualSuccessCount").value(2));

        // 取得门禁后创建正式投放成功
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rr1","operator":"alice","model":"m1","fromVersion":"1.0.0",
                "toVersion":"2.0.0","ratio":10}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.toVersion").value("2.0.0"));

        // 新的发布版本 3.0.0 未演练：必须重新演练，门禁不通用
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rr2","operator":"alice","model":"m1","fromVersion":"2.0.0",
                "toVersion":"3.0.0","ratio":10}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GATE_NOT_READY"));
    }

    @Test
    void 开始演练_空集合_重复集合_版本不相容_设备异常分别422或409() throws Exception {
        registerDevice("rd1", "d1", "m1", "2.0.0", 1);
        registerDevice("rd2", "d2", "m2", "2.0.0", 1);
        registerDevice("rd3", "d3", "m1", "9.9.9", 1);

        // 空集合：422，不产生演练
        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"re1","operator":"alice","model":"m1","targetVersion":"2.0.0",
                "rollbackVersion":"1.0.0","deviceIds":[]}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REHEARSAL_EMPTY_DEVICES"));

        // 重复设备：422，实际值/去重值可见
        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"re2","operator":"alice","model":"m1","targetVersion":"2.0.0",
                "rollbackVersion":"1.0.0","deviceIds":["d1","d1"]}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REHEARSAL_DUPLICATE_DEVICES"));

        // 设备未登记：422
        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"re3","operator":"alice","model":"m1","targetVersion":"2.0.0",
                "rollbackVersion":"1.0.0","deviceIds":["ghost"]}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REHEARSAL_DEVICE_NOT_FOUND"));

        // 型号不一致：422
        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"re4","operator":"alice","model":"m1","targetVersion":"2.0.0",
                "rollbackVersion":"1.0.0","deviceIds":["d2"]}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REHEARSAL_DEVICE_MODEL_MISMATCH"));

        // 设备当前版本不等于目标版本：422
        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"re5","operator":"alice","model":"m1","targetVersion":"2.0.0",
                "rollbackVersion":"1.0.0","deviceIds":["d3"]}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REHEARSAL_VERSION_INCOMPATIBLE"));

        // 回退版本与目标版本相同：422
        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"re6","operator":"alice","model":"m1","targetVersion":"2.0.0",
                "rollbackVersion":"2.0.0","deviceIds":["d1"]}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REHEARSAL_VERSION_INCOMPATIBLE"));

        // 同型号同目标版本已有 RUNNING 演练：第二次开始 409
        startSuccess("re7", "alice", "m1", "2.0.0", "1.0.0", "[\"d1\"]");
        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"re8","operator":"bob","model":"m1","targetVersion":"2.0.0",
                "rollbackVersion":"1.5.0","deviceIds":["d1"]}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REHEARSAL_ALREADY_RUNNING"));

        // 所有失败均不产生半成品：仅 re7 一条 RUNNING 演练，设备行结果仍为 NULL
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_rehearsal", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollback_rehearsal_device WHERE result IS NOT NULL", Long.class)).isZero();
    }

    @Test
    void 已有正式任务的设备不能演练_返回409() throws Exception {
        seedGate("m1", "2.0.0");
        registerDevice("rd1", "d1", "m1", "1.0.0", 1);
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rr1","operator":"alice","model":"m1","fromVersion":"1.0.0",
                "toVersion":"2.0.0","ratio":100}
                """)).andExpect(status().isOk());
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp1\",\"operator\":\"alice\"}"))
                .andExpect(status().isOk()).andReturn();
        long taskId = idOf(pull, "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"rc1\",\"operator\":\"device\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());

        // 设备已完成正式投放（版本已到 2.0.0），不能再作为演练规范设备
        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"rx1","operator":"alice","model":"m1","targetVersion":"2.0.0",
                "rollbackVersion":"1.0.0","deviceIds":["d1"]}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_HAS_FORMAL_TASK"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_rehearsal", Long.class)).isZero();
    }

    @Test
    void 结果提交_集合不一致或非法422_不留半成品_失败不占键可重新提交() throws Exception {
        registerDevice("rd1", "d1", "m1", "2.0.0", 1);
        registerDevice("rd2", "d2", "m1", "2.0.0", 2);
        long rehearsalId = idOf(startSuccess("rs1", "alice", "m1", "2.0.0", "1.0.0", "[\"d1\",\"d2\"]"),
                "$.rehearsalId");

        // 少一台：422，响应给出要求值/实际值/差额，所有设备行仍为 NULL
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rf1","operator":"alice","results":[
                                {"deviceId":"d1","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESULT_SET_MISMATCH"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("差额")));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollback_rehearsal_device WHERE result IS NOT NULL", Long.class)).isZero();

        // 多一台未知设备：422
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rf2","operator":"alice","results":[
                                {"deviceId":"d1","result":"SUCCESS"},
                                {"deviceId":"d2","result":"SUCCESS"},
                                {"deviceId":"x9","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESULT_SET_MISMATCH"));

        // 结果集合内重复：422
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rf3","operator":"alice","results":[
                                {"deviceId":"d1","result":"SUCCESS"},
                                {"deviceId":"d1","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESULT_DEVICE_DUPLICATE"));

        // 非法结果枚举：422
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rf4","operator":"alice","results":[
                                {"deviceId":"d1","result":"SUCCESS"},
                                {"deviceId":"d2","result":"BOGUS"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REHEARSAL_INVALID_RESULT"));

        // 空结果集合：422
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("{\"requestId\":\"rf5\",\"operator\":\"alice\",\"results\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESULT_SET_MISMATCH"));

        // 一台失败：422，给出实际成功数/要求数/差额；整批驳回不产生部分 READY
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rf6","operator":"alice","results":[
                                {"deviceId":"d1","result":"SUCCESS"},
                                {"deviceId":"d2","result":"FAILED"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REHEARSAL_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("差额 1")));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM rollback_rehearsal WHERE id = ?", String.class, rehearsalId))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isZero();

        // 失败不占键：同一 requestId 改为完整成功集合后成功
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rf6","operator":"alice","results":[
                                {"deviceId":"d1","result":"SUCCESS"},
                                {"deviceId":"d2","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isEqualTo(1);
    }

    @Test
    void 幂等_同参重放首次快照_异参与异操作者409_集合顺序归一() throws Exception {
        registerDevice("rd1", "d1", "m1", "2.0.0", 1);
        registerDevice("rd2", "d2", "m1", "2.0.0", 2);

        String bodyA = """
                {"requestId":"rid-s","operator":"alice","model":"m1","targetVersion":"2.0.0",
                "rollbackVersion":"1.0.0","deviceIds":["d1","d2"]}
                """;
        MvcResult first = startRehearsal(bodyA);
        long rehearsalId = idOf(first, "$.rehearsalId");

        // 同参重放：字节级一致的首次响应快照
        MvcResult replay = startRehearsal(bodyA);
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_rehearsal", Long.class)).isEqualTo(1);

        // 集合仅顺序不同，视为同一规范化集合：重放同快照
        MvcResult replayReordered = startRehearsal("""
                {"requestId":"rid-s","operator":"alice","model":"m1","targetVersion":"2.0.0",
                "rollbackVersion":"1.0.0","deviceIds":["d2","d1"]}
                """);
        assertThat(replayReordered.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 同 requestId 异参：409
        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"rid-s","operator":"alice","model":"m1","targetVersion":"2.0.0",
                "rollbackVersion":"1.5.0","deviceIds":["d1","d2"]}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 同 requestId 异操作者：指纹含操作者，409
        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"rid-s","operator":"bob","model":"m1","targetVersion":"2.0.0",
                "rollbackVersion":"1.0.0","deviceIds":["d1","d2"]}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 提交结果同参重放：首次快照，成功数不重复累计
        String resultBody = """
                {"requestId":"rid-r","operator":"alice","results":[
                {"deviceId":"d1","result":"SUCCESS"},
                {"deviceId":"d2","result":"SUCCESS"}]}
                """;
        MvcResult submitted = mockMvc.perform(
                        post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                                .content(resultBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult replayedResult = mockMvc.perform(
                        post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                                .content(resultBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(replayedResult.getResponse().getContentAsString())
                .isEqualTo(submitted.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isEqualTo(1);

        // 演练已终结：不同 requestId 再提交 409
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rid-again","operator":"alice","results":[
                                {"deviceId":"d1","result":"SUCCESS"},
                                {"deviceId":"d2","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REHEARSAL_FINISHED"));

        // 操作者与发起人不一致：409
        registerDevice("rd3", "d3", "m1", "4.0.0", 3);
        long otherId = idOf(startSuccess("rid-o", "alice", "m1", "4.0.0", "3.0.0", "[\"d3\"]"),
                "$.rehearsalId");
        mockMvc.perform(post("/api/rehearsals/" + otherId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rid-bob","operator":"bob","results":[
                                {"deviceId":"d3","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REHEARSAL_OPERATOR_CONFLICT"));

        // 不存在演练：404
        mockMvc.perform(post("/api/rehearsals/9999/results").contentType("application/json")
                        .content("{\"requestId\":\"rid-404\",\"operator\":\"alice\",\"results\":[]}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/rehearsals/9999")).andExpect(status().isNotFound());
    }

    @Test
    void 演练证据不可变_设备后续变化不改写历史_明细历史排序稳定() throws Exception {
        registerDevice("rd1", "d1", "m1", "2.0.0", 1);
        registerDevice("rd2", "d2", "m1", "2.0.0", 2);
        registerDevice("rd3", "d3", "m1", "4.0.0", 3);

        long firstId = idOf(startSuccess("rs1", "alice", "m1", "2.0.0", "1.0.0", "[\"d2\",\"d1\"]"),
                "$.rehearsalId");
        mockMvc.perform(post("/api/rehearsals/" + firstId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rr1","operator":"alice","results":[
                                {"deviceId":"d2","result":"SUCCESS"},
                                {"deviceId":"d1","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        String firstSnapshot = mockMvc.perform(get("/api/rehearsals/" + firstId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // 第二场演练（另一目标版本），验证历史稳定排序：按开始时刻、再按ID
        long secondId = idOf(startSuccess("rs2", "alice", "m1", "4.0.0", "3.0.0", "[\"d3\"]"),
                "$.rehearsalId");
        mockMvc.perform(post("/api/rehearsals/" + secondId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rr2","operator":"alice","results":[
                                {"deviceId":"d3","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isOk());

        // 后续设备状态变化（直接模拟设备被别的流程改写版本），演练证据不变
        jdbc.update("UPDATE device SET current_version = '1.0.0' WHERE device_id = 'd1'");
        String afterChange = mockMvc.perform(get("/api/rehearsals/" + firstId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(afterChange).isEqualTo(firstSnapshot);

        // 历史按ID（同精度开始时刻）稳定排序，明细设备按 ordinal 排序
        mockMvc.perform(get("/api/rehearsals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rehearsals.length()").value(2))
                .andExpect(jsonPath("$.rehearsals[0].rehearsalId").value(firstId))
                .andExpect(jsonPath("$.rehearsals[1].rehearsalId").value(secondId));
        mockMvc.perform(get("/api/rehearsals").param("model", "m1").param("targetVersion", "4.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rehearsals.length()").value(1))
                .andExpect(jsonPath("$.rehearsals[0].rehearsalId").value(secondId));
        mockMvc.perform(get("/api/rehearsals").param("targetVersion", "2.0.0"))
                .andExpect(jsonPath("$.rehearsals.length()").value(1));
    }
}
