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
 * 失败任务显式有限重试 API 测试（H2 内存库，MODE=MySQL）：
 * 主流程、尝试序号与前驱、3 次上限、各类 409/422/404/400、监控样本与设备版本语义、
 * 幂等重放（含期望版本）、取消/暂停交互与尝试历史。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareRetryTest {

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
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private long setupFailedFirstAttempt(String model, int threshold) throws Exception {
        return setupFailedFirstAttempt("d1", model, 100, threshold);
    }

    private long setupFailedFirstAttempt(String deviceId, String model, int sampleFloor, int threshold)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-dev","deviceId":"%s","model":"%s","currentVersion":"1.0.0","bucketNo":1}
                """.formatted(deviceId, model))).andExpect(status().isOk());
        MvcResult release = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"r-rel","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100,
                "sampleFloor":%d,"failureThresholdPercent":%d}
                """.formatted(model, sampleFloor, threshold)))
                .andExpect(status().isOk())
                .andReturn();
        long releaseId = idOf(release, "$.releaseId");
        MvcResult pull = mockMvc.perform(post("/api/devices/" + deviceId + "/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull\"}"))
                .andExpect(status().isOk()).andReturn();
        long taskId = idOf(pull, "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-fail\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.attemptNo").value(1))
                .andExpect(jsonPath("$.prevTaskId").doesNotExist());
        return releaseId;
    }

    @Test
    void 重试主流程_新尝试独立ID序号2指向前驱_拉取返回最新_不自动重开() throws Exception {
        long releaseId = setupFailedFirstAttempt("m1", 100);
        long t1 = jdbc.queryForObject(
                "SELECT id FROM rollout_task WHERE attempt_no = 1", Long.class);

        MvcResult retry = mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-retry\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptNo").value(2))
                .andExpect(jsonPath("$.prevTaskId").value((int) t1))
                .andReturn();
        long t2 = idOf(retry, "$.taskId");
        assertThat(t2).isNotEqualTo(t1);

        // 原任务及回执不可改写
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks")).
                andExpect(jsonPath("$.tasks.length()").value(2));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM rollout_task WHERE id = ?", String.class, t1)).isEqualTo("FAILED");

        // 拉取返回最新尝试（序号2）
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-pull2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(t2))
                .andExpect(jsonPath("$.task.attemptNo").value(2));

        // 未显式重试前不会自动重开：此时任务总数为 2（一次原始 + 一次重试）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);
    }

    @Test
    void 重试_成功回执才更新设备版本并计入新样本_不改版本号与比例() throws Exception {
        long releaseId = setupFailedFirstAttempt("m1", 100);
        long t1 = jdbc.queryForObject("SELECT id FROM rollout_task WHERE attempt_no = 1", Long.class);
        long t2 = idOf(mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-retry\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn(), "$.taskId");

        // 创建重试不增加发布版本、不改比例、不立即计入样本
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.ratio").value(100))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(0));

        // 重试成功：更新设备版本，并新增一个 SUCCESS 样本
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-ok\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.attemptNo").value(2));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(1))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void 重试_失败样本累加_原失败样本不扣回_同尝试重复回执不重计() throws Exception {
        long releaseId = setupFailedFirstAttempt("m1", 100);
        long t1 = jdbc.queryForObject("SELECT id FROM rollout_task WHERE attempt_no = 1", Long.class);
        long t2 = idOf(mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-retry\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn(), "$.taskId");

        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-f2\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk());
        // 原失败样本(1)不扣回，新尝试失败样本(1)累加
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(2))
                .andExpect(jsonPath("$.roundSuccess").value(0));

        // 同尝试重复回执（同结果）不重计
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-f2-dup\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(2));

        // 设备版本仍停留在来源版本
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));
    }

    @Test
    void 三次上限_第三次后重试422_可连续重试至序号3() throws Exception {
        long releaseId = setupFailedFirstAttempt("m1", 100);
        long t1 = jdbc.queryForObject("SELECT id FROM rollout_task WHERE attempt_no = 1", Long.class);

        long t2 = idOf(mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-rt2\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn(), "$.taskId");
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"r-f2\",\"result\":\"FAILED\"}")).andExpect(status().isOk());

        long t3 = idOf(mockMvc.perform(post("/api/tasks/" + t2 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-rt3\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptNo").value(3))
                .andExpect(jsonPath("$.prevTaskId").value((int) t2))
                .andReturn(), "$.taskId");
        mockMvc.perform(post("/api/tasks/" + t3 + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"r-f3\",\"result\":\"FAILED\"}")).andExpect(status().isOk());

        // 次数用尽：422
        mockMvc.perform(post("/api/tasks/" + t3 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-rt4\",\"expectedVersion\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RETRY_EXHAUSTED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(3);
        // 422 失败不占键：同 requestId 可用于别的合法请求（此处验证无新行且键未被锁）
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundFailed").value(3));
    }

    @Test
    void 重试_各失败分支_暂停取消PENDING_SUCCESS_非最新_版本不符_期望版本不符() throws Exception {
        // 场景一：PAUSED 发布单不能重试
        long pausedRelease = setupFailedFirstAttempt("pd", "mp", 1, 1);
        long pt1 = jdbc.queryForObject(
                "SELECT id FROM rollout_task WHERE release_id = ? AND device_id = 'pd' AND attempt_no = 1",
                Long.class, pausedRelease);
        // 单设备失败率 100%，threshold=1 已在首次失败时自动暂停
        mockMvc.perform(get("/api/releases/" + pausedRelease + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));
        mockMvc.perform(post("/api/tasks/" + pt1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"rp1\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));

        // 场景二：ACTIVE，PENDING 任务不能重试
        long activeRelease = setupFailedFirstAttempt("ma", 100);
        // 为另一设备建一个 PENDING 任务
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"d2","deviceId":"d2","model":"ma","currentVersion":"1.0.0","bucketNo":2}"""))
                .andExpect(status().isOk());
        long pending = idOf(mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}")).andReturn(), "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + pending + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"ra1\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FAILED"));

        // SUCCESS 任务不能重试
        mockMvc.perform(post("/api/tasks/" + pending + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"s2\",\"result\":\"SUCCESS\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + pending + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"ra2\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FAILED"));

        // 期望版本不符：409 VERSION_CONFLICT
        long at1 = jdbc.queryForObject(
                "SELECT id FROM rollout_task WHERE release_id = ? AND device_id = 'd1' AND attempt_no = 1",
                Long.class, activeRelease);
        mockMvc.perform(post("/api/tasks/" + at1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"ra3\",\"expectedVersion\":99}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 非最新失败任务：先给 d1 建出第2次尝试，再对旧的第1次重试
        long at2 = idOf(mockMvc.perform(post("/api/tasks/" + at1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"ra4\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn(), "$.taskId");
        mockMvc.perform(post("/api/tasks/" + at1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"ra5\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_LATEST_ATTEMPT"));

        // 新尝试 PENDING 时也不能对它重试（非 FAILED）
        mockMvc.perform(post("/api/tasks/" + at2 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"ra6\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FAILED"));
    }

    @Test
    void 重试_设备版本已变化409_取消后不能重试且取消全部未终结尝试() throws Exception {
        long releaseId = setupFailedFirstAttempt("m1", 100);
        long t1 = jdbc.queryForObject("SELECT id FROM rollout_task WHERE attempt_no = 1", Long.class);
        long t2 = idOf(mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-rt\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn(), "$.taskId");
        // 重试成功升级设备，再模拟其后续失败不可能（版本已变）；这里直接改设备版本验证守卫
        jdbc.update("UPDATE device SET current_version = '9.9.9' WHERE device_id = 'd1'");
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"r-f2\",\"result\":\"FAILED\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + t2 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-rt2\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_VERSION_CHANGED"));
        // 恢复版本后取消发布单
        jdbc.update("UPDATE device SET current_version = '1.0.0' WHERE device_id = 'd1'");
        // t2 已 FAILED，再建一个 PENDING 重试用于验证取消清理全部未终结尝试
        long t3 = idOf(mockMvc.perform(post("/api/tasks/" + t2 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-rt3\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn(), "$.taskId");
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE status = 'PENDING'", Long.class)).isZero();
        // 取消后不能重试
        mockMvc.perform(post("/api/tasks/" + t3 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-rt4\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));
    }

    @Test
    void 重试_旧任务迟到不同结果409_不改变设备与新任务() throws Exception {
        setupFailedFirstAttempt("m1", 100);
        long t1 = jdbc.queryForObject("SELECT id FROM rollout_task WHERE attempt_no = 1", Long.class);
        long t2 = idOf(mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-rt\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn(), "$.taskId");
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-ok\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        // 旧任务（FAILED）迟到 SUCCESS：409，设备版本与新任务不受影响
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-late\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_RESULT_CONFLICT"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?",
                String.class, t1)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?",
                String.class, t2)).isEqualTo("SUCCESS");
    }

    @Test
    void 重试幂等_同键同参含期望版本重放_改参409_失败不占键_不复活已取消重试() throws Exception {
        long releaseId = setupFailedFirstAttempt("m1", 100);
        long t1 = jdbc.queryForObject("SELECT id FROM rollout_task WHERE attempt_no = 1", Long.class);

        MvcResult first = mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-same\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn();
        long t2 = idOf(first, "$.taskId");
        // 同键同参重放：返回首次结果，不再创建
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-same\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(t2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);

        // 同键改期望版本（改参）：409
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-same\",\"expectedVersion\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 取消该重试任务（随发布单一并取消）后，重放旧创建请求不复活：任务仍 CANCELLED
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-cancel\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-same\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?",
                String.class, t2)).isEqualTo("CANCELLED");

        // 失败不占键：用一个新 requestId 先触发 409（对已取消发布单），键不被占用（仍 409 而非重放）
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-free\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));
    }

    @Test
    void 重试_不存在404_参数非法400() throws Exception {
        setupFailedFirstAttempt("m1", 100);
        // 任务不存在：404
        mockMvc.perform(post("/api/tasks/9999/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-x\",\"expectedVersion\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
        // 缺少 requestId：400
        mockMvc.perform(post("/api/tasks/1/retry").contentType("application/json")
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest());
        // expectedVersion 非法（0）：400
        long t1 = jdbc.queryForObject("SELECT id FROM rollout_task WHERE attempt_no = 1", Long.class);
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-y\",\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        // 请求体不可解析：400
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 尝试历史_按尝试序号列前后继及结果_单次任务兼容序号1() throws Exception {
        // 仅有一次原始任务（PENDING）的设备：兼容为序号 1
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"rd","deviceId":"solo","model":"ms","currentVersion":"1.0.0","bucketNo":1}"""))
                .andExpect(status().isOk());
        long soloRelease = idOf(mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rr","model":"ms","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}"""))
                .andExpect(status().isOk()).andReturn(), "$.releaseId");
        long soloTask = idOf(mockMvc.perform(post("/api/devices/solo/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp\"}")).andReturn(), "$.task.taskId");

        mockMvc.perform(get("/api/releases/" + soloRelease + "/devices/solo/attempts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.attempts[0].taskId").value(soloTask))
                .andExpect(jsonPath("$.attempts[0].attemptNo").value(1))
                .andExpect(jsonPath("$.attempts[0].status").value("PENDING"))
                .andExpect(jsonPath("$.attempts[0].firstResult").doesNotExist());

        // 多尝试链：FAILED -> PENDING(retry) ，验证前驱指针与结果
        long releaseId = setupFailedFirstAttempt("m1", 100);
        long t1 = jdbc.queryForObject(
                "SELECT id FROM rollout_task WHERE release_id = ? AND attempt_no = 1",
                Long.class, releaseId);
        long t2 = idOf(mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-rt\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn(), "$.taskId");
        mockMvc.perform(get("/api/releases/" + releaseId + "/devices/d1/attempts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(releaseId))
                .andExpect(jsonPath("$.deviceId").value("d1"))
                .andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[0].taskId").value(t1))
                .andExpect(jsonPath("$.attempts[0].attemptNo").value(1))
                .andExpect(jsonPath("$.attempts[0].status").value("FAILED"))
                .andExpect(jsonPath("$.attempts[0].firstResult").value("FAILED"))
                .andExpect(jsonPath("$.attempts[1].taskId").value(t2))
                .andExpect(jsonPath("$.attempts[1].attemptNo").value(2))
                .andExpect(jsonPath("$.attempts[1].prevTaskId").value((int) t1))
                .andExpect(jsonPath("$.attempts[1].status").value("PENDING"));

        // 设备不存在：404；发布单不存在：404
        mockMvc.perform(get("/api/releases/" + releaseId + "/devices/ghost/attempts"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/releases/9999/devices/d1/attempts"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 暂停后已有重试仍可回执() throws Exception {
        // floor=1 threshold=50：首次失败即自动暂停
        long releaseId = setupFailedFirstAttempt("m1", 1, 50);
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));
        // PAUSED 不能新建重试
        long t1 = jdbc.queryForObject(
                "SELECT id FROM rollout_task WHERE device_id = 'd1' AND attempt_no = 1", Long.class);
        mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-blocked\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict());
        // 人工恢复（版本 1->2）后在 ACTIVE 期间创建重试
        mockMvc.perform(post("/api/releases/" + releaseId + "/resume").contentType("application/json")
                        .content("{\"requestId\":\"r-resume\",\"expectedVersion\":1,\"reason\":\"修复\"}"))
                .andExpect(status().isOk());
        long t2 = idOf(mockMvc.perform(post("/api/tasks/" + t1 + "/retry").contentType("application/json")
                        .content("{\"requestId\":\"r-rt\",\"expectedVersion\":2}"))
                .andExpect(status().isOk()).andReturn(), "$.taskId");
        // 另一设备在恢复后的新轮次失败，再次自动暂停
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"r-d2","deviceId":"d2","model":"m1","currentVersion":"1.0.0","bucketNo":2}"""))
                .andExpect(status().isOk());
        long other = idOf(mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2\"}")).andReturn(), "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + other + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-f2\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"));
        // 暂停前已创建的重试仍可回执，SUCCESS 正常升级设备
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-ok\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
    }
}
