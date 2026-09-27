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
 * 固件目标标签策略与投放目标快照 API 测试（H2 内存库，MODE=MySQL）：
 * 主流程、422 失败分支、快照不追溯、批量标签修改事务与幂等、历史区间查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareTargetPolicyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_target_snapshot");
        jdbc.update("DELETE FROM release_target_policy");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_tag");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM tag_change_record");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("UPDATE tag_dictionary SET version = 0 WHERE id = 1");
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDevice(String requestId, String deviceId, String model, int bucket,
                                String tagsJson) throws Exception {
        String tagsField = tagsJson == null ? "" : ",\"tags\":" + tagsJson;
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":%d%s}
                """.formatted(requestId, deviceId, model, bucket, tagsField)))
                .andExpect(status().isOk());
    }

    private MvcResult createRelease(String requestId, String model, int ratio, String policyJson)
            throws Exception {
        String policyField = policyJson == null ? "" : ",\"targetPolicy\":" + policyJson;
        return mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d%s}
                        """.formatted(requestId, model, ratio, policyField)))
                .andReturn();
    }

    private long createReleaseOk(String requestId, String model, int ratio, String policyJson)
            throws Exception {
        MvcResult result = createRelease(requestId, model, ratio, policyJson);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return idOf(result, "$.releaseId");
    }

    private MvcResult pull(String requestId, String deviceId) throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private MvcResult batchUpdate(String requestId, String operator, String updatesJson)
            throws Exception {
        return mockMvc.perform(post("/api/devices/tags/batch").contentType("application/json")
                        .content("""
                                {"requestId":"%s","operator":"%s","updates":%s}
                                """.formatted(requestId, operator, updatesJson)))
                .andReturn();
    }

    private long dictVersion() {
        Long version = jdbc.queryForObject("SELECT version FROM tag_dictionary WHERE id = 1", Long.class);
        return version == null ? -1 : version;
    }

    @Test
    void 主流程_按表达式解析快照_仅快照成员可拉取_明细与诊断() throws Exception {
        registerDevice("r1", "d1", "m1", 1, "[\"edge\",\"cn\"]");
        registerDevice("r2", "d2", "m1", 2, "[\"edge\",\"us\"]");
        registerDevice("r3", "d3", "m1", 3, "[\"edge\",\"banned\",\"cn\"]");
        registerDevice("r4", "d4", "m1", 4, "[\"cn\"]");
        registerDevice("r5", "d5", "m1", 5, null);

        long releaseId = createReleaseOk("r10", "m1", 100, """
                {"includeAll":["Edge"],"excludeAny":["banned"],"includeAny":["cn","us"]}
                """);

        // 明细：表达式规范化固化、字典版本、解析时刻、规范排序快照
        mockMvc.perform(get("/api/releases/" + releaseId + "/target"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.includeAll[0]").value("edge"))
                .andExpect(jsonPath("$.excludeAny[0]").value("banned"))
                .andExpect(jsonPath("$.includeAny.length()").value(2))
                .andExpect(jsonPath("$.tagDictVersion").value(5))
                .andExpect(jsonPath("$.resolvedAtUtc").isNotEmpty())
                .andExpect(jsonPath("$.targetCount").value(2))
                .andExpect(jsonPath("$.devices[0]").value("d1"))
                .andExpect(jsonPath("$.devices[1]").value("d2"))
                .andExpect(jsonPath("$.devices.length()").value(2));

        // 快照成员可拉取；非成员（被排除、缺标签、无标签）不下发
        MvcResult pull1 = pull("p1", "d1");
        long taskId = idOf(pull1, "$.task.taskId");
        pull("p2", "d2");
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"p3\"}"))
                .andExpect(jsonPath("$.task").doesNotExist());
        mockMvc.perform(post("/api/devices/d4/pull").contentType("application/json")
                        .content("{\"requestId\":\"p4\"}"))
                .andExpect(jsonPath("$.task").doesNotExist());
        mockMvc.perform(post("/api/devices/d5/pull").contentType("application/json")
                        .content("{\"requestId\":\"p5\"}"))
                .andExpect(jsonPath("$.task").doesNotExist());

        // 诊断：实际值与差额
        mockMvc.perform(get("/api/releases/" + releaseId + "/target/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetCount").value(2))
                .andExpect(jsonPath("$.pulledCount").value(2))
                .andExpect(jsonPath("$.notPulledCount").value(0))
                .andExpect(jsonPath("$.pendingCount").value(2))
                .andExpect(jsonPath("$.successCount").value(0));

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/releases/" + releaseId + "/target/diagnostics"))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.pendingCount").value(1));
    }

    @Test
    void 快照不追溯_标签变更不扩大不缩小_新发布单使用新标签状态() throws Exception {
        registerDevice("r1", "d1", "m1", 1, "[\"edge\"]");
        registerDevice("r2", "d2", "m1", 2, "[\"other\"]");
        long releaseId = createReleaseOk("r3", "m1", 100, "{\"includeAll\":[\"edge\"]}");

        // 创建后标签修订：d1 移除 edge，d2 增加 edge
        mockMvc.perform(post("/api/devices/tags/batch").contentType("application/json").content("""
                {"requestId":"b1","operator":"op1","updates":[
                  {"deviceId":"d1","remove":["edge"]},
                  {"deviceId":"d2","add":["edge"]}]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dictVersion").value(3));

        // 快照不缩小：d1 仍是成员；不扩大：d2 仍不是成员
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(jsonPath("$.task.status").value("PENDING"));
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}"))
                .andExpect(jsonPath("$.task").doesNotExist());

        // 固化证据不被改写：仍是创建时的字典版本与快照
        mockMvc.perform(get("/api/releases/" + releaseId + "/target"))
                .andExpect(jsonPath("$.tagDictVersion").value(2))
                .andExpect(jsonPath("$.devices[0]").value("d1"))
                .andExpect(jsonPath("$.devices.length()").value(1));

        // 取消后新发布单按最新标签状态解析
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"x1\"}"))
                .andExpect(status().isOk());
        long release2 = createReleaseOk("r4", "m1", 100, "{\"includeAll\":[\"edge\"]}");
        mockMvc.perform(get("/api/releases/" + release2 + "/target"))
                .andExpect(jsonPath("$.tagDictVersion").value(3))
                .andExpect(jsonPath("$.devices[0]").value("d2"))
                .andExpect(jsonPath("$.devices.length()").value(1));
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p3\"}"))
                .andExpect(jsonPath("$.task.status").value("PENDING"));
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p4\"}"))
                .andExpect(jsonPath("$.task").doesNotExist());
    }

    @Test
    void 策略校验_重复_空条件_包含排除冲突_非法标签_均422且失败不占键() throws Exception {
        registerDevice("r0", "d1", "m1", 1, "[\"edge\"]");

        // 重复标签（规范化后）
        MvcResult dup = createRelease("v1", "m1", 100, "{\"includeAll\":[\"edge\",\"EDGE\"]}");
        assertThat(dup.getResponse().getStatus()).isEqualTo(422);
        assertThat(dup.getResponse().getContentAsString()).contains("DUPLICATE_TAG");

        // 空条件
        MvcResult emptyCond = createRelease("v2", "m1", 100, "{\"includeAll\":[]}");
        assertThat(emptyCond.getResponse().getStatus()).isEqualTo(422);
        assertThat(emptyCond.getResponse().getContentAsString()).contains("EMPTY_CONDITION");

        // 全空策略
        MvcResult emptyPolicy = createRelease("v3", "m1", 100, "{}");
        assertThat(emptyPolicy.getResponse().getStatus()).isEqualTo(422);
        assertThat(emptyPolicy.getResponse().getContentAsString()).contains("EMPTY_POLICY");

        // 同标签同时包含与排除（大小写不敏感）
        MvcResult conflict1 = createRelease("v4", "m1", 100,
                "{\"includeAll\":[\"a\"],\"excludeAny\":[\"A\"]}");
        assertThat(conflict1.getResponse().getStatus()).isEqualTo(422);
        assertThat(conflict1.getResponse().getContentAsString()).contains("TAG_INCLUDE_EXCLUDE_CONFLICT");

        MvcResult conflict2 = createRelease("v5", "m1", 100,
                "{\"includeAny\":[\"x\"],\"excludeAny\":[\"x\"]}");
        assertThat(conflict2.getResponse().getStatus()).isEqualTo(422);
        assertThat(conflict2.getResponse().getContentAsString()).contains("TAG_INCLUDE_EXCLUDE_CONFLICT");

        // 非法标签（去空白后为空）
        MvcResult invalid = createRelease("v6", "m1", 100, "{\"includeAll\":[\"   \"]}");
        assertThat(invalid.getResponse().getStatus()).isEqualTo(422);
        assertThat(invalid.getResponse().getContentAsString()).contains("INVALID_TAG");

        // 全部失败不产生发布单、不占幂等键（仅登记成功的 r0 占一条）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Long.class))
                .isEqualTo(1);

        // 同 requestId 以合法参数重试成功
        createReleaseOk("v1", "m1", 100, "{\"includeAll\":[\"edge\"]}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isEqualTo(1);
    }

    @Test
    void 快照为空_创建422_不残留发布单与策略记录() throws Exception {
        registerDevice("r1", "d1", "m1", 1, "[\"other\"]");

        MvcResult result = createRelease("r2", "m1", 100, "{\"includeAll\":[\"edge\"]}");
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("TARGET_SNAPSHOT_EMPTY");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_target_policy", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_target_snapshot", Long.class)).isZero();
        // 失败的创建不占幂等键（仅登记成功的 r1 占一条）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Long.class))
                .isEqualTo(1);

        // 注册匹配设备后同 requestId 重试成功
        registerDevice("r3", "d2", "m1", 2, "[\"edge\"]");
        long releaseId = createReleaseOk("r2", "m1", 100, "{\"includeAll\":[\"edge\"]}");
        mockMvc.perform(get("/api/releases/" + releaseId + "/target"))
                .andExpect(jsonPath("$.devices[0]").value("d2"));
    }

    @Test
    void 批量标签修改_成功_字典版本递增_历史记录与左闭右开区间过滤() throws Exception {
        registerDevice("r1", "d1", "m1", 1, "[\"a\"]");
        registerDevice("r2", "d2", "m1", 2, "[\"b\"]");
        assertThat(dictVersion()).isEqualTo(2);

        MvcResult result = mockMvc.perform(post("/api/devices/tags/batch")
                        .contentType("application/json").content("""
                        {"requestId":"b1","operator":"op1","updates":[
                          {"deviceId":"d2","add":["C"]},
                          {"deviceId":"d1","add":["c"],"remove":["a"]}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dictVersion").value(3))
                .andExpect(jsonPath("$.deviceCount").value(2))
                .andExpect(jsonPath("$.changedAtUtc").isNotEmpty())
                .andReturn();
        String changedAtUtc = com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.changedAtUtc");

        // 标签已生效（规范化、升序）
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.tags[0]").value("c"))
                .andExpect(jsonPath("$.tags.length()").value(1));
        mockMvc.perform(get("/api/devices/d2"))
                .andExpect(jsonPath("$.tags[0]").value("b"))
                .andExpect(jsonPath("$.tags[1]").value("c"));

        // 历史：按提交顺序，changes 按 deviceId 升序
        mockMvc.perform(get("/api/devices/tags/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].dictVersion").value(3))
                .andExpect(jsonPath("$.records[0].operator").value("op1"))
                .andExpect(jsonPath("$.records[0].deviceCount").value(2))
                .andExpect(jsonPath("$.records[0].changes[0].deviceId").value("d1"))
                .andExpect(jsonPath("$.records[0].changes[0].add[0]").value("c"))
                .andExpect(jsonPath("$.records[0].changes[0].remove[0]").value("a"))
                .andExpect(jsonPath("$.records[0].changes[1].deviceId").value("d2"))
                .andExpect(jsonPath("$.records[0].changedAtUtc").value(changedAtUtc));

        // 区间左闭右开：[changedAt, ∞) 含该记录；[∅, changedAt) 不含
        mockMvc.perform(get("/api/devices/tags/history").param("from", changedAtUtc))
                .andExpect(jsonPath("$.records.length()").value(1));
        mockMvc.perform(get("/api/devices/tags/history").param("to", changedAtUtc))
                .andExpect(jsonPath("$.records.length()").value(0));
        mockMvc.perform(get("/api/devices/tags/history")
                        .param("from", changedAtUtc).param("to", changedAtUtc))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mockMvc.perform(get("/api/devices/tags/history").param("from", "not-a-time"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME"));
    }

    @Test
    void 批量修改_设备不存在整批回滚_无半成品且失败不占键() throws Exception {
        registerDevice("r1", "d1", "m1", 1, "[\"a\"]");
        long versionBefore = dictVersion();

        MvcResult result = batchUpdate("b1", "op1", """
                [{"deviceId":"d1","add":["x"]},{"deviceId":"ghost","add":["y"]}]
                """);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("DEVICE_NOT_FOUND");

        // 无半成品：d1 标签未变、无历史记录、字典版本未推进、幂等键未占用
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.tags[0]").value("a"))
                .andExpect(jsonPath("$.tags.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_change_record", Long.class)).isZero();
        assertThat(dictVersion()).isEqualTo(versionBefore);
        // 失败的批量修改不占幂等键（仅登记成功的 r1 占一条）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record", Long.class))
                .isEqualTo(1);

        // 同 requestId 修正参数后成功
        mockMvc.perform(post("/api/devices/tags/batch").contentType("application/json").content("""
                {"requestId":"b1","operator":"op1","updates":[{"deviceId":"d1","add":["x"]}]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dictVersion").value(versionBefore + 1));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.tags.length()").value(2));
    }

    @Test
    void 批量修改_重复设备_空变更_增删交集_重复标签_均422() throws Exception {
        registerDevice("r1", "d1", "m1", 1, "[\"a\"]");

        MvcResult dupDevice = batchUpdate("c1", "op1", """
                [{"deviceId":"d1","add":["x"]},{"deviceId":"d1","add":["y"]}]
                """);
        assertThat(dupDevice.getResponse().getStatus()).isEqualTo(422);
        assertThat(dupDevice.getResponse().getContentAsString()).contains("DUPLICATE_DEVICE");

        MvcResult emptyChange = batchUpdate("c2", "op1", """
                [{"deviceId":"d1"}]
                """);
        assertThat(emptyChange.getResponse().getStatus()).isEqualTo(422);
        assertThat(emptyChange.getResponse().getContentAsString()).contains("EMPTY_TAG_CHANGE");

        MvcResult addRemove = batchUpdate("c3", "op1", """
                [{"deviceId":"d1","add":["x"],"remove":["X"]}]
                """);
        assertThat(addRemove.getResponse().getStatus()).isEqualTo(422);
        assertThat(addRemove.getResponse().getContentAsString()).contains("TAG_ADD_REMOVE_CONFLICT");

        MvcResult dupTag = batchUpdate("c4", "op1", """
                [{"deviceId":"d1","add":["x"," x "]}]
                """);
        assertThat(dupTag.getResponse().getStatus()).isEqualTo(422);
        assertThat(dupTag.getResponse().getContentAsString()).contains("DUPLICATE_TAG");

        // 空批次：Bean 校验 400
        mockMvc.perform(post("/api/devices/tags/batch").contentType("application/json")
                        .content("{\"requestId\":\"c5\",\"operator\":\"op1\",\"updates\":[]}"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_change_record", Long.class)).isZero();
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.tags[0]").value("a"))
                .andExpect(jsonPath("$.tags.length()").value(1));
    }

    @Test
    void 幂等_批量修改重放返回首次快照_异参409_副作用只发生一次() throws Exception {
        registerDevice("r1", "d1", "m1", 1, "[\"a\"]");

        String first = batchUpdate("b1", "op1", """
                [{"deviceId":"d1","add":["x"]}]
                """).getResponse().getContentAsString();
        assertThat(first).contains("\"dictVersion\":2");

        // 同键同参重放：返回首次完整响应快照，字典版本不再推进，历史不重复
        String replay = batchUpdate("b1", "op1", """
                [{"deviceId":"d1","add":["x"]}]
                """).getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(dictVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_change_record", Long.class)).isEqualTo(1);

        // 规范化等价（顺序不同、大小写空白不同）也视为同参重放
        String replayNormalized = batchUpdate("b1", "op1", """
                [{"deviceId":"d1","add":[" X "]}]
                """).getResponse().getContentAsString();
        assertThat(replayNormalized).isEqualTo(first);
        assertThat(dictVersion()).isEqualTo(2);

        // 同键异参：409
        MvcResult conflict = batchUpdate("b1", "op2", """
                [{"deviceId":"d1","add":["x"]}]
                """);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(conflict.getResponse().getContentAsString()).contains("REQUEST_ID_CONFLICT");
    }

    @Test
    void 注册_标签规范化与重复422_设备视图返回规范标签() throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r1","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "tags":[" Beta ","alpha"]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0]").value("alpha"))
                .andExpect(jsonPath("$.tags[1]").value("beta"));

        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.tags[0]").value("alpha"))
                .andExpect(jsonPath("$.tags[1]").value("beta"));

        // 重复标签 422，设备未创建，requestId 可复用
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r2","deviceId":"d2","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "tags":["a","A"]}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_TAG"));
        mockMvc.perform(get("/api/devices/d2"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r2","deviceId":"d2","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(0));
    }

    @Test
    void 已下发任务不受批量标签修改影响() throws Exception {
        registerDevice("r1", "d1", "m1", 1, "[\"edge\"]");
        long releaseId = createReleaseOk("r2", "m1", 100, "{\"includeAll\":[\"edge\"]}");
        MvcResult pull = pull("p1", "d1");
        long taskId = idOf(pull, "$.task.taskId");

        // 移除 d1 全部标签：已下发任务不取消、不回执受限
        mockMvc.perform(post("/api/devices/tags/batch").contentType("application/json").content("""
                {"requestId":"b1","operator":"op1","updates":[{"deviceId":"d1","remove":["edge"]}]}
                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].status").value("PENDING"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
    }

    @Test
    void 无策略发布单_目标明细与诊断404_拉取行为不变() throws Exception {
        registerDevice("r1", "d1", "m1", 1, null);
        long releaseId = createReleaseOk("r2", "m1", 100, null);

        mockMvc.perform(get("/api/releases/" + releaseId + "/target"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TARGET_POLICY_NOT_FOUND"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/target/diagnostics"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TARGET_POLICY_NOT_FOUND"));

        // 无策略时拉取行为与旧版一致
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(jsonPath("$.task.status").value("PENDING"));

        // 发布单不存在：404
        mockMvc.perform(get("/api/releases/9999/target"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_FOUND"));
    }
}
