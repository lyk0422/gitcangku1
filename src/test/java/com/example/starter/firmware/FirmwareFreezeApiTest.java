package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固件发布冻结令 API 测试：UTC 窗口、范围匹配、紧急双人例外、批量回滚、
 * 任务快照、撤销影响与 freezeKey 幂等（H2 内存库，MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareFreezeApiTest {

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
        jdbc.update("DELETE FROM freeze_exception_record");
        jdbc.update("DELETE FROM freeze_order");
        jdbc.update("DELETE FROM freeze_approver");
    }

    private static String activeStart() {
        return Instant.now().minusSeconds(3600).toString();
    }

    private static String activeEnd() {
        return Instant.now().plusSeconds(3600).toString();
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerApprover(String requestId, String approverId) throws Exception {
        mockMvc.perform(post("/api/freeze-approvers").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"approverId\":\"%s\"}".formatted(requestId, approverId)))
                .andExpect(status().isOk());
    }

    private void registerDevice(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json")
                        .content("""
                                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                                """.formatted(requestId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    private String createRelease(String requestId, String model, String from, String to, int ratio)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json")
                        .content("""
                                {"requestId":"%s","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":%d}
                                """.formatted(requestId, model, from, to, ratio)))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private String createFreeze(String freezeKey, String models, String releaseIds,
                                String startUtc, String endUtc) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/freeze-orders").contentType("application/json")
                        .content("""
                                {"freezeKey":"%s","models":%s,"releaseIds":%s,"startUtc":"%s","endUtc":"%s"}
                                """.formatted(freezeKey, models, releaseIds, startUtc, endUtc)))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private String pull(String deviceId, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    @Test
    void 创建冻结令_窗口必须结束晚于开始_范围至少一项_规范排序() throws Exception {
        // 结束等于开始：422
        mockMvc.perform(post("/api/freeze-orders").contentType("application/json").content("""
                        {"freezeKey":"fk-w1","models":["m1"],"startUtc":"2026-09-26T08:00:00Z","endUtc":"2026-09-26T08:00:00Z"}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_WINDOW"));

        // 结束早于开始：422
        mockMvc.perform(post("/api/freeze-orders").contentType("application/json").content("""
                        {"freezeKey":"fk-w2","models":["m1"],"startUtc":"2026-09-26T09:00:00Z","endUtc":"2026-09-26T08:00:00Z"}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_WINDOW"));

        // 两个范围集合均为空：422
        mockMvc.perform(post("/api/freeze-orders").contentType("application/json").content("""
                        {"freezeKey":"fk-w3","models":[],"releaseIds":[],"startUtc":"2026-09-26T08:00:00Z","endUtc":"2026-09-26T09:00:00Z"}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMPTY_FREEZE_SCOPE"));

        // UTC 格式非法：400
        mockMvc.perform(post("/api/freeze-orders").contentType("application/json").content("""
                        {"freezeKey":"fk-w4","models":["m1"],"startUtc":"not-a-time","endUtc":"2026-09-26T09:00:00Z"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_UTC_FORMAT"));

        // 合法创建：范围去重并规范排序，版本从1开始，窗口生效中
        mockMvc.perform(post("/api/freeze-orders").contentType("application/json").content("""
                        {"freezeKey":"fk-ok","models":["m2","m1","m2"],"releaseIds":[9,3,9],
                         "startUtc":"%s","endUtc":"%s"}
                        """.formatted(activeStart(), activeEnd())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freezeKey").value("fk-ok"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.windowActive").value(true))
                .andExpect(jsonPath("$.models[0]").value("m1"))
                .andExpect(jsonPath("$.models[1]").value("m2"))
                .andExpect(jsonPath("$.models.length()").value(2))
                .andExpect(jsonPath("$.releaseIds[0]").value(3))
                .andExpect(jsonPath("$.releaseIds[1]").value(9))
                .andExpect(jsonPath("$.releaseIds.length()").value(2));
    }

    @Test
    void 冻结窗口内_发布启动422_完整紧急例外放行并留痕() throws Exception {
        registerApprover("ra1", "alice");
        registerApprover("ra2", "bob");
        createFreeze("fk1", "[\"m1\"]", "[]", activeStart(), activeEnd());

        // 无例外：422 RELEASE_FROZEN
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RELEASE_FROZEN"));

        // 例外不全（缺确认人）：422
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r2","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "exception":{"incidentId":"INC-1"}}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EXCEPTION_INCOMPLETE"));

        // 两名确认人相同：422
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r3","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "exception":{"incidentId":"INC-1","approvers":["alice","alice"]}}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EXCEPTION_APPROVER_DUPLICATE"));

        // 确认人未登记：422
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r4","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "exception":{"incidentId":"INC-1","approvers":["alice","carol"]}}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EXCEPTION_APPROVER_UNKNOWN"));

        // 完整例外：放行并记录
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"r5","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                         "exception":{"incidentId":"INC-1","approvers":["bob","alice"]}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/freeze-orders/exceptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].api").value("release.create"))
                .andExpect(jsonPath("$[0].incidentId").value("INC-1"))
                .andExpect(jsonPath("$[0].approvers[0]").value("alice"))
                .andExpect(jsonPath("$[0].approvers[1]").value("bob"));

        // 未命中范围的型号不受影响
        createRelease("r6", "m2", "1.0.0", "2.0.0", 10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isEqualTo(2);
    }

    @Test
    void 冻结窗口内_新任务拉取422_完整例外放行() throws Exception {
        registerApprover("ra1", "alice");
        registerApprover("ra2", "bob");
        registerDevice("rd1", "d1", "m1", "1.0.0", 1);
        registerDevice("rd2", "d2", "m1", "1.0.0", 2);
        createRelease("rr1", "m1", "1.0.0", "2.0.0", 100);
        createFreeze("fk1", "[\"m1\"]", "[]", activeStart(), activeEnd());

        // 无例外拉取：422
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RELEASE_FROZEN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();

        // 完整例外拉取：放行并记录
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json").content("""
                        {"requestId":"p2","exception":{"incidentId":"INC-7","approvers":["alice","bob"]}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"));
        mockMvc.perform(get("/api/freeze-orders/exceptions"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].api").value("task.pull"))
                .andExpect(jsonPath("$[0].ref").value("d2"));
    }

    @Test
    void 冻结开始_未开始任务转RELEASE_FROZEN并固化快照_已完成回执不改写() throws Exception {
        registerDevice("rd1", "d1", "m1", "1.0.0", 1);
        registerDevice("rd2", "d2", "m1", "1.0.0", 2);
        long releaseId = idOf(mockMvc.perform(post("/api/releases").contentType("application/json")
                        .content("""
                                {"requestId":"rr1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                                """))
                .andReturn(), "$.releaseId");
        long pendingTask = idOf(mockMvc.perform(post("/api/devices/d1/pull")
                        .contentType("application/json").content("{\"requestId\":\"p1\"}"))
                .andReturn(), "$.task.taskId");
        long doneTask = idOf(mockMvc.perform(post("/api/devices/d2/pull")
                        .contentType("application/json").content("{\"requestId\":\"p2\"}"))
                .andReturn(), "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + doneTask + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());

        String body = createFreeze("fk1", "[]", "[" + releaseId + "]", activeStart(), activeEnd());
        Number freezeIdValue = com.jayway.jsonpath.JsonPath.read(body, "$.freezeId");
        long freezeId = freezeIdValue.longValue();
        assertThat((Number) com.jayway.jsonpath.JsonPath.read(body, "$.frozenTaskIds.length()")).isEqualTo(1);

        // 未开始任务被冻结并固化快照
        mockMvc.perform(get("/api/tasks/" + pendingTask))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASE_FROZEN"))
                .andExpect(jsonPath("$.freezeOrderId").value(freezeId))
                .andExpect(jsonPath("$.freezeSnapshot").exists());
        String snapshot = jdbc.queryForObject("SELECT freeze_snapshot FROM rollout_task WHERE id = ?",
                String.class, pendingTask);
        assertThat(snapshot).contains("\"freezeKey\":\"fk1\"").contains("\"version\":1");

        // 已完成回执不改写
        mockMvc.perform(get("/api/tasks/" + doneTask))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.freezeOrderId").doesNotExist());
    }

    @Test
    void 冻结任务回执409_撤销后解冻恢复回执_撤销影响可查() throws Exception {
        registerDevice("rd1", "d1", "m1", "1.0.0", 1);
        createRelease("rr1", "m1", "1.0.0", "2.0.0", 100);
        long taskId = idOf(mockMvc.perform(post("/api/devices/d1/pull")
                        .contentType("application/json").content("{\"requestId\":\"p1\"}"))
                .andReturn(), "$.task.taskId");
        long freezeId = idOf(mockMvc.perform(post("/api/freeze-orders").contentType("application/json")
                        .content("""
                                {"freezeKey":"fk1","models":["m1"],"startUtc":"%s","endUtc":"%s"}
                                """.formatted(activeStart(), activeEnd())))
                .andReturn(), "$.freezeId");

        // 冻结中回执：409 TASK_FROZEN
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_FROZEN"));

        // 撤销：解冻未开始任务，返回撤销影响
        mockMvc.perform(post("/api/freeze-orders/" + freezeId + "/revoke").contentType("application/json")
                        .content("{\"requestId\":\"rv1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeAffectedTaskIds[0]").value(taskId))
                .andExpect(jsonPath("$.revokedAtUtc").exists());

        // 解冻后任务回到 PENDING 且冻结快照清除，可正常回执
        mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.freezeOrderId").doesNotExist());
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc2\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 重复撤销幂等返回当前状态；撤销影响仍可查
        mockMvc.perform(post("/api/freeze-orders/" + freezeId + "/revoke").contentType("application/json")
                        .content("{\"requestId\":\"rv2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeAffectedTaskIds[0]").value(taskId));

        // 撤销后拉取不再冻结
        registerDevice("rd2", "d2", "m1", "1.0.0", 2);
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"));
    }

    @Test
    void 修订_expectedVersion校验_版本加一_已撤销不可修订() throws Exception {
        long freezeId = idOf(mockMvc.perform(post("/api/freeze-orders").contentType("application/json")
                        .content("""
                                {"freezeKey":"fk1","models":["m1"],"startUtc":"%s","endUtc":"%s"}
                                """.formatted(activeStart(), activeEnd())))
                .andReturn(), "$.freezeId");

        // 版本不匹配：409
        mockMvc.perform(post("/api/freeze-orders/" + freezeId + "/revise").contentType("application/json")
                        .content("""
                                {"requestId":"rv1","expectedVersion":9,"models":["m2"],
                                 "startUtc":"%s","endUtc":"%s"}
                                """.formatted(activeStart(), activeEnd())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 版本匹配：修订成功，版本加一，范围替换
        mockMvc.perform(post("/api/freeze-orders/" + freezeId + "/revise").contentType("application/json")
                        .content("""
                                {"requestId":"rv2","expectedVersion":1,"models":["m2"],
                                 "startUtc":"%s","endUtc":"%s"}
                                """.formatted(activeStart(), activeEnd())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.models[0]").value("m2"));

        // 撤销后不可修订：409
        mockMvc.perform(post("/api/freeze-orders/" + freezeId + "/revoke").contentType("application/json")
                        .content("{\"requestId\":\"rv3\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/freeze-orders/" + freezeId + "/revise").contentType("application/json")
                        .content("""
                                {"requestId":"rv4","expectedVersion":2,"models":["m3"],
                                 "startUtc":"%s","endUtc":"%s"}
                                """.formatted(activeStart(), activeEnd())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FREEZE_REVOKED"));

        // 不存在的冻结令：404
        mockMvc.perform(post("/api/freeze-orders/9999/revise").contentType("application/json")
                        .content("""
                                {"requestId":"rv5","expectedVersion":1,"models":["m3"],
                                 "startUtc":"%s","endUtc":"%s"}
                                """.formatted(activeStart(), activeEnd())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/freeze-orders/9999/revoke").contentType("application/json")
                        .content("{\"requestId\":\"rv6\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 批量创建_任一冲突或例外不全则整批422且全部不写入() throws Exception {
        registerApprover("ra1", "alice");
        registerApprover("ra2", "bob");

        // 第二条窗口非法：整批 422，一条都不写入
        mockMvc.perform(post("/api/freeze-orders/batch").contentType("application/json").content("""
                        {"requestId":"b1","freezes":[
                          {"freezeKey":"bk1","models":["m1"],"startUtc":"%s","endUtc":"%s"},
                          {"freezeKey":"bk2","models":["m2"],"startUtc":"2026-09-26T09:00:00Z","endUtc":"2026-09-26T08:00:00Z"}
                        ]}
                        """.formatted(activeStart(), activeEnd())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_WINDOW"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_order", Long.class)).isZero();

        // 第二条例外不全（确认人未登记）：整批 422，全部不写入
        mockMvc.perform(post("/api/freeze-orders/batch").contentType("application/json").content("""
                        {"requestId":"b2","freezes":[
                          {"freezeKey":"bk1","models":["m1"],"startUtc":"%s","endUtc":"%s"},
                          {"freezeKey":"bk2","models":["m2"],"startUtc":"%s","endUtc":"%s",
                           "exception":{"incidentId":"INC-1","approvers":["alice","ghost"]}}
                        ]}
                        """.formatted(activeStart(), activeEnd(), activeStart(), activeEnd())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EXCEPTION_APPROVER_UNKNOWN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_order", Long.class)).isZero();

        // 批量内 freezeKey 重复：422
        mockMvc.perform(post("/api/freeze-orders/batch").contentType("application/json").content("""
                        {"requestId":"b3","freezes":[
                          {"freezeKey":"bk1","models":["m1"],"startUtc":"%s","endUtc":"%s"},
                          {"freezeKey":"bk1","models":["m2"],"startUtc":"%s","endUtc":"%s"}
                        ]}
                        """.formatted(activeStart(), activeEnd(), activeStart(), activeEnd())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FREEZE_KEY_DUPLICATE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_order", Long.class)).isZero();

        // 全部合法：整批写入
        mockMvc.perform(post("/api/freeze-orders/batch").contentType("application/json").content("""
                        {"requestId":"b4","freezes":[
                          {"freezeKey":"bk1","models":["m1"],"startUtc":"%s","endUtc":"%s"},
                          {"freezeKey":"bk2","models":["m2"],"startUtc":"%s","endUtc":"%s",
                           "exception":{"incidentId":"INC-1","approvers":["alice","bob"]}}
                        ]}
                        """.formatted(activeStart(), activeEnd(), activeStart(), activeEnd())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freezes.length()").value(2))
                .andExpect(jsonPath("$.freezes[0].freezeKey").value("bk1"))
                .andExpect(jsonPath("$.freezes[1].exception.incidentId").value("INC-1"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_order", Long.class)).isEqualTo(2);

        // 与既有 freezeKey 冲突：422
        mockMvc.perform(post("/api/freeze-orders/batch").contentType("application/json").content("""
                        {"requestId":"b5","freezes":[
                          {"freezeKey":"bk1","models":["m9"],"startUtc":"%s","endUtc":"%s"}
                        ]}
                        """.formatted(activeStart(), activeEnd())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FREEZE_KEY_EXISTS"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_order", Long.class)).isEqualTo(2);
    }

    @Test
    void freezeKey幂等_同键同参重放首次响应_异参409_失败不占键() throws Exception {
        String start = activeStart();
        String end = activeEnd();
        String first = createFreeze("fk1", "[\"m1\"]", "[]", start, end);
        assertThat(first).contains("\"freezeKey\":\"fk1\"");

        // 同键同参：重放首次完整响应，不新增行
        String replay = createFreeze("fk1", "[\"m1\"]", "[]", start, end);
        Number replayId = com.jayway.jsonpath.JsonPath.read(replay, "$.freezeId");
        Number firstId = com.jayway.jsonpath.JsonPath.read(first, "$.freezeId");
        assertThat(replayId.longValue()).isEqualTo(firstId.longValue());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_order", Long.class)).isEqualTo(1);

        // 同键异参：409
        mockMvc.perform(post("/api/freeze-orders").contentType("application/json").content("""
                        {"freezeKey":"fk1","models":["m2"],"startUtc":"%s","endUtc":"%s"}
                        """.formatted(start, end)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：先以非法窗口失败，再用同键合法参数成功
        mockMvc.perform(post("/api/freeze-orders").contentType("application/json").content("""
                        {"freezeKey":"fk2","models":["m1"],"startUtc":"2026-09-26T09:00:00Z","endUtc":"2026-09-26T08:00:00Z"}
                        """))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/freeze-orders").contentType("application/json").content("""
                        {"freezeKey":"fk2","models":["m1"],"startUtc":"%s","endUtc":"%s"}
                        """.formatted(start, end)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freezeKey").value("fk2"));
    }

    @Test
    void 查询_有效冻结令_范围命中_窗口未开始或已结束不冻结() throws Exception {
        String futureStart = Instant.now().plusSeconds(3600).toString();
        String futureEnd = Instant.now().plusSeconds(7200).toString();
        String pastStart = Instant.now().minusSeconds(7200).toString();
        String pastEnd = Instant.now().minusSeconds(3600).toString();

        createFreeze("fk-active", "[\"m1\"]", "[]", activeStart(), activeEnd());
        createFreeze("fk-future", "[\"m2\"]", "[]", futureStart, futureEnd);
        createFreeze("fk-past", "[\"m3\"]", "[]", pastStart, pastEnd);

        // 有效冻结令：仅窗口生效中的 ACTIVE
        mockMvc.perform(get("/api/freeze-orders").param("effective", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].freezeKey").value("fk-active"));
        mockMvc.perform(get("/api/freeze-orders"))
                .andExpect(jsonPath("$.length()").value(3));

        // 范围命中：型号命中与未命中
        mockMvc.perform(get("/api/freeze-orders/hit").param("model", "m1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hit").value(true))
                .andExpect(jsonPath("$.matches.length()").value(1));
        mockMvc.perform(get("/api/freeze-orders/hit").param("model", "m2"))
                .andExpect(jsonPath("$.hit").value(false))
                .andExpect(jsonPath("$.matches.length()").value(1));
        mockMvc.perform(get("/api/freeze-orders/hit").param("model", "m9"))
                .andExpect(jsonPath("$.hit").value(false))
                .andExpect(jsonPath("$.matches.length()").value(0));
        mockMvc.perform(get("/api/freeze-orders/hit"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HIT_SCOPE_REQUIRED"));

        // 窗口未开始/已结束：发布启动与拉取均不冻结
        registerDevice("rd1", "d1", "m2", "1.0.0", 1);
        registerDevice("rd2", "d2", "m3", "1.0.0", 1);
        createRelease("rr1", "m2", "1.0.0", "2.0.0", 100);
        createRelease("rr2", "m3", "1.0.0", "2.0.0", 100);
        assertThat(pull("d1", "p1")).contains("\"status\":\"PENDING\"");
        assertThat(pull("d2", "p2")).contains("\"status\":\"PENDING\"");
    }

    @Test
    void 确认人登记_重复409_查询列表() throws Exception {
        registerApprover("ra1", "alice");
        mockMvc.perform(post("/api/freeze-approvers").contentType("application/json")
                        .content("{\"requestId\":\"ra2\",\"approverId\":\"alice\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVER_EXISTS"));
        mockMvc.perform(get("/api/freeze-approvers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value("alice"));
    }

    @Test
    void 任务与回执保留创建或完成时的发布快照() throws Exception {
        registerDevice("rd1", "d1", "m1", "1.0.0", 1);
        long releaseId = idOf(mockMvc.perform(post("/api/releases").contentType("application/json")
                        .content("""
                                {"requestId":"rr1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                                """))
                .andReturn(), "$.releaseId");
        long taskId = idOf(mockMvc.perform(post("/api/devices/d1/pull")
                        .contentType("application/json").content("{\"requestId\":\"p1\"}"))
                .andReturn(), "$.task.taskId");

        // 创建时快照固化在任务上
        mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(jsonPath("$.fromVersion").value("1.0.0"))
                .andExpect(jsonPath("$.toVersion").value("2.0.0"))
                .andExpect(jsonPath("$.receiptFromVersion").doesNotExist());

        // 回执完成时固化完成快照
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptFromVersion").value("1.0.0"))
                .andExpect(jsonPath("$.receiptToVersion").value("2.0.0"));

        String receiptFrom = jdbc.queryForObject(
                "SELECT receipt_from_version FROM rollout_task WHERE id = ?", String.class, taskId);
        assertThat(receiptFrom).isEqualTo("1.0.0");
        assertThat(releaseId).isPositive();
    }
}
