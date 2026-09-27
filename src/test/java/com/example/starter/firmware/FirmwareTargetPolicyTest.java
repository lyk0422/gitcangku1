package com.example.starter.firmware;

import com.example.starter.firmware.api.BatchLabelUpdateRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.DeviceLabelsItem;
import com.example.starter.firmware.api.LabelTargetRequest;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.DeviceLabelService;
import com.example.starter.firmware.service.ReleaseService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固件目标策略 H2 集成测试：标签表达式解析、目标快照一次性固化、批量标签修改、
 * 标签字典版本与修订诊断、422 失败分支、事务原子性、真实并发裁决与幂等边界。
 * 时间全部由 TestClock 以 UTC 显式推进，不依赖真实睡眠。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareTargetPolicyTest {

    @TestConfiguration
    static class ClockTestConfiguration {
        @Bean
        @Primary
        TestClock testClock() {
            return new TestClock(Instant.parse("2026-09-28T10:00:00Z"));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestClock clock;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private DeviceLabelService deviceLabelService;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_target_device");
        jdbc.update("DELETE FROM release_target_snapshot");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM label_revision");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM label_dict_tag");
        jdbc.update("UPDATE label_dict_meta SET version = 0 WHERE id = 1");
        jdbc.update("DELETE FROM idempotency_record");
        clock.setInstant(Instant.parse("2026-09-28T10:00:00Z"));
    }

    private void register(String requestId, String deviceId, String model, int bucket, List<String> labels)
            throws Exception {
        register(requestId, deviceId, model, bucket, labels, null);
    }

    private void register(String requestId, String deviceId, String model, int bucket, List<String> labels,
                          String operator) throws Exception {
        String operatorJson = operator == null ? "" : ",\"operator\":\"" + operator + "\"";
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":%d,"labels":%s%s}
                """.formatted(requestId, deviceId, model, bucket, toJsonArray(labels), operatorJson)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, int ratio, LabelTargetRequest target)
            throws Exception {
        return createRelease(requestId, model, ratio, target, null);
    }

    private long createRelease(String requestId, String model, int ratio, LabelTargetRequest target,
                               String operator) throws Exception {
        String operatorJson = operator == null ? "" : ",\"operator\":\"" + operator + "\"";
        String targetJson = target == null ? "null" : """
                {"mustInclude":%s,"mustExclude":%s,"includeAny":%s}""".formatted(
                toJsonArray(target.mustInclude()), toJsonArray(target.mustExclude()),
                toJsonArray(target.includeAny()));
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d,"target":%s%s}
                """.formatted(requestId, model, ratio, targetJson, operatorJson)))
                .andExpect(status().isOk())
                .andReturn();
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.releaseId");
        return id.longValue();
    }

    private MvcResult pullExpectingStatus(String deviceId, String requestId, int httpStatus) throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/pull").contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().is(httpStatus))
                .andReturn();
    }

    private static String toJsonArray(List<String> values) {
        if (values == null) {
            return "null";
        }
        return "[" + values.stream().map(v -> "\"" + v + "\"").reduce((a, b) -> a + "," + b).orElse("") + "]";
    }

    @Test
    void 创建幂等_同参重放完整快照_异参异操作者409_失败不占键() throws Exception {
        register("rd1", "d1", "m1", 1, List.of("canary"));
        register("rd2", "d2", "m1", 2, List.of("canary", "x"));
        clock.setInstant(Instant.parse("2026-09-28T10:15:30Z"));

        String first = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rc1","operator":"ops-a","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                "ratio":100,"target":{"mustInclude":["canary"]}}
                """)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long releaseId = readLong(first, "$.releaseId");

        // 同键同参重放：响应体逐字节等于首次完整快照，且不产生第二张发布单
        String replay = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rc1","operator":"ops-a","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                "ratio":100,"target":{"mustInclude":["canary"]}}
                """)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(replay).contains("\"resolvedAtUtc\":\"2026-09-28T10:15:30Z\"");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_target_device WHERE release_id = ?",
                Long.class, releaseId)).isEqualTo(2);

        // 同键异参（比例不同）：409
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rc1","operator":"ops-a","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                "ratio":50,"target":{"mustInclude":["canary"]}}
                """)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 同键异操作者：操作者在指纹中，409
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rc1","operator":"ops-b","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0",
                "ratio":100,"target":{"mustInclude":["canary"]}}
                """)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    private static long readLong(String body, String path) {
        return JsonPath.<Number>read(body, path).longValue();
    }

    private static final LabelTargetRequest CN_CANARY_NOT_BLACKLIST =
            new LabelTargetRequest(List.of("region-cn"), List.of("blacklist"), List.of("canary"));

    @Test
    void 主流程_按表达式解析快照并仅对成员下发() throws Exception {
        register("r-d1", "d1", "m1", 1, List.of("region-cn", "canary"));
        register("r-d2", "d2", "m1", 2, List.of("region-cn"));
        register("r-d3", "d3", "m1", 3, List.of("region-cn", "canary", "blacklist"));
        register("r-d4", "d4", "m1", 4, List.of("region-cn", "canary"));
        // d4 型号相同但版本不是来源版本：快照解析候选要求同型号同当前版本，登记后改版本模拟
        jdbc.update("UPDATE device SET current_version = '0.9.0' WHERE device_id = 'd4'");
        clock.setInstant(Instant.parse("2026-09-28T10:15:30Z"));

        long releaseId = createRelease("r-c", "m1", 100, CN_CANARY_NOT_BLACKLIST);

        // 发布单固化：表达式、字典版本、实际目标数量与 UTC 解析时刻
        mockMvc.perform(get("/api/releases/" + releaseId + "/target"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustInclude[0]").value("region-cn"))
                .andExpect(jsonPath("$.mustExclude[0]").value("blacklist"))
                .andExpect(jsonPath("$.includeAny[0]").value("canary"))
                // d1 登记引入 region-cn/canary（推进到1），d3 引入 blacklist（推进到2），d2/d4 无新标签
                .andExpect(jsonPath("$.labelDictVersion").value(2))
                .andExpect(jsonPath("$.targetCount").value(1))
                .andExpect(jsonPath("$.targetDevices.length()").value(1))
                .andExpect(jsonPath("$.targetDevices[0]").value("d1"))
                .andExpect(jsonPath("$.resolvedAtUtc").value("2026-09-28T10:15:30Z"));

        // 创建响应同样携带完整快照（成功响应按首次完整快照重放）
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 仅成员 d1 下发，其余（缺至少一项/命中排除/来源版本不符）均不下发
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"));
        for (String nonMember : List.of("d2", "d3", "d4")) {
            mockMvc.perform(post("/api/devices/" + nonMember + "/pull").contentType("application/json")
                            .content("{\"requestId\":\"r-p-" + nonMember + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.task").doesNotExist());
        }

        Long tasks = jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task WHERE release_id = ?",
                Long.class, releaseId);
        assertThat(tasks).isEqualTo(1);
        Long members = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_target_device WHERE release_id = ?", Long.class, releaseId);
        assertThat(members).isEqualTo(1);
    }

    @Test
    void 快照固化_事后标签变更不追溯扩大缩小_新设备也不纳入_已下发任务不变() throws Exception {
        register("r-d1", "d1", "m1", 1, List.of("canary"));
        register("r-d2", "d2", "m1", 2, List.of("stable"));
        long releaseId = createRelease("r-c", "m1", 100,
                new LabelTargetRequest(null, null, List.of("canary")));
        long taskId = readLong(pullExpectingStatus("d1", "r-p1", 200).getResponse().getContentAsString(),
                "$.task.taskId");

        // 事后把 d2 加进 canary、d1 移除 canary（清空），批量修改
        mockMvc.perform(post("/api/labels/batch-update").contentType("application/json").content("""
                {"requestId":"r-b1","operator":"ops-1","items":[
                  {"deviceId":"d2","labels":["canary"]},
                  {"deviceId":"d1","labels":[]}
                ]}""")).andExpect(status().isOk());

        // d2 标签事后改入但不是快照成员：永不下发
        assertThat(pullExpectingStatus("d2", "r-p2", 200).getResponse().getContentAsString())
                .contains("\"task\":null");
        // d1 标签事后改出：已下发任务仍存在且仍可回执，快照成员表不缩
        assertThat(pullExpectingStatus("d1", "r-p1b", 200).getResponse().getContentAsString())
                .contains("\"taskId\":" + taskId).contains("PENDING");

        // 发布单之后登记的匹配设备同样不纳入旧快照；顺带引入新标签，字典版本继续推进但不回写旧快照
        register("r-d3", "d3", "m1", 3, List.of("canary", "late-tag"));
        assertThat(pullExpectingStatus("d3", "r-p3", 200).getResponse().getContentAsString())
                .contains("\"task\":null");

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.labels.length()").value(0));

        // 快照表始终只有 d1，且固化字典版本不随后续修订改变
        mockMvc.perform(get("/api/releases/" + releaseId + "/target"))
                .andExpect(jsonPath("$.targetDevices[0]").value("d1"))
                .andExpect(jsonPath("$.targetCount").value(1));
        Long frozenVersion = jdbc.queryForObject(
                "SELECT label_dict_version FROM release_order WHERE id = ?", Long.class, releaseId);
        Long currentVersion = jdbc.queryForObject("SELECT version FROM label_dict_meta WHERE id = 1", Long.class);
        assertThat(currentVersion).isGreaterThan(frozenVersion);
    }

    @Test
    void 创建失败分支_空表达式_重复标签_包含排除冲突_空快照_均422且可区分() throws Exception {
        register("r-d1", "d1", "m1", 1, List.of("canary"));

        // target 缺省
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-e1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMPTY_LABEL_CONDITION"));

        // 三组条件全空
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-e2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "target":{"mustInclude":[],"mustExclude":[],"includeAny":[]}}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMPTY_LABEL_CONDITION"));

        // 组内重复
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-e3","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "target":{"includeAny":["x","x"]}}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LABEL_DUPLICATED"));

        // 包含与排除冲突
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-e4","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "target":{"mustInclude":["x"],"mustExclude":["x"]}}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LABEL_INCLUDE_EXCLUDE_CONFLICT"));

        // 快照为空：有设备但无设备满足表达式
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-e5","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "target":{"includeAny":["zzz-none"]}}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TARGET_SNAPSHOT_EMPTY"));

        // 空快照不留半成品：无发布单、无快照
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_target_snapshot", Long.class)).isZero();

        // 422 不占幂等键：同一 requestId 改用有效参数成功
        long releaseId = createRelease("r-e5", "m1", 10,
                new LabelTargetRequest(null, null, List.of("canary")));
        assertThat(releaseId).isPositive();
    }

    @Test
    void 设备标签非法_空白标签422_失败不占键() throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-bad","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "labels":["  "]}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LABEL_INVALID"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device", Long.class)).isZero();
        // 同键修正后成功
        register("r-bad", "d1", "m1", 1, List.of("ok"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device", Long.class)).isEqualTo(1);
    }

    @Test
    void 批量修改_原子提交_任一失败整批回滚不留半成品_失败不占键() throws Exception {
        register("r-d1", "d1", "m1", 1, List.of("a"));
        register("r-d2", "d2", "m1", 2, List.of("b"));

        // 批次内含不存在设备：404，整批不生效
        mockMvc.perform(post("/api/labels/batch-update").contentType("application/json").content("""
                {"requestId":"r-x1","items":[
                  {"deviceId":"d1","labels":["n1"]},
                  {"deviceId":"ghost","labels":["n1"]}
                ]}""")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT labels FROM device WHERE device_id = 'd1'", String.class))
                .isEqualTo("[\"a\"]");

        // 批次内设备重复：422
        mockMvc.perform(post("/api/labels/batch-update").contentType("application/json").content("""
                {"requestId":"r-x2","items":[
                  {"deviceId":"d1","labels":["n1"]},
                  {"deviceId":"d1","labels":["n2"]}
                ]}""")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_DEVICE_DUPLICATED"));

        // 空白标签：422
        mockMvc.perform(post("/api/labels/batch-update").contentType("application/json").content("""
                {"requestId":"r-x3","items":[{"deviceId":"d1","labels":[" "]}]}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LABEL_INVALID"));

        // 全部失败均不占键：同 requestId 合法整批成功，且失败期间没有半成品标签或字典污染
        MvcResult ok = mockMvc.perform(post("/api/labels/batch-update").contentType("application/json").content("""
                {"requestId":"r-x1","operator":"ops","items":[
                  {"deviceId":"d2","labels":["n1","n2"]},
                  {"deviceId":"d1","labels":["n1"]}
                ]}""")).andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceCount").value(2))
                .andExpect(jsonPath("$.updatedCount").value(2))
                .andExpect(jsonPath("$.newTagCount").value(2))
                .andExpect(jsonPath("$.versionDelta").value(1))
                // 逐设备结果按 deviceId 字典序稳定排序
                .andExpect(jsonPath("$.devices[0].deviceId").value("d1"))
                .andExpect(jsonPath("$.devices[0].labels[0]").value("n1"))
                .andExpect(jsonPath("$.devices[1].deviceId").value("d2"))
                .andExpect(jsonPath("$.devices[1].labels[0]").value("n1"))
                .andExpect(jsonPath("$.devices[1].labels[1]").value("n2"))
                .andExpect(jsonPath("$.revisedAtUtc").exists())
                .andReturn();
        long dictVersion = readLong(ok.getResponse().getContentAsString(), "$.dictVersion");
        assertThat(dictVersion).isPositive();
        assertThat(jdbc.queryForObject("SELECT labels FROM device WHERE device_id = 'd2'", String.class))
                .isEqualTo("[\"n1\",\"n2\"]");
    }

    @Test
    void 批量修改幂等_同参重放首次快照_异参409_无新标签版本增量为0但仍留事件() throws Exception {
        register("r-d1", "d1", "m1", 1, List.of("a"));
        register("r-d2", "d2", "m1", 2, List.of("b"));

        String firstBody = mockMvc.perform(post("/api/labels/batch-update").contentType("application/json")
                        .content("""
                                {"requestId":"r-idem","operator":"ops","items":[{"deviceId":"d1","labels":["p","q"]}]}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long firstVersion = readLong(firstBody, "$.dictVersion");
        assertThat(JsonPath.<Integer>read(firstBody, "$.versionDelta")).isEqualTo(1);

        // 另一个批次引入新标签推进字典版本
        mockMvc.perform(post("/api/labels/batch-update").contentType("application/json")
                        .content("""
                                {"requestId":"r-idem2","operator":"ops","items":[{"deviceId":"d2","labels":["r"]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dictVersion").value(firstVersion + 1));

        // 同键同参重放：按首次完整快照返回（dictVersion/versionDelta/标签均为首次值），不再修改状态
        String replay = mockMvc.perform(post("/api/labels/batch-update").contentType("application/json")
                        .content("""
                                {"requestId":"r-idem","operator":"ops","items":[{"deviceId":"d1","labels":["p","q"]}]}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(firstBody);

        // 无新标签的批次：版本增量 0，但仍产生一条 LABEL_BATCH_UPDATE 修订事件
        Long eventsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM label_revision", Long.class);
        mockMvc.perform(post("/api/labels/batch-update").contentType("application/json")
                        .content("""
                                {"requestId":"r-idem3","operator":"ops","items":[{"deviceId":"d1","labels":["p"]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newTagCount").value(0))
                .andExpect(jsonPath("$.versionDelta").value(0))
                .andExpect(jsonPath("$.dictVersion").value(firstVersion + 1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM label_revision", Long.class))
                .isEqualTo(eventsBefore + 1);

        // 同键异参：409（标签集合属于规范化对象集合，进入指纹）
        mockMvc.perform(post("/api/labels/batch-update").contentType("application/json")
                        .content("""
                                {"requestId":"r-idem","operator":"ops","items":[{"deviceId":"d1","labels":["p"]}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 同键异操作者：操作者进入指纹，同样 409
        mockMvc.perform(post("/api/labels/batch-update").contentType("application/json")
                        .content("""
                                {"requestId":"r-idem","operator":"other","items":[{"deviceId":"d1","labels":["p","q"]}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 操作者进入指纹_同键不同操作者409() throws Exception {
        register("r-d1", "d1", "m1", 1, List.of("a"), "alice");
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-d1","deviceId":"d1","model":"m1","currentVersion":"1.0.0","bucketNo":1,
                "labels":["a"],"operator":"bob"}
                """)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 字典与修订诊断_版本随新标签推进_区间左闭右开_只读不改状态() throws Exception {
        clock.setInstant(Instant.parse("2026-09-28T10:00:00Z"));
        register("r-d1", "d1", "m1", 1, List.of("beta", "alpha"), "ops");
        clock.setInstant(Instant.parse("2026-09-28T11:00:00Z"));
        register("r-d2", "d2", "m1", 2, List.of("alpha"), "ops"); // 无新标签，无修订事件

        mockMvc.perform(get("/api/labels/dict"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.tags[0]").value("alpha"))
                .andExpect(jsonPath("$.tags[1]").value("beta"));

        // 批量修改引入 gamma，事件时刻 12:00
        clock.setInstant(Instant.parse("2026-09-28T12:00:00Z"));
        mockMvc.perform(post("/api/labels/batch-update").contentType("application/json").content("""
                {"requestId":"r-b1","operator":"ops","items":[{"deviceId":"d2","labels":["alpha","gamma"]}]}
                """)).andExpect(status().isOk());

        // [10:00, 12:00)：仅 10:00 的登记事件，12:00 整点被右开排除
        mockMvc.perform(get("/api/labels/revisions")
                        .param("fromUtc", "2026-09-28T10:00:00Z")
                        .param("toUtc", "2026-09-28T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.revisions[0].eventType").value("DEVICE_REGISTER"))
                .andExpect(jsonPath("$.revisions[0].newTagCount").value(2))
                .andExpect(jsonPath("$.revisions[0].dictVersion").value(1))
                .andExpect(jsonPath("$.revisions[0].operator").value("ops"));

        // 起点 12:00（左闭）：包含批量事件
        mockMvc.perform(get("/api/labels/revisions").param("fromUtc", "2026-09-28T12:00:00Z"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.revisions[0].eventType").value("LABEL_BATCH_UPDATE"))
                .andExpect(jsonPath("$.revisions[0].newTagCount").value(1))
                .andExpect(jsonPath("$.revisions[0].deviceCount").value(1));

        // 非法时刻 400，区间反向 400
        mockMvc.perform(get("/api/labels/revisions").param("fromUtc", "not-a-time"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_UTC_TIME"));
        mockMvc.perform(get("/api/labels/revisions")
                        .param("fromUtc", "2026-09-28T12:00:00Z")
                        .param("toUtc", "2026-09-28T10:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));

        // 只读查询不改变字典版本与事件数
        Long versionBefore = jdbc.queryForObject("SELECT version FROM label_dict_meta WHERE id = 1", Long.class);
        Long eventsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM label_revision", Long.class);
        mockMvc.perform(get("/api/labels/dict")).andExpect(status().isOk());
        mockMvc.perform(get("/api/labels/revisions")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT version FROM label_dict_meta WHERE id = 1", Long.class))
                .isEqualTo(versionBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM label_revision", Long.class))
                .isEqualTo(eventsBefore);
    }

    @Test
    void 并发_标签修改与发布创建按提交顺序裁决_快照成员与统计一致_无死锁() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        int rounds = 10;
        int emptySnapshots = 0;
        int createdReleases = 0;
        try {
            for (int i = 0; i < rounds; i++) {
                final int seq = i;
                String tag = "tag-" + seq;
                String deviceId = "d" + seq;
                String model = "m" + seq;
                register("rd-" + seq, deviceId, model, 1, List.of(tag));

                List<Callable<Object>> actions = List.of(
                        () -> releaseService.create(new CreateReleaseRequest("rc-" + seq, model,
                                "1.0.0", "2.0.0", 100,
                                new LabelTargetRequest(null, null, List.of(tag)))).releaseId(),
                        () -> deviceLabelService.updateBatch(new BatchLabelUpdateRequest("rb-" + seq, null,
                                List.of(new DeviceLabelsItem(deviceId, List.of("other-" + seq))))));
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Object>> futures = new ArrayList<>();
                for (Callable<Object> action : actions) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        try {
                            return action.call();
                        } catch (Exception e) {
                            return e;
                        }
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                Object createResult = futures.get(0).get(30, TimeUnit.SECONDS);
                Object batchResult = futures.get(1).get(30, TimeUnit.SECONDS);
                assertThat(batchResult).as("批量修改只含已存在设备，应始终成功").isNotInstanceOf(Exception.class);

                if (createResult instanceof ApiException api) {
                    // 标签修改先提交：快照为空，422，且不留发布单
                    emptySnapshots++;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.code()).isEqualTo("TARGET_SNAPSHOT_EMPTY");
                    Long count = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM release_order WHERE model = ?", Long.class, model);
                    assertThat(count).isZero();
                } else {
                    // 创建先提交：快照含该设备；随后标签改出也不追溯缩小，拉取仍对该快照成员下发
                    createdReleases++;
                    long releaseId = ((Number) createResult).longValue();
                    Long targetCount = jdbc.queryForObject(
                            "SELECT target_count FROM release_order WHERE id = ?", Long.class, releaseId);
                    Long memberCount = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM release_target_device WHERE release_id = ?",
                            Long.class, releaseId);
                    assertThat(targetCount).isEqualTo(1);
                    assertThat(memberCount).isEqualTo(1);
                    MvcResult pull = pullExpectingStatus(deviceId, "rp-" + seq, 200);
                    assertThat(pull.getResponse().getContentAsString()).contains("PENDING");
                    Long tasks = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                            Long.class, releaseId, deviceId);
                    assertThat(tasks).isEqualTo(1);
                }
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(emptySnapshots + createdReleases).isEqualTo(rounds);
        // 两种提交顺序的业务结果分别由下方两个确定性顺序用例严格断言；
        // 本用例在真实锁竞争下只裁决：无死锁、无半成品、每轮结果与最终数据一致。
        // 设备最终标签都是整批替换后的完整集合（未出现新旧标签混合的半成品）
        for (int i = 0; i < rounds; i++) {
            assertThat(jdbc.queryForObject("SELECT labels FROM device WHERE device_id = ?",
                    String.class, "d" + i)).isEqualTo("[\"other-" + i + "\"]");
        }
    }

    @Test
    void 并发_同requestId批量修改重放一次_整批结果不被并发撕裂() throws Exception {
        register("rd1", "d1", "m1", 1, List.of("old"));
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            int threads = 6;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return deviceLabelService.updateBatch(new BatchLabelUpdateRequest("same-req", null,
                            List.of(new DeviceLabelsItem("d1", List.of("new-a", "new-b")))));
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Object first = null;
            for (Future<Object> future : futures) {
                Object result = future.get(30, TimeUnit.SECONDS);
                assertThat(result).isNotInstanceOf(Exception.class);
                if (first == null) {
                    first = result;
                } else {
                    assertThat(result).isEqualTo(first);
                }
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        // 只产生一次批量修订事件，标签为完整新集合
        Long batchEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM label_revision WHERE event_type = 'LABEL_BATCH_UPDATE'", Long.class);
        assertThat(batchEvents).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT labels FROM device WHERE device_id = 'd1'", String.class))
                .isEqualTo("[\"new-a\",\"new-b\"]");
    }

    @Test
    void 并发_不同批次交叉设备_最终标签等于某一完整批次() throws Exception {
        register("rd1", "d1", "m1", 1, List.of("old1"));
        register("rd2", "d2", "m1", 2, List.of("old2"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                String a = "a" + round;
                String b = "b" + round;
                resetDeviceLabels("d1", List.of("old1"));
                resetDeviceLabels("d2", List.of("old2"));

                BatchLabelUpdateRequest batchA = new BatchLabelUpdateRequest("ra" + round, null,
                        List.of(new DeviceLabelsItem("d1", List.of(a)),
                                new DeviceLabelsItem("d2", List.of(a))));
                BatchLabelUpdateRequest batchB = new BatchLabelUpdateRequest("rb" + round, null,
                        List.of(new DeviceLabelsItem("d1", List.of(b)),
                                new DeviceLabelsItem("d2", List.of(b))));
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Object>> futures = new ArrayList<>();
                for (BatchLabelUpdateRequest request : List.of(batchA, batchB)) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        return deviceLabelService.updateBatch(request);
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                for (Future<Object> future : futures) {
                    assertThat(future.get(30, TimeUnit.SECONDS)).isNotInstanceOf(Exception.class);
                }
                String labels1 = jdbc.queryForObject("SELECT labels FROM device WHERE device_id = 'd1'",
                        String.class);
                String labels2 = jdbc.queryForObject("SELECT labels FROM device WHERE device_id = 'd2'",
                        String.class);
                // 两设备标签必须来自同一整批（同 a 或同 b），不允许跨批次撕裂
                assertThat(labels1).isEqualTo(labels2);
                assertThat(labels1).isIn("[\"" + a + "\"]", "[\"" + b + "\"]");
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 顺序裁决_标签修改先提交则创建快照为空422_不留半成品_可复用同键成功() throws Exception {
        register("rd1", "d1", "m1", 1, List.of("canary"));

        // 先提交：把 d1 的标签整体替换为不含 canary
        mockMvc.perform(post("/api/labels/batch-update").contentType("application/json").content("""
                {"requestId":"rb1","operator":"ops","items":[{"deviceId":"d1","labels":["stable"]}]}
                """)).andExpect(status().isOk());

        // 后创建：按提交时刻的最终标签解析，快照为空，422
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rc1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "target":{"includeAny":["canary"]}}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TARGET_SNAPSHOT_EMPTY"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_target_snapshot", Long.class)).isZero();

        // 422 不占键且失败不留半成品：同 requestId 改以 stable 为目标成功
        long releaseId = createRelease("rc1", "m1", 100,
                new LabelTargetRequest(null, null, List.of("stable")));
        mockMvc.perform(get("/api/releases/" + releaseId + "/target"))
                .andExpect(jsonPath("$.targetCount").value(1))
                .andExpect(jsonPath("$.targetDevices[0]").value("d1"));
    }

    @Test
    void 顺序裁决_创建先提交则快照固化_事后标签改出仍对成员下发且不缩小快照() throws Exception {
        register("rd1", "d1", "m1", 1, List.of("canary"));
        long releaseId = createRelease("rc1", "m1", 100,
                new LabelTargetRequest(null, null, List.of("canary")));
        long frozenVersion = jdbc.queryForObject(
                "SELECT label_dict_version FROM release_order WHERE id = ?", Long.class, releaseId);

        // 创建提交后再改标签（改出 canary），不追溯缩小快照，成员仍可拉取
        mockMvc.perform(post("/api/labels/batch-update").contentType("application/json").content("""
                {"requestId":"rb1","operator":"ops","items":[{"deviceId":"d1","labels":["stable"]}]}
                """)).andExpect(status().isOk());
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        long taskId = readLong(pull.getResponse().getContentAsString(), "$.task.taskId");

        // 快照成员、数量、固化字典版本均不随后续修订改变
        mockMvc.perform(get("/api/releases/" + releaseId + "/target"))
                .andExpect(jsonPath("$.targetCount").value(1))
                .andExpect(jsonPath("$.targetDevices[0]").value("d1"))
                .andExpect(jsonPath("$.labelDictVersion").value(frozenVersion));
        Long currentVersion = jdbc.queryForObject("SELECT version FROM label_dict_meta WHERE id = 1", Long.class);
        assertThat(currentVersion).isGreaterThan(frozenVersion);

        // 已下发任务可正常回执成功
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rrc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    private void resetDeviceLabels(String deviceId, List<String> labels) {
        String json = "[" + labels.stream().map(l -> "\"" + l + "\"").reduce((x, y) -> x + "," + y).orElse("") + "]";
        jdbc.update("UPDATE device SET labels = ? WHERE device_id = ?", json, deviceId);
    }
}
