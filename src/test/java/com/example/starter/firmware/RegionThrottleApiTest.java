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
 * 区域带宽限流与公平排队 API 测试（H2 内存库，MODE=MySQL）。
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
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM region_wait_record");
        jdbc.update("DELETE FROM region_throttle_event");
    }

    private void registerDevice(String requestId, String deviceId, String model, String version, int bucket,
                                String region) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                        {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d,"region":"%s"}
                        """.formatted(requestId, deviceId, model, version, bucket, region)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, int ratio, Integer regionLimit) throws Exception {
        String limitJson = regionLimit == null ? "null" : regionLimit.toString();
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d,"regionLimit":%s}
                        """.formatted(requestId, model, ratio, limitJson)))
                .andExpect(status().isOk())
                .andReturn();
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.releaseId");
        return id.longValue();
    }

    private MvcResult pull(String deviceId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/pull").contentType("application/json")
                        .content("{\"requestId\":\"" + requestId + "\"}"))
                .andReturn();
    }

    private void receipt(long taskId, String requestId, String result) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"" + requestId + "\",\"result\":\"" + result + "\"}"))
                .andExpect(status().isOk());
    }

    private long taskIdOf(MvcResult result) throws Exception {
        Number id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.task.taskId");
        return id.longValue();
    }

    private long inFlight(long releaseId, String region) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task t JOIN device d ON d.device_id = t.device_id"
                        + " WHERE t.release_id = ? AND d.region = ? AND t.status = 'PENDING'",
                Long.class, releaseId, region);
        return count;
    }

    @Test
    void 限流_达到区域上限返回THROTTLED_不下发不改状态_其他区域不受影响() throws Exception {
        registerDevice("rd-a", "d-a", "m1", "1.0.0", 1, "cn-north");
        registerDevice("rd-b", "d-b", "m1", "1.0.0", 1, "cn-north");
        registerDevice("rd-c", "d-c", "m1", "1.0.0", 1, "cn-south");
        long releaseId = createRelease("rr-1", "m1", 100, 1);

        // 第一个设备正常下发
        MvcResult first = pull("d-a", "rp-1");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(first.getResponse().getContentAsString()).contains("\"result\":\"DISPATCHED\"");

        // 同区域第二个设备被限流：200 + THROTTLED，无任务
        mockMvc.perform(post("/api/devices/d-b/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("THROTTLED"))
                .andExpect(jsonPath("$.task").doesNotExist());

        // 不下发任务、不改变设备或任务状态：任务仅一条且设备版本不变
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);
        mockMvc.perform(get("/api/devices/d-b"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 被限流不计入失败率样本：无 FAILED 任务，限流结果不产生任何任务记录
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE status = 'FAILED'", Long.class)).isZero();

        // 其他区域不受该区域上限影响
        mockMvc.perform(post("/api/devices/d-c/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DISPATCHED"));

        // 限流写入等待记录与限流历史
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_wait_record", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_throttle_event", Long.class)).isEqualTo(1);
        assertThat(inFlight(releaseId, "cn-north")).isEqualTo(1);
        assertThat(inFlight(releaseId, "cn-south")).isEqualTo(1);
    }

    @Test
    void 限流_THROTTLED不占幂等键_空位释放后同requestId重拉可下发() throws Exception {
        registerDevice("rd-a", "d-a", "m1", "1.0.0", 1, "cn-north");
        registerDevice("rd-b", "d-b", "m1", "1.0.0", 1, "cn-north");
        createRelease("rr-1", "m1", 100, 1);

        long taskA = taskIdOf(pull("d-a", "rp-1"));

        // d-b 被限流，requestId 不占键
        mockMvc.perform(post("/api/devices/d-b/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-2\"}"))
                .andExpect(jsonPath("$.result").value("THROTTLED"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'rp-2'", Long.class)).isZero();

        // d-a 完成释放名额后，d-b 用同一 requestId 重拉可下发
        receipt(taskA, "rp-3", "SUCCESS");
        MvcResult second = pull("d-b", "rp-2");
        assertThat(second.getResponse().getContentAsString()).contains("\"result\":\"DISPATCHED\"");

        // 下发后同键同参重放首次结果
        MvcResult replay = pull("d-b", "rp-2");
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(second.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);
    }

    @Test
    void 回执_成功与失败都释放名额_重复回执不重复减少计数() throws Exception {
        registerDevice("rd-a", "d-a", "m1", "1.0.0", 1, "cn-north");
        registerDevice("rd-b", "d-b", "m1", "1.0.0", 1, "cn-north");
        registerDevice("rd-c", "d-c", "m1", "1.0.0", 1, "cn-north");
        long releaseId = createRelease("rr-1", "m1", 100, 1);

        long taskA = taskIdOf(pull("d-a", "rp-1"));
        assertThat(inFlight(releaseId, "cn-north")).isEqualTo(1);

        // FAILED 回执释放名额
        receipt(taskA, "rp-2", "FAILED");
        assertThat(inFlight(releaseId, "cn-north")).isZero();

        // 同结果重复回执成功且不重复减少计数（计数已为0，不得出现漂移）
        receipt(taskA, "rp-3", "FAILED");
        assertThat(inFlight(releaseId, "cn-north")).isZero();

        // 名额已释放：d-b 可下发，d-c 仍被限流，证明计数与实际下发严格一致
        mockMvc.perform(post("/api/devices/d-b/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-4\"}"))
                .andExpect(jsonPath("$.result").value("DISPATCHED"));
        mockMvc.perform(post("/api/devices/d-c/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-5\"}"))
                .andExpect(jsonPath("$.result").value("THROTTLED"));
        assertThat(inFlight(releaseId, "cn-north")).isEqualTo(1);
    }

    @Test
    void 公平排队_按等待时刻先后释放名额_后到者不能插队() throws Exception {
        registerDevice("rd-a", "d-a", "m1", "1.0.0", 1, "cn-north");
        registerDevice("rd-1", "d-1", "m1", "1.0.0", 1, "cn-north");
        registerDevice("rd-2", "d-2", "m1", "1.0.0", 1, "cn-north");
        long releaseId = createRelease("rr-1", "m1", 100, 1);

        long taskA = taskIdOf(pull("d-a", "rp-1"));
        // d-1 先被限流，d-2 后被限流（设备名字典序与时刻顺序一致，同时刻亦确定）
        mockMvc.perform(post("/api/devices/d-1/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-2\"}"))
                .andExpect(jsonPath("$.result").value("THROTTLED"));
        mockMvc.perform(post("/api/devices/d-2/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-3\"}"))
                .andExpect(jsonPath("$.result").value("THROTTLED"));

        // 等待清单按限流时刻从早到晚排列
        mockMvc.perform(get("/api/releases/" + releaseId + "/regions/cn-north/waiting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waiting.length()").value(2))
                .andExpect(jsonPath("$.waiting[0].deviceId").value("d-1"))
                .andExpect(jsonPath("$.waiting[1].deviceId").value("d-2"));

        // 释放一个名额
        receipt(taskA, "rp-4", "SUCCESS");

        // d-2 先拉：排在 d-1 之后，仍被限流，且最近一次限流时刻被刷新
        mockMvc.perform(post("/api/devices/d-2/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-5\"}"))
                .andExpect(jsonPath("$.result").value("THROTTLED"));
        assertThat(inFlight(releaseId, "cn-north")).isZero();

        // d-1 拉取：获得名额，等待记录移除
        mockMvc.perform(post("/api/devices/d-1/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-6\"}"))
                .andExpect(jsonPath("$.result").value("DISPATCHED"));
        assertThat(inFlight(releaseId, "cn-north")).isEqualTo(1);
        mockMvc.perform(get("/api/releases/" + releaseId + "/regions/cn-north/waiting"))
                .andExpect(jsonPath("$.waiting.length()").value(1))
                .andExpect(jsonPath("$.waiting[0].deviceId").value("d-2"));
    }

    @Test
    void 公平排队_同一时刻按设备标识字典序() throws Exception {
        registerDevice("rd-a", "d-a", "m1", "1.0.0", 1, "cn-north");
        registerDevice("rd-b", "d-b", "m1", "1.0.0", 1, "cn-north");
        long releaseId = createRelease("rr-1", "m1", 100, 1);

        // 预置同一限流时刻的等待记录（可控时钟：直接写入相同时刻）
        jdbc.update("INSERT INTO region_wait_record (release_id, region, device_id, last_throttled_at)"
                        + " VALUES (?, 'cn-north', 'd-b', '2026-01-01 10:00:00'),"
                        + " (?, 'cn-north', 'd-a', '2026-01-01 10:00:00')",
                releaseId, releaseId);

        // 字典序靠后的 d-b 不能获得名额
        mockMvc.perform(post("/api/devices/d-b/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-1\"}"))
                .andExpect(jsonPath("$.result").value("THROTTLED"));
        // 字典序靠前的 d-a 获得名额
        mockMvc.perform(post("/api/devices/d-a/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-2\"}"))
                .andExpect(jsonPath("$.result").value("DISPATCHED"));
        assertThat(inFlight(releaseId, "cn-north")).isEqualTo(1);
    }

    @Test
    void 公平排队_未曾被限流的设备不排队_有空位直接下发() throws Exception {
        registerDevice("rd-a", "d-a", "m1", "1.0.0", 1, "cn-north");
        registerDevice("rd-b", "d-b", "m1", "1.0.0", 1, "cn-north");
        registerDevice("rd-c", "d-c", "m1", "1.0.0", 1, "cn-north");
        createRelease("rr-1", "m1", 100, 1);

        long taskA = taskIdOf(pull("d-a", "rp-1"));
        // d-b 被限流进入等待
        mockMvc.perform(post("/api/devices/d-b/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-2\"}"))
                .andExpect(jsonPath("$.result").value("THROTTLED"));
        // 释放名额
        receipt(taskA, "rp-3", "SUCCESS");
        // 未曾被限流的 d-c 不排队，有空位直接下发，即使 d-b 在等待
        mockMvc.perform(post("/api/devices/d-c/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-4\"}"))
                .andExpect(jsonPath("$.result").value("DISPATCHED"));
        // 等待中的 d-b 此时仍受限
        mockMvc.perform(post("/api/devices/d-b/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-5\"}"))
                .andExpect(jsonPath("$.result").value("THROTTLED"));
    }

    @Test
    void 区域上限_修改携带expectedVersion_冲突409_只影响后续拉取() throws Exception {
        registerDevice("rd-a", "d-a", "m1", "1.0.0", 1, "cn-north");
        registerDevice("rd-b", "d-b", "m1", "1.0.0", 1, "cn-north");
        registerDevice("rd-c", "d-c", "m1", "1.0.0", 1, "cn-north");
        long releaseId = createRelease("rr-1", "m1", 100, 1);

        long taskA = taskIdOf(pull("d-a", "rp-1"));

        // expectedVersion 不匹配：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limit").contentType("application/json")
                        .content("{\"requestId\":\"rl-1\",\"expectedVersion\":9,\"regionLimit\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 正确版本：成功，版本加一
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limit").contentType("application/json")
                        .content("{\"requestId\":\"rl-2\",\"expectedVersion\":1,\"regionLimit\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.regionLimit").value(2));

        // 同 requestId 同参重放：版本不再增加
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limit").contentType("application/json")
                        .content("{\"requestId\":\"rl-2\",\"expectedVersion\":1,\"regionLimit\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 同 requestId 异参：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limit").contentType("application/json")
                        .content("{\"requestId\":\"rl-2\",\"expectedVersion\":1,\"regionLimit\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 上限提高到2后：d-b 可下发（只影响后续拉取判定）
        mockMvc.perform(post("/api/devices/d-b/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-2\"}"))
                .andExpect(jsonPath("$.result").value("DISPATCHED"));

        // 上限降回1：已下发任务不受影响，新拉取被限流
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limit").contentType("application/json")
                        .content("{\"requestId\":\"rl-3\",\"expectedVersion\":2,\"regionLimit\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionLimit").value(1));
        mockMvc.perform(post("/api/devices/d-c/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-3\"}"))
                .andExpect(jsonPath("$.result").value("THROTTLED"));
        // 已下发任务正常回执
        receipt(taskA, "rp-4", "SUCCESS");
        mockMvc.perform(get("/api/devices/d-a"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 清除限流（null）：不再限流
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limit").contentType("application/json")
                        .content("{\"requestId\":\"rl-4\",\"expectedVersion\":3,\"regionLimit\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionLimit").doesNotExist());
        mockMvc.perform(post("/api/devices/d-c/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-5\"}"))
                .andExpect(jsonPath("$.result").value("DISPATCHED"));
    }

    @Test
    void 区域上限_取值校验_取消后不可修改() throws Exception {
        long releaseId = createRelease("rr-1", "m1", 100, 1);

        // 越界：0 与 1001 均 400
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limit").contentType("application/json")
                        .content("{\"requestId\":\"rl-1\",\"expectedVersion\":1,\"regionLimit\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limit").contentType("application/json")
                        .content("{\"requestId\":\"rl-2\",\"expectedVersion\":1,\"regionLimit\":1001}"))
                .andExpect(status().isBadRequest());
        // 创建时越界同样 400
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"rl-x","model":"m2","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":10,"regionLimit":0}
                        """))
                .andExpect(status().isBadRequest());

        // 取消后不可修改
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"rl-3\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/releases/" + releaseId + "/region-limit").contentType("application/json")
                        .content("{\"requestId\":\"rl-4\",\"expectedVersion\":1,\"regionLimit\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));

        // 发布单不存在：404
        mockMvc.perform(post("/api/releases/9999/region-limit").contentType("application/json")
                        .content("{\"requestId\":\"rl-5\",\"expectedVersion\":1,\"regionLimit\":5}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 不限流_未配置区域上限时按现有比例规则下发() throws Exception {
        for (int i = 0; i < 5; i++) {
            registerDevice("rd-" + i, "d-" + i, "m1", "1.0.0", 1, "cn-north");
        }
        long releaseId = createRelease("rr-1", "m1", 100, null);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/devices/d-" + i + "/pull").contentType("application/json")
                            .content("{\"requestId\":\"rp-" + i + "\"}"))
                    .andExpect(jsonPath("$.result").value("DISPATCHED"));
        }
        assertThat(inFlight(releaseId, "cn-north")).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_wait_record", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM region_throttle_event", Long.class)).isZero();
    }

    @Test
    void 查询_区域进行中任务数_等待清单_限流历史() throws Exception {
        registerDevice("rd-a", "d-a", "m1", "1.0.0", 1, "cn-north");
        registerDevice("rd-b", "d-b", "m1", "1.0.0", 1, "cn-north");
        registerDevice("rd-c", "d-c", "m1", "1.0.0", 1, "cn-south");
        long releaseId = createRelease("rr-1", "m1", 100, 1);

        pull("d-a", "rp-1");
        pull("d-b", "rp-2"); // THROTTLED
        pull("d-b", "rp-3"); // THROTTLED 第二次，历史追加
        pull("d-c", "rp-4");

        // 区域总览：各区域进行中任务数与等待数
        mockMvc.perform(get("/api/releases/" + releaseId + "/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(releaseId))
                .andExpect(jsonPath("$.regionLimit").value(1))
                .andExpect(jsonPath("$.regions.length()").value(2))
                .andExpect(jsonPath("$.regions[0].region").value("cn-north"))
                .andExpect(jsonPath("$.regions[0].inFlight").value(1))
                .andExpect(jsonPath("$.regions[0].waiting").value(1))
                .andExpect(jsonPath("$.regions[1].region").value("cn-south"))
                .andExpect(jsonPath("$.regions[1].inFlight").value(1))
                .andExpect(jsonPath("$.regions[1].waiting").value(0));

        // 等待清单
        mockMvc.perform(get("/api/releases/" + releaseId + "/regions/cn-north/waiting"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waiting.length()").value(1))
                .andExpect(jsonPath("$.waiting[0].deviceId").value("d-b"))
                .andExpect(jsonPath("$.waiting[0].lastThrottledAt").exists());

        // 限流历史：两次限流各一条
        mockMvc.perform(get("/api/releases/" + releaseId + "/regions/cn-north/throttles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.throttles.length()").value(2))
                .andExpect(jsonPath("$.throttles[0].deviceId").value("d-b"))
                .andExpect(jsonPath("$.throttles[1].deviceId").value("d-b"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/regions/cn-south/throttles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.throttles.length()").value(0));

        // 发布单不存在：404
        mockMvc.perform(get("/api/releases/9999/regions"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/releases/9999/regions/cn-north/waiting"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/releases/9999/regions/cn-north/throttles"))
                .andExpect(status().isNotFound());
    }
}
