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
 * 固件签名证书轮换与任务版本冻结 API 测试（H2 内存库，MODE=MySQL）：
 * 证书时态、唯一当前链、轮换规则、设备信任、批量启动、任务快照、撤销后回执复核与人工确认、幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareCertTest {

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
        jdbc.update("DELETE FROM device_trust");
        jdbc.update("DELETE FROM cert_chain");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static String utc(long epochSecond) {
        return Instant.ofEpochSecond(epochSecond).toString();
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDevice(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, String from, String to, int ratio)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":%d}
                """.formatted(requestId, model, from, to, ratio)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    /**
     * 登记证书链；previousChainVersion 与 deviceIds 可空。
     */
    private MvcResult registerCert(String requestId, String firmwareVersion, String chainVersion,
                                   long validFrom, long validTo, String previousChainVersion,
                                   String deviceIdsJson) throws Exception {
        String previous = previousChainVersion == null ? "null" : "\"" + previousChainVersion + "\"";
        String devices = deviceIdsJson == null ? "null" : deviceIdsJson;
        return mockMvc.perform(post("/api/certificates").contentType("application/json").content("""
                {"requestId":"%s","firmwareVersion":"%s","chainVersion":"%s",
                "validFromUtc":"%s","validToUtc":"%s","previousChainVersion":%s,
                "deviceIds":%s,"operator":"op-1"}
                """.formatted(requestId, firmwareVersion, chainVersion, utc(validFrom), utc(validTo),
                previous, devices)))
                .andReturn();
    }

    private long registerCertOk(String requestId, String firmwareVersion, String chainVersion,
                                long validFrom, long validTo, String previousChainVersion,
                                String deviceIdsJson) throws Exception {
        MvcResult result = registerCert(requestId, firmwareVersion, chainVersion, validFrom, validTo,
                previousChainVersion, deviceIdsJson);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return idOf(result, "$.chainId");
    }

    @Test
    void 证书登记与查询_主流程与设备信任授权() throws Exception {
        registerDevice("r-d1", "d1", "m1", "1.0.0", 1);
        long now = now();
        long chainId = registerCertOk("r-c1", "2.0.0", "chain-v1", now - 3600, now + 3600, null, "[\"d1\"]");

        mockMvc.perform(get("/api/certificates").param("firmwareVersion", "2.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chains.length()").value(1))
                .andExpect(jsonPath("$.chains[0].chainVersion").value("chain-v1"))
                .andExpect(jsonPath("$.chains[0].operator").value("op-1"))
                .andExpect(jsonPath("$.chains[0].revokedAtUtc").doesNotExist());

        mockMvc.perform(get("/api/certificates/current").param("firmwareVersion", "2.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainId").value(chainId))
                .andExpect(jsonPath("$.chainVersion").value("chain-v1"));

        // 信任集合：登记时授予
        mockMvc.perform(get("/api/devices/d1/trust"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainIds.length()").value(1))
                .andExpect(jsonPath("$.chainIds[0]").value(chainId));

        // 无登记的固件版本：当前链 404
        mockMvc.perform(get("/api/certificates/current").param("firmwareVersion", "9.9.9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_CURRENT_CERT_CHAIN"));
    }

    @Test
    void 证书时态_未生效与已到期拉取422_区间重叠409_参数400() throws Exception {
        registerDevice("r-d1", "d1", "m1", "1.0.0", 1);
        createRelease("r-r1", "m1", "1.0.0", "2.0.0", 100);
        long now = now();

        // 仅未来链：新链生效前不可用于拉取
        registerCertOk("r-c1", "2.0.0", "chain-future", now + 7200, now + 10800, null, "[\"d1\"]");
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERT_CHAIN_NOT_YET_EFFECTIVE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();

        // 区间重叠：409
        mockMvc.perform(post("/api/certificates").contentType("application/json").content("""
                {"requestId":"r-c2","firmwareVersion":"2.0.0","chainVersion":"chain-x",
                "validFromUtc":"%s","validToUtc":"%s","previousChainVersion":"chain-future",
                "operator":"op-1"}
                """.formatted(utc(now + 8000), utc(now + 20000))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERT_INTERVAL_OVERLAP"));

        // 生效不早于失效：400
        mockMvc.perform(post("/api/certificates").contentType("application/json").content("""
                {"requestId":"r-c3","firmwareVersion":"3.0.0","chainVersion":"chain-bad",
                "validFromUtc":"%s","validToUtc":"%s","operator":"op-1"}
                """.formatted(utc(now + 100), utc(now))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CERT_INTERVAL_INVALID"));

        // 时间格式非法：400
        mockMvc.perform(post("/api/certificates").contentType("application/json").content("""
                {"requestId":"r-c4","firmwareVersion":"3.0.0","chainVersion":"chain-bad2",
                "validFromUtc":"not-a-time","validToUtc":"%s","operator":"op-1"}
                """.formatted(utc(now + 100))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CERT_TIME_INVALID"));
    }

    @Test
    void 证书时态_旧链到期后不可新建任务() throws Exception {
        registerDevice("r-d1", "d1", "m1", "1.0.0", 1);
        createRelease("r-r1", "m1", "1.0.0", "2.0.0", 100);
        long now = now();
        registerCertOk("r-c1", "2.0.0", "chain-expired", now - 7200, now - 3600, null, "[\"d1\"]");

        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERT_CHAIN_EXPIRED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
    }

    @Test
    void 轮换规则_必须指定旧链且区间不重叠() throws Exception {
        long now = now();

        // 首条链指定旧链：422
        MvcResult first = registerCert("r-c1", "2.0.0", "chain-v1", now - 100, now + 100, "ghost", null);
        assertThat(first.getResponse().getStatus()).isEqualTo(422);
        assertThat(first.getResponse().getContentAsString()).contains("PREVIOUS_CHAIN_NOT_FOUND");

        registerCertOk("r-c2", "2.0.0", "chain-v1", now - 3600, now - 100, null, null);

        // 轮换缺旧链版本：400
        MvcResult noPrevious = registerCert("r-c3", "2.0.0", "chain-v2", now - 100, now + 3600, null, null);
        assertThat(noPrevious.getResponse().getStatus()).isEqualTo(400);
        assertThat(noPrevious.getResponse().getContentAsString()).contains("PREVIOUS_CHAIN_REQUIRED");

        // 旧链版本不存在：422
        MvcResult ghost = registerCert("r-c4", "2.0.0", "chain-v2", now - 100, now + 3600, "ghost", null);
        assertThat(ghost.getResponse().getStatus()).isEqualTo(422);
        assertThat(ghost.getResponse().getContentAsString()).contains("PREVIOUS_CHAIN_NOT_FOUND");

        // 正确轮换：区间衔接不重叠
        registerCertOk("r-c5", "2.0.0", "chain-v2", now - 100, now + 3600, "chain-v1", null);
        mockMvc.perform(get("/api/certificates").param("firmwareVersion", "2.0.0"))
                .andExpect(jsonPath("$.chains.length()").value(2));

        // 同固件版本同链版本重复登记（新 requestId）：409
        MvcResult dup = registerCert("r-c6", "2.0.0", "chain-v2", now + 3600, now + 7200, "chain-v1", null);
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
        assertThat(dup.getResponse().getContentAsString()).contains("CERT_CHAIN_EXISTS");
    }

    @Test
    void 设备信任与任务固化_未信任422_任务快照可查() throws Exception {
        registerDevice("r-d1", "d1", "m1", "1.0.0", 1);
        registerDevice("r-d2", "d2", "m1", "1.0.0", 2);
        long releaseId = createRelease("r-r1", "m1", "1.0.0", "2.0.0", 100);
        long now = now();
        registerCertOk("r-c1", "2.0.0", "chain-v1", now - 3600, now + 3600, null, "[\"d1\"]");

        // 未信任设备：422，不创建任务
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_TRUSTED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();

        // 信任设备：任务固化当前链版本
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.chainVersion").value("chain-v1"))
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");

        // 任务详情查询：固化版本与复核标记
        mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainVersion").value("chain-v1"))
                .andExpect(jsonPath("$.reviewStatus").doesNotExist());
        mockMvc.perform(get("/api/tasks/9999"))
                .andExpect(status().isNotFound());
        assertThat(releaseId).isPositive();
    }

    @Test
    void 撤销后回执转复核_不自动更新版本_人工确认后完成() throws Exception {
        registerDevice("r-d1", "d1", "m1", "1.0.0", 1);
        registerDevice("r-d2", "d2", "m1", "1.0.0", 2);
        long releaseId = createRelease("r-release", "m1", "1.0.0", "2.0.0", 100);
        long now = now();
        long chainId = registerCertOk("r-c1", "2.0.0", "chain-v1", now - 3600, now + 3600, null,
                "[\"d1\",\"d2\"]");
        MvcResult pull = mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long taskId = idOf(pull, "$.task.taskId");

        // 撤销证书链
        mockMvc.perform(post("/api/certificates/" + chainId + "/revoke").contentType("application/json")
                        .content("{\"requestId\":\"r-rev1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedAtUtc").isString());

        // 撤销后新拉取：422
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p2\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERT_CHAIN_REVOKED"));

        // 已创建任务回执仍受理，但标记复核且不更新设备版本
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"r-rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.reviewStatus").value("CERTIFICATE_REVOKED_REVIEW"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 复核查询可见
        mockMvc.perform(get("/api/releases/" + releaseId + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviews.length()").value(1))
                .andExpect(jsonPath("$.reviews[0].taskId").value(taskId))
                .andExpect(jsonPath("$.reviews[0].reviewStatus").value("CERTIFICATE_REVOKED_REVIEW"))
                .andExpect(jsonPath("$.reviews[0].chainVersion").value("chain-v1"));

        // 人工确认后完成版本更新
        mockMvc.perform(post("/api/tasks/" + taskId + "/confirmReview").contentType("application/json")
                        .content("{\"requestId\":\"r-cf1\",\"operator\":\"ops-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("REVIEW_CONFIRMED"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 重复确认（新 requestId）：幂等返回当前状态
        mockMvc.perform(post("/api/tasks/" + taskId + "/confirmReview").contentType("application/json")
                        .content("{\"requestId\":\"r-cf2\",\"operator\":\"ops-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("REVIEW_CONFIRMED"));

        // 无复核标记的任务确认：409（d2 无任务，用不存在任务 404 与正常任务对比）
        mockMvc.perform(post("/api/tasks/9999/confirmReview").contentType("application/json")
                        .content("{\"requestId\":\"r-cf9\",\"operator\":\"ops-1\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 批量启动_任一不信任整体422且不创建任务() throws Exception {
        registerDevice("r-d1", "d1", "m1", "1.0.0", 1);
        registerDevice("r-d2", "d2", "m1", "1.0.0", 2);
        registerDevice("r-d3", "d3", "m1", "1.0.0", 3);
        long releaseId = createRelease("r-release", "m1", "1.0.0", "2.0.0", 100);
        long now = now();
        registerCertOk("r-c1", "2.0.0", "chain-v1", now - 3600, now + 3600, null, "[\"d1\",\"d3\"]");

        // d2 未信任：整体 422，携带每台设备拒绝原因，任何任务都不创建
        mockMvc.perform(post("/api/releases/" + releaseId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r-s1\",\"deviceIds\":[\"d1\",\"d2\",\"d3\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_START_REJECTED"))
                .andExpect(jsonPath("$.rejections.length()").value(1))
                .andExpect(jsonPath("$.rejections[0].deviceId").value("d2"))
                .andExpect(jsonPath("$.rejections[0].code").value("DEVICE_NOT_TRUSTED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();

        // 不存在的设备：拒绝原因 DEVICE_NOT_FOUND
        mockMvc.perform(post("/api/releases/" + releaseId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r-s2\",\"deviceIds\":[\"d1\",\"ghost\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejections[0].code").value("DEVICE_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();

        // 全部信任：一次性创建全部任务，固化当前链
        mockMvc.perform(post("/api/releases/" + releaseId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r-s3\",\"deviceIds\":[\"d1\",\"d3\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(2))
                .andExpect(jsonPath("$.tasks[0].chainVersion").value("chain-v1"))
                .andExpect(jsonPath("$.tasks[1].chainVersion").value("chain-v1"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);

        // 取消后批量启动：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"r-cancel\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/releases/" + releaseId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"r-s4\",\"deviceIds\":[\"d1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));
    }

    @Test
    void 证书幂等_同键重放_异参409_失败不占键() throws Exception {
        long now = now();
        MvcResult first = registerCert("cert-k1", "2.0.0", "chain-v1", now - 3600, now + 3600, null, null);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        // 同键同参重放：响应一致，只登记一条
        MvcResult replay = registerCert("cert-k1", "2.0.0", "chain-v1", now - 3600, now + 3600, null, null);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cert_chain", Long.class)).isEqualTo(1);

        // 同键异参：409
        MvcResult conflict = registerCert("cert-k1", "2.0.0", "chain-v9", now - 3600, now + 3600, null, null);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(conflict.getResponse().getContentAsString()).contains("REQUEST_ID_CONFLICT");

        // 失败不占键：先重叠 409，再同键合法参数成功
        MvcResult overlap = registerCert("cert-k2", "2.0.0", "chain-v2", now - 100, now + 100,
                "chain-v1", null);
        assertThat(overlap.getResponse().getStatus()).isEqualTo(409);
        MvcResult ok = registerCert("cert-k2", "2.0.0", "chain-v2", now + 3600, now + 7200,
                "chain-v1", null);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cert_chain", Long.class)).isEqualTo(2);
    }

    @Test
    void 轮换后新拉取使用当前有效链() throws Exception {
        registerDevice("r-d1", "d1", "m1", "1.0.0", 1);
        createRelease("r-r1", "m1", "1.0.0", "2.0.0", 100);
        long now = now();
        // 旧链已到期，新链当前有效；设备仅信任新链
        registerCertOk("r-c1", "2.0.0", "chain-old", now - 7200, now - 3600, null, null);
        registerCertOk("r-c2", "2.0.0", "chain-new", now - 3600, now + 3600, "chain-old", "[\"d1\"]");

        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"r-p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.chainVersion").value("chain-new"));

        // 当前链查询返回新链
        mockMvc.perform(get("/api/certificates/current").param("firmwareVersion", "2.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainVersion").value("chain-new"));
    }
}
