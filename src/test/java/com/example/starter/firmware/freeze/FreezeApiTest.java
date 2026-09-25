package com.example.starter.firmware.freeze;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固件发布冻结令主流程与失败分支测试（真实 H2，MODE=MySQL）：UTC 左闭右开窗口、范围匹配、
 * 冻结扫荡与任务快照、紧急双人例外、批量回滚、撤销影响、修订版本与幂等、只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FreezeApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private static String utc(Instant t) {
        return t.toString();
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), path)).longValue();
    }

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM freeze_emergency_exception");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM freeze_order");
        jdbc.update("DELETE FROM freeze_confirmer");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private void registerDevice(String requestId, String deviceId, String model, int bucket) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":%d}
                """.formatted(requestId, deviceId, model, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, int ratio) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d}
                """.formatted(requestId, model, ratio)))
                .andExpect(status().isOk()).andReturn();
        return idOf(result, "$.releaseId");
    }

    private long pullTask(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    private void registerConfirmer(String name) throws Exception {
        mockMvc.perform(post("/api/freeze/confirmers").contentType("application/json").content("""
                {"requestId":"conf-%s","name":"%s"}""".formatted(name, name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name));
    }

    private long createFreeze(String requestId, String start, String end, String modelsJson, String releaseIdsJson)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/freeze/orders").contentType("application/json").content("""
                {"requestId":"%s","startUtc":"%s","endUtc":"%s","models":%s,"releaseIds":%s}
                """.formatted(requestId, start, end, modelsJson, releaseIdsJson)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        return idOf(result, "$.freezeId");
    }

    @Test
    void UTC窗口左闭右开_开始扫荡冻结PENDING_已完成回执不改写_快照固化() throws Exception {
        Instant now = Instant.now();
        registerDevice("d1", "dev1", "m1", 1);
        registerDevice("d2", "dev2", "m1", 2);
        registerDevice("d3", "dev3", "m2", 1);
        createRelease("r1", "m1", 100);
        long t1 = pullTask("p1", "dev1");
        long t2 = pullTask("p2", "dev2");
        createRelease("r2", "m2", 100);
        long t3 = pullTask("p3", "dev3");
        // t1 在冻结前已成功完成，冻结不得改写
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());

        // 生效窗口 [now-60s, now+1h)，型号范围 m1
        long freezeId = createFreeze("f1", utc(now.minus(60, ChronoUnit.SECONDS)),
                utc(now.plus(1, ChronoUnit.HOURS)), "[\"m1\"]", "[]");

        // t1 已完成保持 SUCCESS，t2 命中型号转 RELEASE_FROZEN 并固化快照，t3 型号不命中保持 PENDING
        MvcResult tasks = mockMvc.perform(get("/api/releases/" + jdbc.queryForObject(
                        "SELECT id FROM release_order WHERE model='m1'", Long.class) + "/tasks"))
                .andExpect(status().isOk()).andReturn();
        mockMvc.perform(get("/api/releases/9999/tasks")).andReturn(); // no-op guard
        assertThat(jdbc.queryForObject(
                "SELECT status FROM rollout_task WHERE id=?", String.class, t1)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM rollout_task WHERE id=?", String.class, t2)).isEqualTo("RELEASE_FROZEN");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM rollout_task WHERE id=?", String.class, t3)).isEqualTo("PENDING");

        // 冻结快照字段
        MvcResult t2View = mockMvc.perform(get("/api/releases/"
                        + jdbc.queryForObject("SELECT id FROM release_order WHERE model='m1'", Long.class)
                        + "/tasks").param("status", "RELEASE_FROZEN"))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].freezeSnapshot.freezeId").value(freezeId))
                .andExpect(jsonPath("$.tasks[0].freezeSnapshot.freezeVersion").value(1))
                .andExpect(jsonPath("$.tasks[0].freezeSnapshot.models").value("m1"))
                .andExpect(jsonPath("$.tasks[0].freezeSnapshot.frozenAtUtc").isString())
                .andReturn();
        String frozenAt = JsonPath.read(t2View.getResponse().getContentAsString(),
                "$.tasks[0].freezeSnapshot.frozenAtUtc");
        assertThat(frozenAt).endsWith("Z");

        // 已冻结任务回执 409
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc2\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_FROZEN"));
    }

    @Test
    void 窗口未开始与已结束均不冻结_左闭右开边界() throws Exception {
        Instant now = Instant.now();
        registerDevice("d1", "dev1", "m1", 1);
        createRelease("r1", "m1", 100);
        long t1 = pullTask("p1", "dev1");

        // 窗口尚未开始：不冻结，拉取不被拦截
        createFreeze("future", utc(now.plus(1, ChronoUnit.HOURS)),
                utc(now.plus(2, ChronoUnit.HOURS)), "[\"m1\"]", "[]");
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id=?", String.class, t1))
                .isEqualTo("PENDING");
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"));

        // 已结束窗口（end 为右开，now 已过 end）：不拦截发布启动
        long pastId = createFreeze("past", utc(now.minus(2, ChronoUnit.HOURS)),
                utc(now.minus(1, ChronoUnit.HOURS)), "[\"m9\"]", "[]");
        registerDevice("d9", "dev9", "m9", 1);
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r9","model":"m9","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """))
                .andExpect(status().isOk());
        // 已结束但未撤销的冻结令仍列在 ACTIVE（状态语义），窗口判定不生效
        mockMvc.perform(get("/api/freeze/orders/" + pastId + "/hit").param("model", "m9"))
                .andExpect(jsonPath("$.windowActive").value(false))
                .andExpect(jsonPath("$.scopeHit").value(true))
                .andExpect(jsonPath("$.blocked").value(false));
    }

    @Test
    void 发布单范围命中_型号不匹配也冻结() throws Exception {
        Instant now = Instant.now();
        registerDevice("d1", "dev1", "m1", 1);
        long releaseId = createRelease("r1", "m1", 100);
        long t1 = pullTask("p1", "dev1");
        // 型号范围为空，仅按发布单ID冻结
        createFreeze("f1", utc(now.minusSeconds(30)), utc(now.plusSeconds(3600)), "[]", "[" + releaseId + "]");
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id=?", String.class, t1))
                .isEqualTo("RELEASE_FROZEN");
        mockMvc.perform(get("/api/freeze/orders/9999/hit")).andExpect(status().isNotFound());
    }

    @Test
    void 创建冻结令_窗口非法_范围全空_发布单不存在_422且不写入() throws Exception {
        Instant now = Instant.now();
        // 结束不晚于开始
        mockMvc.perform(post("/api/freeze/orders").contentType("application/json").content("""
                {"requestId":"bad1","startUtc":"%s","endUtc":"%s","models":["m1"],"releaseIds":[]}
                """.formatted(utc(now), utc(now))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FREEZE_WINDOW_INVALID"));
        // 时间无法解析
        mockMvc.perform(post("/api/freeze/orders").contentType("application/json").content("""
                {"requestId":"bad2","startUtc":"not-a-time","endUtc":"%s","models":["m1"],"releaseIds":[]}
                """.formatted(utc(now.plusSeconds(10)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FREEZE_WINDOW_INVALID"));
        // 两个范围都为空
        mockMvc.perform(post("/api/freeze/orders").contentType("application/json").content("""
                {"requestId":"bad3","startUtc":"%s","endUtc":"%s","models":[],"releaseIds":[]}
                """.formatted(utc(now), utc(now.plusSeconds(10)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FREEZE_SCOPE_EMPTY"));
        // 引用不存在的发布单
        mockMvc.perform(post("/api/freeze/orders").contentType("application/json").content("""
                {"requestId":"bad4","startUtc":"%s","endUtc":"%s","models":[],"releaseIds":[9999]}
                """.formatted(utc(now), utc(now.plusSeconds(10)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FREEZE_RELEASE_NOT_FOUND"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_order", Long.class)).isZero();
        // 失败不占键：bad3 用同 requestId 合法请求成功
        mockMvc.perform(post("/api/freeze/orders").contentType("application/json").content("""
                {"requestId":"bad3","startUtc":"%s","endUtc":"%s","models":["m1"],"releaseIds":[]}
                """.formatted(utc(now), utc(now.plusSeconds(10)))))
                .andExpect(status().isOk());
    }

    @Test
    void 范围去重规范化排序_存储为字典序与升序() throws Exception {
        Instant now = Instant.now();
        long r1 = createRelease("r1", "m1", 10);
        long r2 = createRelease("r2", "m2", 10);
        long freezeId = createFreeze("f1", utc(now.plusSeconds(3600)), utc(now.plusSeconds(7200)),
                "[\"m2\",\"m1\",\"m1\"]", "[" + r2 + "," + r1 + "," + r1 + "]");
        MvcResult view = mockMvc.perform(get("/api/freeze/orders/" + freezeId))
                .andExpect(jsonPath("$.models[0]").value("m1"))
                .andExpect(jsonPath("$.models[1]").value("m2"))
                .andExpect(jsonPath("$.models.length()").value(2))
                .andExpect(jsonPath("$.releaseIds[0]").value(r1))
                .andExpect(jsonPath("$.releaseIds[1]").value(r2))
                .andReturn();
        assertThat(JsonPath.read(view.getResponse().getContentAsString(), "$.models").toString())
                .contains("m1", "m2");
    }

    @Test
    void 窗口内发布启动_无例外422_双人例外成功_例外不全各422() throws Exception {
        Instant now = Instant.now();
        registerConfirmer("alice");
        registerConfirmer("bob");
        createFreeze("f1", utc(now.minusSeconds(10)), utc(now.plusSeconds(3600)), "[\"m1\"]", "[]");

        // 无例外：422，发布单不写入
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-blocked","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMERGENCY_GRANT_REQUIRED"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_order WHERE model='m1'", Long.class)).isZero();

        // 两名确认人相同：422
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-same","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "emergencyGrant":{"eventNo":"INC-1","confirmer1":"alice","confirmer2":"alice"}}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMERGENCY_CONFIRMERS_SAME"));

        // 确认人未登记：422
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-unreg","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "emergencyGrant":{"eventNo":"INC-1","confirmer1":"alice","confirmer2":"carol"}}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMERGENCY_CONFIRMER_NOT_REGISTERED"));

        // 缺事件号：422
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-noevent","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "emergencyGrant":{"eventNo":" ","confirmer1":"alice","confirmer2":"bob"}}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMERGENCY_EVENT_NO_REQUIRED"));

        // 完整双人例外：成功，且落例外记录
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-ok","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "emergencyGrant":{"eventNo":"INC-1","confirmer1":"alice","confirmer2":"bob"}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        long freezeId = jdbc.queryForObject("SELECT id FROM freeze_order", Long.class);
        mockMvc.perform(get("/api/freeze/orders/" + freezeId + "/exceptions"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventNo").value("INC-1"))
                .andExpect(jsonPath("$[0].operation").value("RELEASE_START"))
                .andExpect(jsonPath("$[0].confirmer1").value("alice"))
                .andExpect(jsonPath("$[0].confirmer2").value("bob"));
    }

    @Test
    void 窗口内新任务拉取_无例外422_双人例外创建且不被本冻结扫荡_可被其他冻结冻结() throws Exception {
        Instant now = Instant.now();
        registerConfirmer("alice");
        registerConfirmer("bob");
        registerDevice("d1", "dev1", "m1", 1);
        createRelease("r1", "m1", 100);
        long freezeA = createFreeze("fA", utc(now.minusSeconds(10)), utc(now.plusSeconds(3600)),
                "[\"m1\"]", "[]");

        // 无例外拉取：422，任务不创建
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p-blocked\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMERGENCY_GRANT_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();

        // 紧急例外拉取：成功创建，并记录其针对的冻结令
        MvcResult pull = mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json").content("""
                {"requestId":"p-ok","emergencyGrant":{"eventNo":"INC-7","confirmer1":"alice","confirmer2":"bob"}}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.emergencyFreezeId").value(freezeA))
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");

        // 再来一次扫荡（发布单查询不扫荡，构造对同一冻结令的修订触发扫荡）：例外针对本冻结令，不冻结
        mockMvc.perform(post("/api/freeze/orders/" + freezeA + "/revise").contentType("application/json").content("""
                {"requestId":"fA-rev","expectedVersion":1,"startUtc":"%s","endUtc":"%s","models":["m1"],"releaseIds":[]}
                """.formatted(utc(now.minusSeconds(10)), utc(now.plusSeconds(7200)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id=?", String.class, taskId))
                .isEqualTo("PENDING");

        // 另一张冻结令 B 开始：例外不针对 B，任务仍被冻结
        long freezeB = createFreeze("fB", utc(now.minusSeconds(5)), utc(now.plusSeconds(3600)),
                "[\"m1\"]", "[]");
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id=?", String.class, taskId))
                .isEqualTo("RELEASE_FROZEN");
        assertThat(jdbc.queryForObject("SELECT frozen_freeze_id FROM rollout_task WHERE id=?",
                Long.class, taskId)).isEqualTo(freezeB);
    }

    @Test
    void 批量创建冻结令_任一非法整体422且全部不写入() throws Exception {
        Instant now = Instant.now();
        String body = """
                {"requestId":"batch1","items":[
                  {"startUtc":"%s","endUtc":"%s","models":["m1"],"releaseIds":[]},
                  {"startUtc":"%s","endUtc":"%s","models":[],"releaseIds":[]}
                ]}
                """.formatted(utc(now.minusSeconds(10)), utc(now.plusSeconds(3600)),
                utc(now), utc(now.plusSeconds(10)));
        mockMvc.perform(post("/api/freeze/orders/batch").contentType("application/json").content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FREEZE_SCOPE_EMPTY"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_order", Long.class)).isZero();

        // 全部合法：批量成功并按序返回
        String ok = """
                {"requestId":"batch2","items":[
                  {"startUtc":"%s","endUtc":"%s","models":["m1"],"releaseIds":[]},
                  {"startUtc":"%s","endUtc":"%s","models":["m2"],"releaseIds":[]}
                ]}
                """.formatted(utc(now.plusSeconds(3600)), utc(now.plusSeconds(7200)),
                utc(now.plusSeconds(3600)), utc(now.plusSeconds(7200)));
        mockMvc.perform(post("/api/freeze/orders/batch").contentType("application/json").content(ok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freezes.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_order", Long.class)).isEqualTo(2);
    }

    @Test
    void 批量启动发布_任一冻结冲突整体422且全部不写入_例外齐全整体成功() throws Exception {
        Instant now = Instant.now();
        registerConfirmer("alice");
        registerConfirmer("bob");
        createFreeze("f1", utc(now.minusSeconds(10)), utc(now.plusSeconds(3600)), "[\"m1\"]", "[]");

        // 批量中 m1 命中、m2 不命中，无例外：整体 422，两张发布单都不写入
        String blocked = """
                {"requestId":"b1","items":[
                  {"model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10},
                  {"model":"m2","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10}
                ]}
                """;
        mockMvc.perform(post("/api/releases/batch").contentType("application/json").content(blocked))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMERGENCY_GRANT_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isZero();

        // 例外齐全（两名不同已登记确认人）：整体成功
        String granted = """
                {"requestId":"b2","items":[
                  {"model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10},
                  {"model":"m2","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10}
                ],"emergencyGrant":{"eventNo":"INC-9","confirmer1":"alice","confirmer2":"bob"}}
                """;
        mockMvc.perform(post("/api/releases/batch").contentType("application/json").content(granted))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releases.length()").value(2))
                .andExpect(jsonPath("$.emergencyFreezeIds.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order", Long.class)).isEqualTo(2);
    }

    @Test
    void 撤销冻结令_已冻结任务不复活_撤销后新拉取放行_返回撤销影响() throws Exception {
        Instant now = Instant.now();
        registerDevice("d1", "dev1", "m1", 1);
        registerDevice("d2", "dev2", "m1", 2);
        createRelease("r1", "m1", 100);
        long t1 = pullTask("p1", "dev1");
        long freezeId = createFreeze("f1", utc(now.minusSeconds(10)), utc(now.plusSeconds(3600)),
                "[\"m1\"]", "[]");
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id=?", String.class, t1))
                .isEqualTo("RELEASE_FROZEN");

        // 撤销：已冻结 t1 不复活；此刻命中范围的 PENDING 数为 0（t2 尚未创建）
        MvcResult revoke = mockMvc.perform(post("/api/freeze/orders/" + freezeId + "/revoke")
                        .contentType("application/json").content("{\"requestId\":\"rv1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freeze.status").value("REVOKED"))
                .andExpect(jsonPath("$.freeze.revokedAtUtc").isString())
                .andReturn();
        assertThat(((Number) JsonPath.read(revoke.getResponse().getContentAsString(),
                "$.affectedPendingTasks")).longValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id=?", String.class, t1))
                .isEqualTo("RELEASE_FROZEN");

        // 撤销后新拉取放行（窗口虽仍覆盖当前时刻，但冻结令已撤销）
        MvcResult pull2 = mockMvc.perform(post("/api/devices/dev2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        long t2 = idOf(pull2, "$.task.taskId");

        // 再次撤销幂等返回 REVOKED，affectedPendingTasks 统计撤销后命中范围尚未开始任务（t2）
        mockMvc.perform(post("/api/freeze/orders/" + freezeId + "/revoke").contentType("application/json")
                        .content("{\"requestId\":\"rv2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedPendingTasks").value(1));

        // 已撤销冻结令不能修订
        String revBad = "{\"requestId\":\"rev-bad\",\"expectedVersion\":1,\"startUtc\":\"" + utc(now)
                + "\",\"endUtc\":\"" + utc(now.plusSeconds(10)) + "\",\"models\":[\"m1\"],\"releaseIds\":[]}";
        mockMvc.perform(post("/api/freeze/orders/" + freezeId + "/revise").contentType("application/json")
                        .content(revBad))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FREEZE_ALREADY_REVOKED"));
        assertThat(t2).isPositive();
    }

    @Test
    void 修订_expectedVersion冲突409_成功版本加一_有效冻结与命中查询() throws Exception {
        Instant now = Instant.now();
        long freezeId = createFreeze("f1", utc(now.plusSeconds(3600)), utc(now.plusSeconds(7200)),
                "[\"m1\"]", "[]");

        mockMvc.perform(get("/api/freeze/orders/active"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].freezeId").value(freezeId));
        mockMvc.perform(get("/api/freeze/orders/" + freezeId + "/hit").param("model", "m1"))
                .andExpect(jsonPath("$.scopeHit").value(true));

        // 错误 expectedVersion：409
        String revWrong = "{\"requestId\":\"rev1\",\"expectedVersion\":99,\"startUtc\":\""
                + utc(now.plusSeconds(3600)) + "\",\"endUtc\":\"" + utc(now.plusSeconds(10800))
                + "\",\"models\":[\"m1\",\"m2\"],\"releaseIds\":[]}";
        mockMvc.perform(post("/api/freeze/orders/" + freezeId + "/revise").contentType("application/json")
                        .content(revWrong))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FREEZE_VERSION_CONFLICT"));

        // 正确修订：版本加一、范围更新
        String revOk = "{\"requestId\":\"rev2\",\"expectedVersion\":1,\"startUtc\":\""
                + utc(now.plusSeconds(3600)) + "\",\"endUtc\":\"" + utc(now.plusSeconds(10800))
                + "\",\"models\":[\"m1\",\"m2\"],\"releaseIds\":[]}";
        mockMvc.perform(post("/api/freeze/orders/" + freezeId + "/revise").contentType("application/json")
                        .content(revOk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.models.length()").value(2));
    }

    @Test
    void 幂等_同键同参重放首次完整响应_异参409_指纹含版本范围窗口例外() throws Exception {
        Instant now = Instant.now();
        registerConfirmer("alice");
        registerConfirmer("bob");

        String firstBody = """
                {"requestId":"same","startUtc":"%s","endUtc":"%s","models":["m1"],"releaseIds":[]}
                """.formatted(utc(now.plusSeconds(3600)), utc(now.plusSeconds(7200)));
        MvcResult first = mockMvc.perform(post("/api/freeze/orders").contentType("application/json")
                        .content(firstBody))
                .andExpect(status().isOk()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/freeze/orders").contentType("application/json")
                        .content(firstBody))
                .andExpect(status().isOk()).andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_order", Long.class)).isEqualTo(1);

        // 同键异参（窗口不同）：409
        mockMvc.perform(post("/api/freeze/orders").contentType("application/json").content("""
                {"requestId":"same","startUtc":"%s","endUtc":"%s","models":["m1"],"releaseIds":[]}
                """.formatted(utc(now.plusSeconds(3601)), utc(now.plusSeconds(7200)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 紧急发布启动：同键同参重放只落一次发布单与一条例外；异参（确认人不同）409
        long freezeId = jdbc.queryForObject("SELECT id FROM freeze_order", Long.class);
        // 先把冻结窗口改为当前生效
        mockMvc.perform(post("/api/freeze/orders/" + freezeId + "/revise").contentType("application/json")
                .content("""
                        {"requestId":"rev-now","expectedVersion":1,"startUtc":"%s","endUtc":"%s",
                        "models":["m9"],"releaseIds":[]}
                        """.formatted(utc(now.minusSeconds(10)), utc(now.plusSeconds(3600)))))
                .andExpect(status().isOk());
        String grantBody = """
                {"requestId":"g1","model":"m9","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "emergencyGrant":{"eventNo":"INC-X","confirmer1":"alice","confirmer2":"bob"}}
                """;
        MvcResult gFirst = mockMvc.perform(post("/api/releases").contentType("application/json")
                        .content(grantBody)).andExpect(status().isOk()).andReturn();
        MvcResult gReplay = mockMvc.perform(post("/api/releases").contentType("application/json")
                        .content(grantBody)).andExpect(status().isOk()).andReturn();
        assertThat(gReplay.getResponse().getContentAsString()).isEqualTo(gFirst.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_order WHERE model='m9'", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM freeze_emergency_exception", Long.class)).isEqualTo(1);

        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"g1","model":"m9","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,
                "emergencyGrant":{"eventNo":"INC-Y","confirmer1":"alice","confirmer2":"bob"}}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 任务创建与完成时发布快照被固化() throws Exception {
        registerDevice("d1", "dev1", "m1", 1);
        long releaseId = createRelease("r1", "m1", 100);
        long t1 = pullTask("p1", "dev1");
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].releaseVersionAtCreate").value(1))
                .andExpect(jsonPath("$.tasks[0].releaseVersionAtComplete").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].releaseVersionAtCreate").value(1))
                .andExpect(jsonPath("$.tasks[0].releaseVersionAtComplete").value(1));
    }
}
