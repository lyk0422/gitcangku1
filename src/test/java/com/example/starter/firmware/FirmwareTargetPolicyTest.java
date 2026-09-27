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
 * 固件目标标签策略 API 集成测试（真实 H2 内存库，MODE=MySQL）：
 * 快照一次性解析不追溯、三类表达式 422、批量整批事务与数量差额、幂等、只读诊断与左闭右开区间。
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
        jdbc.update("DELETE FROM release_target");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_tag");
        jdbc.update("DELETE FROM tag_revision");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("UPDATE tag_dict_meta SET version = 0 WHERE id = 1");
    }

    private void registerDevice(String deviceId, String model, String version, int bucket) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"reg-%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(deviceId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    private MvcResult batchTags(String requestId, String operator, String bodyItems) throws Exception {
        return mockMvc.perform(post("/api/devices/tags/batch").contentType("application/json").content("""
                {"requestId":"%s","operator":"%s","items":[%s]}
                """.formatted(requestId, operator, bodyItems)))
                .andReturn();
    }

    private String item(String deviceId, String... tags) {
        String tagArray = java.util.Arrays.stream(tags)
                .map(t -> "\"" + t + "\"").reduce((a, b) -> a + "," + b).orElse("");
        return "{\"deviceId\":\"%s\",\"tags\":[%s]}".formatted(deviceId, tagArray);
    }

    private long createTargetedRelease(String requestId, String model, int ratio, String expressionJson)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","operator":"alice","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0",
                "ratio":%d,"target":%s}
                """.formatted(requestId, model, ratio, expressionJson)))
                .andExpect(status().isOk())
                .andReturn();
        Number releaseId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(),
                "$.releaseId");
        return releaseId.longValue();
    }

    @Test
    void 主流程_标签规范化_快照成员命中_表达式三类条件生效() throws Exception {
        registerDevice("d1", "m1", "1.0.0", 1);
        registerDevice("d2", "m1", "1.0.0", 2);
        registerDevice("d3", "m1", "1.0.0", 3);
        registerDevice("d4", "m1", "1.0.0", 4);
        // d1: beta+cn；d2: beta+us；d3: beta+canary+cn；d4: 仅有 cn 不满足必须包含 beta
        MvcResult batch = batchTags("b1", "alice",
                item("d1", "beta", "cn") + "," + item("d2", "beta", "us")
                        + "," + item("d3", "beta", "cn", "canary") + "," + item("d4", "cn"));
        assertThat(batch.getResponse().getStatus()).isEqualTo(200);

        long releaseId = createTargetedRelease("r1", "m1", 100,
                "{\"include\":[\"beta\"],\"exclude\":[\"canary\"],\"anyOf\":[\"cn\",\"us\"]}");

        // 快照仅 d1、d2（d3 命中排除、d4 缺必须包含），规范按 device_id 排序
        mockMvc.perform(get("/api/releases/" + releaseId + "/target"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.tagDictVersion").value(1))
                .andExpect(jsonPath("$.targetSize").value(2))
                .andExpect(jsonPath("$.deviceIds[0]").value("d1"))
                .andExpect(jsonPath("$.deviceIds[1]").value("d2"))
                .andExpect(jsonPath("$.expression.include[0]").value("beta"))
                .andExpect(jsonPath("$.expression.exclude[0]").value("canary"))
                .andExpect(jsonPath("$.expression.anyOf[0]").value("cn"))
                .andExpect(jsonPath("$.expression.anyOf[1]").value("us"))
                .andExpect(jsonPath("$.resolvedAtUtc").isString());

        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task.status").value("PENDING"));
        // 非快照成员 d3、d4 不下发
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"p3\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task").doesNotExist());
        mockMvc.perform(post("/api/devices/d4/pull").contentType("application/json")
                        .content("{\"requestId\":\"p4\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task").doesNotExist());
    }

    @Test
    void 快照不追溯_创建后标签变更既不扩大也不缩小_已下发任务不受影响() throws Exception {
        registerDevice("d1", "m1", "1.0.0", 1);
        registerDevice("d2", "m1", "1.0.0", 2);
        batchTags("b1", "alice", item("d1", "beta"));
        long releaseId = createTargetedRelease("r1", "m1", 100, "{\"include\":[\"beta\"]}");
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(status().isOk()).andReturn();
        Number taskIdValue = com.jayway.jsonpath.JsonPath.read(pull.getResponse().getContentAsString(),
                "$.task.taskId");
        long taskId = taskIdValue.longValue();

        // 创建后：给 d2 加 beta（扩大候选），清空 d1 标签（缩小候选）——快照成员不变
        batchTags("b2", "bob", item("d2", "beta") + "," + item("d1"));

        // d2 当时不在快照，标签补加后仍不下发（不扩大）
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task").doesNotExist());
        // d1 标签已清空，但仍是快照成员，重复拉取返回原任务（不缩小、已下发任务不受批量修改影响）
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(taskId))
                .andExpect(jsonPath("$.task.status").value("PENDING"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/target"))
                .andExpect(jsonPath("$.targetSize").value(1))
                .andExpect(jsonPath("$.tagDictVersion").value(1))
                .andExpect(jsonPath("$.deviceIds[0]").value("d1"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);
    }

    @Test
    void 快照成员仍受版本与分桶约束() throws Exception {
        registerDevice("d1", "m1", "9.9.9", 1);
        registerDevice("d2", "m1", "1.0.0", 50);
        registerDevice("d3", "m1", "1.0.0", 5);
        batchTags("b1", "alice", item("d1", "beta") + "," + item("d2", "beta") + "," + item("d3", "beta"));
        long releaseId = createTargetedRelease("r1", "m1", 10, "{\"include\":[\"beta\"]}");
        mockMvc.perform(get("/api/releases/" + releaseId + "/target"))
                .andExpect(jsonPath("$.targetSize").value(3));
        // 快照成员但当前版本不匹配
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(jsonPath("$.task").doesNotExist());
        // 快照成员但分桶 50 不在 [0,10)
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}"))
                .andExpect(jsonPath("$.task").doesNotExist());
        // 全部满足
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"p3\"}"))
                .andExpect(jsonPath("$.task.status").value("PENDING"));
    }

    @Test
    void 表达式非法_重复_空条件_包含排除冲突_空快照_均422_空快照不占键() throws Exception {
        registerDevice("d1", "m1", "1.0.0", 1);
        // 组内重复标签
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"e1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "target":{"include":["beta"," beta "]}}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TAG_DUPLICATE"));
        // 空条件
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"e2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "target":{"include":[],"exclude":[],"anyOf":[]}}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TARGET_EXPRESSION_EMPTY"));
        // 同标签同时包含与排除
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"e3","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "target":{"include":["beta"],"exclude":["beta"]}}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TAG_INCLUDE_EXCLUDE_CONFLICT"));
        // 空快照：无任何 beta 设备
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"e4","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "target":{"include":["nonexistent"]}}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TARGET_EMPTY"));
        // 空快照不留半成品且不占键：同一 requestId 改合法表达式后成功
        mockMvc.perform(post("/api/devices/tags/batch").contentType("application/json")
                .content("""
                        {"requestId":"b0","operator":"alice","items":[{"deviceId":"d1","tags":["beta"]}]}
                        """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"e4","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "target":{"include":["beta"]}}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_target", Long.class)).isEqualTo(1);
    }

    @Test
    void 批量标签_数量实际要求差额_清空标签_字典版本与修订历史() throws Exception {
        registerDevice("d1", "m1", "1.0.0", 1);
        registerDevice("d2", "m1", "1.0.0", 2);
        MvcResult first = batchTags("b1", "alice", item("d1", "beta") + "," + item("d2", "beta"));

        String body = first.getResponse().getContentAsString();
        com.jayway.jsonpath.DocumentContext json = com.jayway.jsonpath.JsonPath.parse(body);
        assertThat(json.read("$.dictVersion", Long.class)).isEqualTo(1L);
        assertThat(json.read("$.requestedCount", Integer.class)).isEqualTo(2);
        assertThat(json.read("$.changedCount", Integer.class)).isEqualTo(2);
        assertThat(json.read("$.unchangedCount", Integer.class)).isEqualTo(0);

        // 同参再批一次：要求2、实际变化0、差额2，字典版本仍递增（每次提交都是一次修订）
        MvcResult replay = batchTags("b2", "alice", item("d1", "beta") + "," + item("d2", "beta"));
        com.jayway.jsonpath.DocumentContext replayJson =
                com.jayway.jsonpath.JsonPath.parse(replay.getResponse().getContentAsString());
        assertThat(replayJson.read("$.dictVersion", Long.class)).isEqualTo(2L);
        assertThat(replayJson.read("$.changedCount", Integer.class)).isZero();
        assertThat(replayJson.read("$.unchangedCount", Integer.class)).isEqualTo(2);

        // 清空 d1 标签（空列表合法）
        batchTags("b3", "bob", item("d1"));
        mockMvc.perform(get("/api/devices/d1/tags")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(0));
        mockMvc.perform(get("/api/devices/d2/tags")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tags[0]").value("beta"));
        mockMvc.perform(get("/api/tags/dict")).andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void 批量标签_任一失败整批回滚不留半成品_原因可区分_失败不占键() throws Exception {
        registerDevice("d1", "m1", "1.0.0", 1);
        batchTags("b0", "alice", item("d1", "beta"));
        long versionBefore = jdbc.queryForObject("SELECT version FROM tag_dict_meta WHERE id=1", Long.class);

        // 含不存在设备：404，d1 标签与字典版本、修订历史均不变
        mockMvc.perform(post("/api/devices/tags/batch").contentType("application/json").content("""
                {"requestId":"x1","operator":"alice","items":[
                  {"deviceId":"d1","tags":["gamma"]},{"deviceId":"ghost","tags":["gamma"]}]}
                """)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("批量标签修改失败，设备不存在: ghost"));
        // 非法标签：422
        mockMvc.perform(post("/api/devices/tags/batch").contentType("application/json").content("""
                {"requestId":"x2","operator":"alice","items":[{"deviceId":"d1","tags":["a b"]}]}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TAG_INVALID"));
        // 同设备重复成项：422
        mockMvc.perform(post("/api/devices/tags/batch").contentType("application/json").content("""
                {"requestId":"x3","operator":"alice","items":[
                  {"deviceId":"d1","tags":["gamma"]},{"deviceId":"d1","tags":["delta"]}]}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TAG_BATCH_DUPLICATE_DEVICE"));
        // tags 为 null：400 校验错误
        mockMvc.perform(post("/api/devices/tags/batch").contentType("application/json").content("""
                {"requestId":"x4","operator":"alice","items":[{"deviceId":"d1"}]}
                """)).andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT version FROM tag_dict_meta WHERE id=1", Long.class))
                .isEqualTo(versionBefore);
        assertThat(jdbc.queryForList("SELECT tag FROM device_tag WHERE device_id='d1'", String.class))
                .containsExactly("beta");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_revision", Long.class)).isEqualTo(1);
        // 失败不占键：x2 换合法标签后成功
        mockMvc.perform(post("/api/devices/tags/batch").contentType("application/json").content("""
                {"requestId":"x2","operator":"alice","items":[{"deviceId":"d1","tags":["gamma"]}]}
                """)).andExpect(status().isOk()).andExpect(jsonPath("$.dictVersion").value(2));
    }

    @Test
    void 幂等_批量与发布创建_同参重放首次快照_异参409() throws Exception {
        registerDevice("d1", "m1", "1.0.0", 1);
        registerDevice("d2", "m1", "1.0.0", 2);
        MvcResult first = batchTags("same", "alice", item("d1", "beta") + "," + item("d2", "beta"));
        MvcResult replay = batchTags("same", "alice", item("d1", "beta") + "," + item("d2", "beta"));
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        // 重放不重复执行：字典版本只增加一次，修订历史只有一条
        mockMvc.perform(get("/api/tags/dict")).andExpect(jsonPath("$.version").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_revision", Long.class)).isEqualTo(1);
        // 同键异参（操作者不同）：409
        mockMvc.perform(post("/api/devices/tags/batch").contentType("application/json").content("""
                {"requestId":"same","operator":"bob","items":[{"deviceId":"d1","tags":["beta"]}]}
                """)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        long releaseId = createTargetedRelease("rsame", "m1", 100, "{\"include\":[\"beta\"]}");
        String firstRelease = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rsame","operator":"alice","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                "ratio":100,"target":{"include":["beta"]}}
                """)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        com.jayway.jsonpath.DocumentContext ctx = com.jayway.jsonpath.JsonPath.parse(firstRelease);
        assertThat(ctx.read("$.releaseId", Long.class)).isEqualTo(releaseId);
        // 同键异参（表达式不同）：409
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rsame","operator":"alice","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                "ratio":100,"target":{"include":["gamma"]}}
                """)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 同键异参（操作者不同）：409
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rsame","operator":"bob","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                "ratio":100,"target":{"include":["beta"]}}
                """)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isEqualTo(1);
    }

    @Test
    void 修订历史_只读_左闭右开_UTC时刻() throws Exception {
        registerDevice("d1", "m1", "1.0.0", 1);
        batchTags("h1", "alice", item("d1", "beta"));
        MvcResult listResult = mockMvc.perform(get("/api/tags/revisions"))
                .andExpect(status().isOk()).andReturn();
        String changedAt = com.jayway.jsonpath.JsonPath.read(
                listResult.getResponse().getContentAsString(), "$[0].changedAtUtc");
        assertThat(changedAt).endsWith("Z");
        mockMvc.perform(get("/api/tags/revisions"))
                .andExpect(jsonPath("$[0].operator").value("alice"))
                .andExpect(jsonPath("$[0].requestedCount").value(1))
                .andExpect(jsonPath("$[0].changedCount").value(1))
                .andExpect(jsonPath("$[0].unchangedCount").value(0));
        // 左闭：from 等于修订时刻，包含该条
        mockMvc.perform(get("/api/tags/revisions").param("fromUtc", changedAt))
                .andExpect(jsonPath("$.length()").value(1));
        // 右开：to 等于修订时刻，排除该条
        mockMvc.perform(get("/api/tags/revisions").param("toUtc", changedAt))
                .andExpect(jsonPath("$.length()").value(0));
        // 未来上界开区间仍包含；from 晚于修订时刻则为空
        mockMvc.perform(get("/api/tags/revisions").param("fromUtc", "2099-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.length()").value(0));
        // 非法时刻：400；from >= to：400
        mockMvc.perform(get("/api/tags/revisions").param("fromUtc", "not-a-time"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TIME"));
        mockMvc.perform(get("/api/tags/revisions")
                        .param("fromUtc", "2026-01-02T00:00:00Z").param("toUtc", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
    }

    @Test
    void 旧发布单未启用目标策略_诊断enabled为false_行为保持动态匹配() throws Exception {
        registerDevice("d1", "m1", "1.0.0", 1);
        MvcResult created = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r0","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.target").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tagDictVersion").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        long releaseId = new java.math.BigDecimal(
                com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(),
                        "$.releaseId").toString()).longValueExact();
        mockMvc.perform(get("/api/releases/" + releaseId + "/target"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.deviceIds.length()").value(0));
        // 无标签设备对旧发布单仍按型号动态下发
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task.status").value("PENDING"));
    }
}
