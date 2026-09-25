package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 区域带宽限流与公平排队 API 测试（H2 内存库，MODE=MySQL）。
 * 覆盖：上限限流 THROTTLED、不计失败样本、公平排队顺序、配置版本冲突、查询接口与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegionThrottleApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_region_limit");
        jdbc.update("DELETE FROM region_wait_record");
        jdbc.update("DELETE FROM region_throttle_event");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private void registerDevice(String requestId, String deviceId, String region) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":1,"region":"%s"}
                """.formatted(requestId, deviceId, region)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """.formatted(requestId)))
                .andExpect(status().isOk())
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.releaseId");
        return id.longValue();
    }

    private String setLimits(long releaseId, String requestId, int expectedVersion, String limitsJson)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases/" + releaseId + "/region-limits")
                        .contentType("application/json")
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"limits":%s}
                                """.formatted(requestId, expectedVersion, limitsJson)))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private MvcResult pull(String deviceId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/pull").contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andReturn();
    }

    private long taskIdOf(MvcResult pullResult) throws Exception {
        Number id = com.jayway.jsonpath.JsonPath.read(pullResult.getResponse().getContentAsString(),
                "$.task.taskId");
        return id.longValue();
    }

    private void receipt(long taskId, String requestId, String result) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"result\":\"%s\"}".formatted(requestId, result)))
                .andExpect(status().isOk());
    }

    private void setWaitedAt(long releaseId, String deviceId, String waitedAt) {
        jdbc.update("UPDATE region_wait_record SET waited_at = ? WHERE release_id = ? AND device_id = ?",
                Timestamp.valueOf(java.time.LocalDateTime.parse(waitedAt)), releaseId, deviceId);
    }

    @Test
    void 限流主流程_达到上限返回THROTTLED_回执释放名额后等待者获得任务() throws Exception {
        registerDevice("r-d1", "d1", "cn-north");
        registerDevice("r-d2", "d2", "cn-north");
        registerDevice("r-d3", "d3", "cn-north");
        long releaseId = createRelease("r-rel");
        setLimits(releaseId, "r-lim", 1, "[{\"region\":\"cn-north\",\"maxInFlight\":2}]");

        MvcResult pull1 = pull("d1", "r-p1");
        assertThat(pull1.getResponse().getContentAsString()).contains("\"result\":\"ISSUED\"");
        pull("d2", "r-p2");
        long task1 = taskIdOf(pull1);

        // 区域达到上限：THROTTLED，不下发任务、不改变设备或任务状态
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("THROTTLED"))
                .andExpect(jsonPath("$.task").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);
        mockMvc.perform(get("/api/devices/d3"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 查询：进行中数、等待清单、限流历史
        mockMvc.perform(get("/api/releases/" + releaseId + "/regions/cn-north/in-flight"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inFlight").value(2))
                .andExpect(jsonPath("$.maxInFlight").value(2));
        mockMvc.perform(get("/api/releases/" + releaseId + "/regions/cn-north/waiting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices.length()").value(1))
                .andExpect(jsonPath("$.devices[0].deviceId").value("d3"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/regions/cn-north/throttle-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].deviceId").value("d3"));

        // 回执 SUCCESS：进行中计数减一，等待中的 d3 获得名额
        receipt(task1, "r-rc1", "SUCCESS");
        mockMvc.perform(get("/api/releases/" + releaseId + "/regions/cn-north/in-flight"))
                .andExpect(jsonPath("$.inFlight").value(1));
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ISSUED"))
                .andExpect(jsonPath("$.task.status").value("PENDING"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/regions/cn-north/waiting"))
                .andExpect(jsonPath("$.devices.length()").value(0));
        mockMvc.perform(get("/api/releases/" + releaseId + "/regions/cn-north/in-flight"))
                .andExpect(jsonPath("$.inFlight").value(2));
    }

    @Test
    void 限流不计失败样本_重复限流刷新等待时刻并累计历史_同键重放不重复记录() throws Exception {
        registerDevice("r-d1", "d1", "cn-north");
        registerDevice("r-d2", "d2", "cn-north");
        long releaseId = createRelease("r-rel");
        setLimits(releaseId, "r-lim", 1, "[{\"region\":\"cn-north\",\"maxInFlight\":1}]");
        pull("d1", "r-p1");

        // 同一设备两次限流（不同 requestId）：等待记录仅一条且时刻刷新，历史累计两条
        pull("d2", "r-p2a");
        String firstWaitedAt = jdbc.queryForObject(
                "SELECT waited_at FROM region_wait_record WHERE device_id = 'd2'", String.class);
        pull("d2", "r-p2b");
        String secondWaitedAt = jdbc.queryForObject(
                "SELECT waited_at FROM region_wait_record WHERE device_id = 'd2'", String.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM region_wait_record WHERE device_id = 'd2'", Long.class)).isEqualTo(1);
        assertThat(secondWaitedAt).isNotEqualTo(firstWaitedAt);
        mockMvc.perform(get("/api/releases/" + releaseId + "/regions/cn-north/throttle-events"))
                .andExpect(jsonPath("$.events.length()").value(2));

        // 同 requestId 重放首次 THROTTLED 结果，不追加历史
        MvcResult replay = pull("d2", "r-p2b");
        assertThat(replay.getResponse().getContentAsString()).contains("\"result\":\"THROTTLED\"");
        mockMvc.perform(get("/api/releases/" + releaseId + "/regions/cn-north/throttle-events"))
                .andExpect(jsonPath("$.events.length()").value(2));

        // 限流不产生任务、不产生失败样本：任务仅 d1 一条且为 PENDING，无 FAILED
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE status = 'FAILED'", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE status = 'PENDING'", Long.class)).isEqualTo(1);
    }

    @Test
    void 公平排队_先限流者先获名额_同刻按设备ID字典序() throws Exception {
        registerDevice("r-d1", "d1", "cn-north");
        registerDevice("r-d2", "d2", "cn-north");
        registerDevice("r-d3", "d3", "cn-north");
        long releaseId = createRelease("r-rel");
        setLimits(releaseId, "r-lim", 1, "[{\"region\":\"cn-north\",\"maxInFlight\":1}]");

        long task1 = taskIdOf(pull("d1", "r-p1"));
        pull("d2", "r-p2");
        pull("d3", "r-p3");
        // 控制等待时刻：d3 早于 d2
        setWaitedAt(releaseId, "d2", "2026-01-01T10:00:00");
        setWaitedAt(releaseId, "d3", "2026-01-01T09:00:00");

        receipt(task1, "r-rc1", "SUCCESS");
        // d2 前面有更早的 d3：仍被限流
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2b\"}"))
                .andExpect(jsonPath("$.result").value("THROTTLED"));
        // d3 最早：获得名额
        MvcResult pull3 = mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3b\"}"))
                .andExpect(jsonPath("$.result").value("ISSUED"))
                .andReturn();
        long task3 = taskIdOf(pull3);

        // 同一等待时刻：按设备ID字典序，dev-a 优先于 dev-b
        registerDevice("r-da", "dev-a", "cn-north");
        registerDevice("r-db", "dev-b", "cn-north");
        pull("dev-b", "r-pb");
        pull("dev-a", "r-pa");
        setWaitedAt(releaseId, "dev-a", "2026-01-02T00:00:00");
        setWaitedAt(releaseId, "dev-b", "2026-01-02T00:00:00");
        receipt(task3, "r-rc3", "FAILED");
        mockMvc.perform(post("/api/devices/dev-b/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pb2\"}"))
                .andExpect(jsonPath("$.result").value("THROTTLED"));
        mockMvc.perform(post("/api/devices/dev-a/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pa2\"}"))
                .andExpect(jsonPath("$.result").value("ISSUED"));
    }

    @Test
    void 未限流设备不排队_区域未配置上限不限流() throws Exception {
        registerDevice("r-d1", "d1", "cn-north");
        registerDevice("r-d2", "d2", "cn-north");
        registerDevice("r-d3", "d3", "cn-north");
        registerDevice("r-d4", "d4", "cn-north");
        registerDevice("r-e1", "e1", "cn-south");
        registerDevice("r-e2", "e2", "cn-south");
        registerDevice("r-e3", "e3", "cn-south");
        long releaseId = createRelease("r-rel");
        setLimits(releaseId, "r-lim", 1, "[{\"region\":\"cn-north\",\"maxInFlight\":2}]");

        long task1 = taskIdOf(pull("d1", "r-p1"));
        pull("d2", "r-p2");
        // d3 被限流进入等待
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3\"}"))
                .andExpect(jsonPath("$.result").value("THROTTLED"));

        // 释放一个名额后，未曾被限流的 d4 不排队，直接获得名额
        receipt(task1, "r-rc1", "SUCCESS");
        mockMvc.perform(post("/api/devices/d4/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p4\"}"))
                .andExpect(jsonPath("$.result").value("ISSUED"));
        // 名额再次被占满，等待中的 d3 仍被限流
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3b\"}"))
                .andExpect(jsonPath("$.result").value("THROTTLED"));

        // 未配置上限的 cn-south 区域不限流
        for (String device : new String[]{"e1", "e2", "e3"}) {
            mockMvc.perform(post("/api/devices/" + device + "/pull").contentType("application/json")
                            .content("{\"requestId\":\"r-p" + device + "\"}"))
                    .andExpect(jsonPath("$.result").value("ISSUED"));
        }
        mockMvc.perform(get("/api/releases/" + releaseId + "/regions/cn-south/in-flight"))
                .andExpect(jsonPath("$.inFlight").value(3))
                .andExpect(jsonPath("$.maxInFlight").doesNotExist());
    }

    @Test
    void 上限配置_版本冲突409_全量替换_只影响后续拉取_参数校验() throws Exception {
        registerDevice("r-d1", "d1", "cn-north");
        registerDevice("r-d2", "d2", "cn-north");
        registerDevice("r-d3", "d3", "cn-north");
        long releaseId = createRelease("r-rel");

        // expectedVersion 不匹配：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limits").contentType("application/json")
                        .content("""
                                {"requestId":"r-l0","expectedVersion":2,"limits":[{"region":"cn-north","maxInFlight":2}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 正确版本：成功，版本加一
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limits").contentType("application/json")
                        .content("""
                                {"requestId":"r-l1","expectedVersion":1,"limits":[{"region":"cn-north","maxInFlight":2}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.limits[0].region").value("cn-north"))
                .andExpect(jsonPath("$.limits[0].maxInFlight").value(2));

        // 同 requestId 同参重放：版本不再增加
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limits").contentType("application/json")
                        .content("""
                                {"requestId":"r-l1","expectedVersion":1,"limits":[{"region":"cn-north","maxInFlight":2}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 同 requestId 异参：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limits").contentType("application/json")
                        .content("""
                                {"requestId":"r-l1","expectedVersion":1,"limits":[{"region":"cn-north","maxInFlight":3}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 上限越界：400；重复区域：400
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limits").contentType("application/json")
                        .content("""
                                {"requestId":"r-l2","expectedVersion":2,"limits":[{"region":"cn-north","maxInFlight":0}]}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limits").contentType("application/json")
                        .content("""
                                {"requestId":"r-l3","expectedVersion":2,"limits":[{"region":"cn-north","maxInFlight":1001}]}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limits").contentType("application/json")
                        .content("""
                                {"requestId":"r-l4","expectedVersion":2,"limits":[{"region":"a","maxInFlight":1},{"region":"a","maxInFlight":2}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REGION"));

        // 两台设备占满上限 2；收窄为 1 只影响后续拉取，已下发任务保持 PENDING
        pull("d1", "r-p1");
        pull("d2", "r-p2");
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limits").contentType("application/json")
                        .content("""
                                {"requestId":"r-l5","expectedVersion":2,"limits":[{"region":"cn-north","maxInFlight":1}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3\"}"))
                .andExpect(jsonPath("$.result").value("THROTTLED"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE status = 'PENDING'", Long.class)).isEqualTo(2);

        // 全量替换为空列表：清除上限，后续拉取不再限流
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limits").contentType("application/json")
                        .content("""
                                {"requestId":"r-l6","expectedVersion":3,"limits":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.limits.length()").value(0));
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p3c\"}"))
                .andExpect(jsonPath("$.result").value("ISSUED"));

        // 发布单不存在：404
        mockMvc.perform(post("/api/releases/9999/region-limits").contentType("application/json")
                        .content("""
                                {"requestId":"r-l7","expectedVersion":1,"limits":[]}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void 上限配置_已取消发布单409_查询接口发布单不存在404() throws Exception {
        long releaseId = createRelease("r-rel");
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-c1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limits").contentType("application/json")
                        .content("""
                                {"requestId":"r-l1","expectedVersion":1,"limits":[{"region":"cn-north","maxInFlight":1}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));

        mockMvc.perform(get("/api/releases/9999/regions/cn-north/in-flight"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/releases/9999/regions/cn-north/waiting"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/releases/9999/regions/cn-north/throttle-events"))
                .andExpect(status().isNotFound());
    }
}
