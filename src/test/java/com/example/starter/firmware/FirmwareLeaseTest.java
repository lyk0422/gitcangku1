package com.example.starter.firmware;

import com.example.starter.firmware.support.ClockTestConfiguration;
import com.example.starter.firmware.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 执行租约 API 测试（H2 内存库，MODE=MySQL，独立命名库）：
 * 领取/续租/接管、旧代理回执拒绝、终态冻结、旧入口不绕过模式、取消交互与幂等边界。
 * 时钟使用固定起点的可控时钟，到期边界无需真实等待。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfiguration.class)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:firmware_lease_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class FirmwareLeaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        clock.setInstant(ClockTestConfiguration.START);
    }

    private void registerDevice(String requestId, String deviceId) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                """.formatted(requestId, deviceId, deviceId)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """.formatted(requestId)))
                .andExpect(status().isOk())
                .andReturn();
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.releaseId");
        return value.longValue();
    }

    private long pullTask(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.task.taskId");
        return value.longValue();
    }

    private org.springframework.test.web.servlet.ResultActions acquire(long taskId, String requestId,
                                                                       String agentId) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + taskId + "/lease").contentType("application/json")
                .content("""
                        {"requestId":"%s","agentId":"%s"}
                        """.formatted(requestId, agentId)));
    }

    private org.springframework.test.web.servlet.ResultActions renew(long taskId, String requestId,
                                                                     String agentId, int token) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + taskId + "/lease/renew").contentType("application/json")
                .content("""
                        {"requestId":"%s","agentId":"%s","token":%d}
                        """.formatted(requestId, agentId, token)));
    }

    private org.springframework.test.web.servlet.ResultActions receipt(long taskId, String requestId,
                                                                       String result, String agentId,
                                                                       Integer token) throws Exception {
        String creds = "";
        if (agentId != null) {
            creds = ",\"agentId\":\"" + agentId + "\"";
        }
        if (token != null) {
            creds += ",\"token\":" + token;
        }
        return mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"%s\",\"result\":\"%s\"%s}".formatted(requestId, result, creds)));
    }

    @Test
    void 主流程_P领取token1_到期Q领取token2_P回执409_Q成功才更新并计一个样本() throws Exception {
        registerDevice("r-dev", "d1");
        long releaseId = createRelease("r-rel");
        long taskId = pullTask("r-pull", "d1");

        String firstLease = acquire(taskId, "req-lease-p", "P").andReturn()
                .getResponse().getContentAsString();
        assertThat(firstLease).contains("\"agentId\":\"P\"").contains("\"token\":1")
                .contains("\"expiresAtUtc\":\"2026-10-08T00:00:30Z\"");

        // 有效期内其他代理不能抢占
        mockMvc.perform(post("/api/tasks/" + taskId + "/lease").contentType("application/json")
                        .content("{\"requestId\":\"req-lease-q-early\",\"agentId\":\"Q\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_HELD_BY_OTHER"));

        // 30 秒到期后 Q 接管，token 递增为 2
        clock.advance(Duration.ofSeconds(30));
        String secondLease = acquire(taskId, "req-lease-q", "Q").andReturn()
                .getResponse().getContentAsString();
        assertThat(secondLease).contains("\"agentId\":\"Q\"").contains("\"token\":2")
                .contains("\"expiresAtUtc\":\"2026-10-08T00:01:00Z\"");

        // P 持旧 token 成功回执：409（旧代已被接管），设备与统计不变
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"req-rc-p\",\"result\":\"SUCCESS\",\"agentId\":\"P\",\"token\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_SUPERSEDED"));
        mockMvc.perform(get("/api/devices/d1")).andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0));

        // Q 成功回执才更新固件，且只计一个样本
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"req-rc-q\",\"result\":\"SUCCESS\",\"agentId\":\"Q\",\"token\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.resultAgentId").value("Q"))
                .andExpect(jsonPath("$.resultToken").value(2));
        mockMvc.perform(get("/api/devices/d1")).andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1))
                .andExpect(jsonPath("$.roundFailed").value(0));

        // 重放 P 的首次领取：返回 token1 快照，但不恢复其有效性
        String replayLease = acquire(taskId, "req-lease-p", "P").andReturn()
                .getResponse().getContentAsString();
        assertThat(replayLease).isEqualTo(firstLease);
        // P 用新 requestId 领取已终结任务：409，不能夺回
        mockMvc.perform(post("/api/tasks/" + taskId + "/lease").contentType("application/json")
                        .content("{\"requestId\":\"req-lease-p2\",\"agentId\":\"P\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_TERMINATED"));
        // 已终结不允许续租
        mockMvc.perform(post("/api/tasks/" + taskId + "/lease/renew").contentType("application/json")
                        .content("{\"requestId\":\"req-ren-p\",\"agentId\":\"P\",\"token\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_TERMINATED"));
        // P 旧 token 改失败结果：409，统计不变
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"req-rc-p2\",\"result\":\"FAILED\",\"agentId\":\"P\",\"token\":1}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1))
                .andExpect(jsonPath("$.roundFailed").value(0));
    }

    @Test
    void 续租_本人当前令牌延长期限_到期或非本人409() throws Exception {
        registerDevice("r-dev", "d1");
        createRelease("r-rel");
        long taskId = pullTask("r-pull", "d1");
        acquire(taskId, "req-lease-p", "P").andExpect(status().isOk());

        // 29 秒时仍有效：P 续租成功，到期时刻改为当前时刻后 30 秒
        clock.advance(Duration.ofSeconds(29));
        mockMvc.perform(post("/api/tasks/" + taskId + "/lease/renew").contentType("application/json")
                        .content("{\"requestId\":\"req-ren-ok\",\"agentId\":\"P\",\"token\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(1))
                .andExpect(jsonPath("$.agentId").value("P"))
                .andExpect(jsonPath("$.expiresAtUtc").value("2026-10-08T00:00:59Z"));

        // 他人不能续租
        mockMvc.perform(post("/api/tasks/" + taskId + "/lease/renew").contentType("application/json")
                        .content("{\"requestId\":\"req-ren-q\",\"agentId\":\"Q\",\"token\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_HOLDER_MISMATCH"));
        // 错误令牌不能续租
        mockMvc.perform(post("/api/tasks/" + taskId + "/lease/renew").contentType("application/json")
                        .content("{\"requestId\":\"req-ren-bad\",\"agentId\":\"P\",\"token\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_HOLDER_MISMATCH"));

        // 到达到期时刻（左闭右开，再走 30 秒）不能续租
        clock.advance(Duration.ofSeconds(30));
        mockMvc.perform(post("/api/tasks/" + taskId + "/lease/renew").contentType("application/json")
                        .content("{\"requestId\":\"req-ren-exp\",\"agentId\":\"P\",\"token\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_EXPIRED"));

        // 从未领取的任务不能续租
        registerDevice("r-dev2", "d2");
        long task2 = pullTask("r-pull2", "d2");
        mockMvc.perform(post("/api/tasks/" + task2 + "/lease/renew").contentType("application/json")
                        .content("{\"requestId\":\"req-ren-none\",\"agentId\":\"P\",\"token\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_NOT_FOUND"));
    }

    @Test
    void 有效期左闭右开_到期前不可抢占_到期时刻可接管_本人重领返回当前租约() throws Exception {
        registerDevice("r-dev", "d1");
        createRelease("r-rel");
        long taskId = pullTask("r-pull", "d1");
        acquire(taskId, "req-lease-p", "P").andExpect(status().isOk());

        // 到期前 1 秒：Q 不能抢占；P 再次领取返回当前租约，令牌不增加
        clock.advance(Duration.ofSeconds(29));
        mockMvc.perform(post("/api/tasks/" + taskId + "/lease").contentType("application/json")
                        .content("{\"requestId\":\"req-lease-q\",\"agentId\":\"Q\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_HELD_BY_OTHER"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/lease").contentType("application/json")
                        .content("{\"requestId\":\"req-lease-p-again\",\"agentId\":\"P\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(1))
                .andExpect(jsonPath("$.expiresAtUtc").value("2026-10-08T00:00:30Z"));

        // 恰为到期时刻：Q 可接管，令牌加一
        clock.advance(Duration.ofSeconds(1));
        mockMvc.perform(post("/api/tasks/" + taskId + "/lease").contentType("application/json")
                        .content("{\"requestId\":\"req-lease-q2\",\"agentId\":\"Q\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value("Q"))
                .andExpect(jsonPath("$.token").value(2));
    }

    @Test
    void 租约模式旧入口不能绕过_凭据不完整400_失败回执同样受保护() throws Exception {
        registerDevice("r-dev", "d1");
        long releaseId = createRelease("r-rel");
        long taskId = pullTask("r-pull", "d1");
        acquire(taskId, "req-lease-p", "P").andExpect(status().isOk());

        // 旧入口（无凭据）不能绕过租约模式：409，统计不变
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"req-rc-old\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_REQUIRED"));
        // 只给 agentId：400
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"req-rc-half1\",\"result\":\"SUCCESS\",\"agentId\":\"P\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEASE_CREDENTIALS_INCOMPLETE"));
        // 只给 token：400
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"req-rc-half2\",\"result\":\"SUCCESS\",\"token\":1}"))
                .andExpect(status().isBadRequest());
        // 非持有人 FAILED 回执也被拒绝，设备与统计不变
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"req-rc-q\",\"result\":\"FAILED\",\"agentId\":\"Q\",\"token\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_HOLDER_MISMATCH"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(0))
                .andExpect(jsonPath("$.roundSuccess").value(0));
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?",
                String.class, taskId)).isEqualTo("PENDING");
    }

    @Test
    void 成功冻结实际代理与令牌_接管者不能冒充_同代理同结果重放不重复计数() throws Exception {
        registerDevice("r-dev", "d1");
        long releaseId = createRelease("r-rel");
        long taskId = pullTask("r-pull", "d1");
        acquire(taskId, "req-lease-p", "P").andExpect(status().isOk());

        // P 成功终结，冻结 P/token1
        receipt(taskId, "req-rc-p", "SUCCESS", "P", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultAgentId").value("P"))
                .andExpect(jsonPath("$.resultToken").value(1));

        // 到期后 Q 领取 token2
        clock.advance(Duration.ofSeconds(30));
        acquire(taskId, "req-lease-q", "Q").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_TERMINATED"));

        // Q 凭据不能冒充回执
        receipt(taskId, "req-rc-q", "SUCCESS", "Q", 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_HOLDER_MISMATCH"));
        // P 用 token1 改结果：409
        receipt(taskId, "req-rc-pf", "FAILED", "P", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_RESULT_CONFLICT"));
        // P 同结果重放（新 requestId）：成功但不重复计数
        receipt(taskId, "req-rc-p2", "SUCCESS", "P", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1));
        // 旧入口对租约模式终结任务仍 409
        receipt(taskId, "req-rc-old", "SUCCESS", null, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_REQUIRED"));
    }

    @Test
    void 失败终结同样冻结代理_新代理不能再领取或写回() throws Exception {
        registerDevice("r-dev", "d1");
        long releaseId = createRelease("r-rel");
        long taskId = pullTask("r-pull", "d1");
        acquire(taskId, "req-lease-p", "P").andExpect(status().isOk());
        receipt(taskId, "req-rc-p", "FAILED", "P", 1).andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/d1")).andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1));

        clock.advance(Duration.ofSeconds(30));
        acquire(taskId, "req-lease-q", "Q").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_TERMINATED"));
        receipt(taskId, "req-rc-q", "SUCCESS", "Q", 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_HOLDER_MISMATCH"));
        receipt(taskId, "req-rc-q-fail", "FAILED", "Q", 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_HOLDER_MISMATCH"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1));
    }

    @Test
    void 取消与租约串行_已领取任务取消后领取续租回执均409_暂停期间既有租约可回执() throws Exception {
        registerDevice("r-dev", "d1");
        long releaseId = createRelease("r-rel");
        long taskId = pullTask("r-pull", "d1");
        acquire(taskId, "req-lease-p", "P").andExpect(status().isOk());

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"req-cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        acquire(taskId, "req-lease-q", "Q").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        renew(taskId, "req-ren-p", "P", 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        receipt(taskId, "req-rc-p", "SUCCESS", "P", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        mockMvc.perform(get("/api/devices/d1")).andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // PAUSED 发布单的既有任务租约仍可领取与回执（沿用暂停允许既有任务回执的规则）
        registerDevice("r-dev2", "d2");
        registerDevice("r-dev3", "d3");
        long release2 = createRelease("r-rel2");
        long t2 = pullTask("r-pull2", "d2");
        long t3 = pullTask("r-pull3", "d3");
        acquire(t2, "req-lease2", "P").andExpect(status().isOk());
        acquire(t3, "req-lease3", "P").andExpect(status().isOk());
        receipt(t2, "req-fail2", "FAILED", "P", 1).andExpect(status().isOk());
        receipt(t3, "req-fail3", "FAILED", "P", 1).andExpect(status().isOk());
        mockMvc.perform(get("/api/releases/" + release2 + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));
        // 暂停后既有租约任务的同结果重复回执仍受理且不重复计数
        receipt(t3, "req-fail3-dup", "FAILED", "P", 1).andExpect(status().isOk());
        mockMvc.perform(get("/api/releases/" + release2 + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(2));
    }

    @Test
    void 领取续租幂等_同键同参回首次完整响应_异参409_失败不占键_查询不自动领取() throws Exception {
        registerDevice("r-dev", "d1");
        createRelease("r-rel");
        long taskId = pullTask("r-pull", "d1");

        MvcResult first = acquire(taskId, "req-lease", "P").andExpect(status().isOk()).andReturn();
        // 同 requestId 同参重放：返回首次完整响应（快照仍是 token1/00:00:30），不延长到期
        clock.advance(Duration.ofSeconds(10));
        MvcResult replay = mockMvc.perform(post("/api/tasks/" + taskId + "/lease").contentType("application/json")
                        .content("{\"requestId\":\"req-lease\",\"agentId\":\"P\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(1))
                .andExpect(jsonPath("$.expiresAtUtc").value("2026-10-08T00:00:30Z"))
                .andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());

        // 同 requestId 异参：409
        mockMvc.perform(post("/api/tasks/" + taskId + "/lease").contentType("application/json")
                        .content("{\"requestId\":\"req-lease\",\"agentId\":\"Q\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 续租幂等：同键同参返回首次续租响应
        clock.setInstant(Instant.parse("2026-10-08T00:00:20Z"));
        MvcResult renewFirst = renew(taskId, "req-renew", "P", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAtUtc").value("2026-10-08T00:00:50Z"))
                .andReturn();
        clock.setInstant(Instant.parse("2026-10-08T00:00:25Z"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/lease/renew").contentType("application/json")
                        .content("{\"requestId\":\"req-renew\",\"agentId\":\"P\",\"token\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAtUtc").value("2026-10-08T00:00:50Z"));
        assertThat(renewFirst.getResponse().getContentAsString()).contains("\"token\":1");

        // 失败不占键：先用错误令牌触发 409，再用同 requestId 正确令牌成功
        renew(taskId, "req-renew2", "P", 9).andExpect(status().isConflict());
        renew(taskId, "req-renew2", "P", 1).andExpect(status().isOk());

        // 查询（拉取）不自动领取：任务租约字段保持已领取状态不变，无新 token
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"req-pull-again\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(taskId))
                .andExpect(jsonPath("$.task.leaseAgentId").value("P"))
                .andExpect(jsonPath("$.task.leaseToken").value(1));

        // 任务不存在：404
        acquire(9999, "req-404", "P").andExpect(status().isNotFound());
        renew(9999, "req-404-r", "P", 1).andExpect(status().isNotFound());
    }

    @Test
    void 未领取任务保持旧入口_领取后必须带凭据() throws Exception {
        registerDevice("r-dev", "d1");
        long releaseId = createRelease("r-rel");
        long taskId = pullTask("r-pull", "d1");

        // 从未领取：旧入口回执仍可终结
        receipt(taskId, "req-rc", "SUCCESS", null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(jdbc.queryForObject("SELECT result_agent_id FROM rollout_task WHERE id = ?",
                String.class, taskId)).isNull();
        assertThat(jdbc.queryForObject("SELECT result_token FROM rollout_task WHERE id = ?",
                Object.class, taskId)).isNull();
        mockMvc.perform(get("/api/devices/d1")).andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(1));
    }
}
