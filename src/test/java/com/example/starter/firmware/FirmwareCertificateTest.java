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
 * 固件签名证书轮换与任务验证版本冻结测试：证书时态、唯一当前链、设备信任、
 * 批量启动全量校验、任务快照、撤销回执复核与人工确认、certKey 幂等、拒绝原因查询。
 * 使用 H2 内存库（MODE=MySQL），固件版本号使用 9.x 段避免与其他测试类互相影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareCertificateTest {

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
        jdbc.update("DELETE FROM cert_chain");
        jdbc.update("DELETE FROM cert_rejection_record");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static String hoursAgo(int hours) {
        return Instant.now().minusSeconds(hours * 3600L).toString();
    }

    private static String hoursLater(int hours) {
        return Instant.now().plusSeconds(hours * 3600L).toString();
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

    private long createRelease(String requestId, String model, String from, String to) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"%s","toVersion":"%s","ratio":100}
                """.formatted(requestId, model, from, to)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private String registerChain(String requestId, String firmwareVersion, String chainVersion,
                                 String validFrom, String validTo, String deviceSetJson) throws Exception {
        String validToPart = validTo == null ? "" : "\"validToUtc\":\"%s\",".formatted(validTo);
        String deviceSetPart = deviceSetJson == null ? "" : "\"deviceSet\":%s,".formatted(deviceSetJson);
        MvcResult result = mockMvc.perform(
                        post("/api/firmwares/" + firmwareVersion + "/cert-chains")
                                .contentType("application/json")
                                .content("""
                                        {"requestId":"%s","chainVersion":"%s","validFromUtc":"%s",%s%s
                                        "operator":"op-1"}
                                        """.formatted(requestId, chainVersion, validFrom,
                                        validToPart, deviceSetPart)))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private long pullTaskId(String requestId, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices/" + deviceId + "/pull")
                        .contentType("application/json").content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andReturn();
        return idOf(result, "$.task.taskId");
    }

    @Test
    void 证书链登记_查询_重叠409_时间非法400_版本重复409() throws Exception {
        registerChain("c1", "9.0.0", "v1", hoursAgo(1), hoursLater(1), null);
        mockMvc.perform(get("/api/firmwares/9.0.0/cert-chains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chains.length()").value(1))
                .andExpect(jsonPath("$.chains[0].chainVersion").value("v1"))
                .andExpect(jsonPath("$.chains[0].current").value(true))
                .andExpect(jsonPath("$.chains[0].revoked").value(false));

        // 区间重叠：409
        mockMvc.perform(post("/api/firmwares/9.0.0/cert-chains").contentType("application/json").content("""
                        {"requestId":"c2","chainVersion":"v2","validFromUtc":"%s","validToUtc":"%s",
                        "operator":"op-1"}
                        """.formatted(Instant.now().toString(), hoursLater(2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERT_INTERVAL_OVERLAP"));

        // 半开区间边界相接：不重叠，允许
        mockMvc.perform(post("/api/firmwares/9.0.0/cert-chains").contentType("application/json").content("""
                        {"requestId":"c3","chainVersion":"v2","validFromUtc":"%s","validToUtc":"%s",
                        "operator":"op-1"}
                        """.formatted(hoursLater(1), hoursLater(2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainVersion").value("v2"))
                .andExpect(jsonPath("$.current").value(false));

        // 链版本重复：409
        mockMvc.perform(post("/api/firmwares/9.0.0/cert-chains").contentType("application/json").content("""
                        {"requestId":"c4","chainVersion":"v1","validFromUtc":"%s","validToUtc":"%s",
                        "operator":"op-1"}
                        """.formatted(hoursLater(2), hoursLater(3))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERT_CHAIN_EXISTS"));

        // 时间格式非法：400
        mockMvc.perform(post("/api/firmwares/9.0.0/cert-chains").contentType("application/json").content("""
                        {"requestId":"c5","chainVersion":"v9","validFromUtc":"not-a-time","operator":"op-1"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_UTC_TIME"));

        // 失效时刻不晚于生效时刻：400
        mockMvc.perform(post("/api/firmwares/9.0.0/cert-chains").contentType("application/json").content("""
                        {"requestId":"c6","chainVersion":"v9","validFromUtc":"%s","validToUtc":"%s",
                        "operator":"op-1"}
                        """.formatted(hoursLater(5), hoursAgo(5))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CERT_INTERVAL_INVALID"));

        mockMvc.perform(get("/api/firmwares/9.0.0/cert-chains"))
                .andExpect(jsonPath("$.chains.length()").value(2));
    }

    @Test
    void 轮换_截断旧链_新链生效前拉取仍用旧链_失败不占键() throws Exception {
        registerDevice("d1", "dev-1", "m1", "1.0.0", 1);
        createRelease("r1", "m1", "1.0.0", "9.1.0");
        registerChain("c1", "9.1.0", "v1", hoursAgo(1), null, null);

        // 旧链不存在：404
        mockMvc.perform(post("/api/firmwares/9.1.0/cert-chains/rotate").contentType("application/json").content("""
                        {"requestId":"rot-0","oldChainVersion":"ghost","newChainVersion":"v9",
                        "validFromUtc":"%s","operator":"op-1"}
                        """.formatted(hoursLater(1))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CERT_CHAIN_NOT_FOUND"));

        // 新链生效时刻不晚于旧链生效时刻：409，失败不占键
        mockMvc.perform(post("/api/firmwares/9.1.0/cert-chains/rotate").contentType("application/json").content("""
                        {"requestId":"rot-1","oldChainVersion":"v1","newChainVersion":"v2",
                        "validFromUtc":"%s","operator":"op-1"}
                        """.formatted(hoursAgo(2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERT_INTERVAL_OVERLAP"));

        // 同 requestId 修正参数后成功：新链一小时后生效，旧链被截断
        String newFrom = hoursLater(1);
        mockMvc.perform(post("/api/firmwares/9.1.0/cert-chains/rotate").contentType("application/json").content("""
                        {"requestId":"rot-1","oldChainVersion":"v1","newChainVersion":"v2",
                        "validFromUtc":"%s","operator":"op-2"}
                        """.formatted(newFrom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainVersion").value("v2"))
                .andExpect(jsonPath("$.current").value(false));

        mockMvc.perform(get("/api/firmwares/9.1.0/cert-chains"))
                .andExpect(jsonPath("$.chains.length()").value(2))
                .andExpect(jsonPath("$.chains[0].chainVersion").value("v1"))
                .andExpect(jsonPath("$.chains[0].validToUtc").value(newFrom))
                .andExpect(jsonPath("$.chains[0].current").value(true))
                .andExpect(jsonPath("$.chains[1].chainVersion").value("v2"))
                .andExpect(jsonPath("$.chains[1].current").value(false));

        // 新链生效前拉取：仍使用当前有效的旧链 v1
        mockMvc.perform(post("/api/devices/dev-1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.certChainVersion").value("v1"));

        // 同 requestId 同参重放：返回原结果，链数量不变
        mockMvc.perform(post("/api/firmwares/9.1.0/cert-chains/rotate").contentType("application/json").content("""
                        {"requestId":"rot-1","oldChainVersion":"v1","newChainVersion":"v2",
                        "validFromUtc":"%s","operator":"op-2"}
                        """.formatted(newFrom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainVersion").value("v2"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM cert_chain WHERE firmware_version = '9.1.0'", Long.class)).isEqualTo(2);

        // 同 requestId 异参：409
        mockMvc.perform(post("/api/firmwares/9.1.0/cert-chains/rotate").contentType("application/json").content("""
                        {"requestId":"rot-1","oldChainVersion":"v1","newChainVersion":"v3",
                        "validFromUtc":"%s","operator":"op-2"}
                        """.formatted(newFrom)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 证书时态_未生效与已到期与已撤销均不可新建任务且原因可区分() throws Exception {
        registerDevice("d1", "dev-1", "m1", "1.0.0", 1);
        registerDevice("d2", "dev-2", "m2", "1.0.0", 1);
        registerDevice("d3", "dev-3", "m3", "1.0.0", 1);

        // 仅未来链：422 CERT_CHAIN_NOT_YET_VALID
        createRelease("r1", "m1", "1.0.0", "9.2.0");
        registerChain("c1", "9.2.0", "v1", hoursLater(1), null, null);
        mockMvc.perform(post("/api/devices/dev-1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERT_CHAIN_NOT_YET_VALID"));

        // 仅已到期链：422 CERT_CHAIN_EXPIRED
        createRelease("r2", "m2", "1.0.0", "9.2.1");
        registerChain("c2", "9.2.1", "v1", hoursAgo(2), hoursAgo(1), null);
        mockMvc.perform(post("/api/devices/dev-2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERT_CHAIN_EXPIRED"));

        // 唯一链已撤销：422 CERT_CHAIN_REVOKED
        createRelease("r3", "m3", "1.0.0", "9.2.2");
        registerChain("c3", "9.2.2", "v1", hoursAgo(1), null, null);
        mockMvc.perform(post("/api/firmwares/9.2.2/cert-chains/v1/revoke")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rv1\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.current").value(false));
        mockMvc.perform(post("/api/devices/dev-3/pull").contentType("application/json")
                        .content("{\"requestId\":\"p3\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERT_CHAIN_REVOKED"));

        // 失败不留下半成品状态：无任何任务
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        // 拒绝原因可查询且可区分
        mockMvc.perform(get("/api/cert-rejections"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejections.length()").value(3))
                .andExpect(jsonPath("$.rejections[0].reasonCode").value("CERT_CHAIN_NOT_YET_VALID"))
                .andExpect(jsonPath("$.rejections[0].operation").value("PULL"))
                .andExpect(jsonPath("$.rejections[1].reasonCode").value("CERT_CHAIN_EXPIRED"))
                .andExpect(jsonPath("$.rejections[2].reasonCode").value("CERT_CHAIN_REVOKED"));
    }

    @Test
    void 设备信任集合_不信任设备拉取422_信任设备成功() throws Exception {
        registerDevice("d1", "dev-1", "m1", "1.0.0", 1);
        registerDevice("d2", "dev-2", "m1", "1.0.0", 2);
        createRelease("r1", "m1", "1.0.0", "9.3.0");
        registerChain("c1", "9.3.0", "v1", hoursAgo(1), null, "[\"dev-1\"]");

        mockMvc.perform(post("/api/devices/dev-2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_TRUSTED"));

        mockMvc.perform(post("/api/devices/dev-1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.certChainVersion").value("v1"))
                .andExpect(jsonPath("$.task.reviewStatus").value("NONE"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);
        mockMvc.perform(get("/api/cert-rejections"))
                .andExpect(jsonPath("$.rejections.length()").value(1))
                .andExpect(jsonPath("$.rejections[0].reasonCode").value("DEVICE_NOT_TRUSTED"))
                .andExpect(jsonPath("$.rejections[0].deviceId").value("dev-2"));
    }

    @Test
    void 批量启动_任一不信任422且全部任务不创建_全部通过才创建() throws Exception {
        registerDevice("d1", "dev-1", "m1", "1.0.0", 1);
        registerDevice("d2", "dev-2", "m1", "1.0.0", 2);
        registerDevice("d3", "dev-3", "m1", "1.0.0", 3);
        registerDevice("d4", "dev-4", "m1", "0.9.0", 4);
        long releaseId = createRelease("r1", "m1", "1.0.0", "9.4.0");
        registerChain("c1", "9.4.0", "v1", hoursAgo(1), null, "[\"dev-1\",\"dev-2\",\"dev-4\"]");

        // 设备不存在：422 DEVICE_NOT_FOUND
        mockMvc.perform(post("/api/releases/" + releaseId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"s0\",\"deviceIds\":[\"ghost\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));

        // 版本不兼容：422 VERSION_MISMATCH
        mockMvc.perform(post("/api/releases/" + releaseId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"s1\",\"deviceIds\":[\"dev-4\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VERSION_MISMATCH"));

        // dev-3 不在信任集合：422 DEVICE_NOT_TRUSTED，全部任务不创建
        mockMvc.perform(post("/api/releases/" + releaseId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"s2\",\"deviceIds\":[\"dev-1\",\"dev-2\",\"dev-3\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_TRUSTED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();

        // 全部通过：创建任务并固化当前链
        mockMvc.perform(post("/api/releases/" + releaseId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"s3\",\"deviceIds\":[\"dev-1\",\"dev-2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(2))
                .andExpect(jsonPath("$.tasks[0].certChainVersion").value("v1"))
                .andExpect(jsonPath("$.tasks[0].status").value("PENDING"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);

        // 同 requestId 同参重放：不重复创建
        mockMvc.perform(post("/api/releases/" + releaseId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"s3\",\"deviceIds\":[\"dev-1\",\"dev-2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);

        // 发布单不存在：404
        mockMvc.perform(post("/api/releases/9999/start").contentType("application/json")
                        .content("{\"requestId\":\"s9\",\"deviceIds\":[\"dev-1\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 撤销后回执标记复核_不自动更新版本_人工确认后完成更新() throws Exception {
        registerDevice("d1", "dev-1", "m1", "1.0.0", 1);
        registerDevice("d2", "dev-2", "m1", "1.0.0", 2);
        long releaseId = createRelease("r1", "m1", "1.0.0", "9.5.0");
        registerChain("c1", "9.5.0", "v1", hoursAgo(1), null, null);
        long taskId = pullTaskId("p1", "dev-1");

        // 撤销固化链
        mockMvc.perform(post("/api/firmwares/9.5.0/cert-chains/v1/revoke")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rv1\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));

        // 撤销后回执仍受理，但标记 CERTIFICATE_REVOKED_REVIEW 且不更新设备版本
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.reviewStatus").value("CERTIFICATE_REVOKED_REVIEW"));
        mockMvc.perform(get("/api/devices/dev-1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 任务详情：固化链版本与复核状态
        mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certChainVersion").value("v1"))
                .andExpect(jsonPath("$.reviewStatus").value("CERTIFICATE_REVOKED_REVIEW"));

        // 回执复核查询
        mockMvc.perform(get("/api/releases/" + releaseId + "/receipt-reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].taskId").value(taskId));

        // 人工确认前重复确认无意义：确认后版本更新且仅生效一次
        mockMvc.perform(post("/api/tasks/" + taskId + "/confirm").contentType("application/json")
                        .content("{\"requestId\":\"cf1\",\"operator\":\"op-9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("CONFIRMED"));
        mockMvc.perform(get("/api/devices/dev-1"))
                .andExpect(jsonPath("$.currentVersion").value("9.5.0"));

        // 再次确认（新 requestId）：409 无待确认复核
        mockMvc.perform(post("/api/tasks/" + taskId + "/confirm").contentType("application/json")
                        .content("{\"requestId\":\"cf2\",\"operator\":\"op-9\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_PENDING_REVIEW"));

        // 新拉取必须使用当前有效链：唯一链已撤销，新设备拉取 422
        mockMvc.perform(post("/api/devices/dev-2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERT_CHAIN_REVOKED"));
    }

    @Test
    void 撤销前成功回执正常更新版本_撤销后FAILED回执仅标记复核() throws Exception {
        registerDevice("d1", "dev-1", "m1", "1.0.0", 1);
        registerDevice("d2", "dev-2", "m1", "1.0.0", 2);
        long releaseId = createRelease("r1", "m1", "1.0.0", "9.6.0");
        registerChain("c1", "9.6.0", "v1", hoursAgo(1), null, null);
        long successTask = pullTaskId("p1", "dev-1");
        long failedTask = pullTaskId("p2", "dev-2");

        // 撤销前 SUCCESS：正常更新版本，无需复核
        mockMvc.perform(post("/api/tasks/" + successTask + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("NONE"));
        mockMvc.perform(get("/api/devices/dev-1"))
                .andExpect(jsonPath("$.currentVersion").value("9.6.0"));

        mockMvc.perform(post("/api/firmwares/9.6.0/cert-chains/v1/revoke")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rv1\",\"operator\":\"op-1\"}"))
                .andExpect(status().isOk());

        // 撤销后 FAILED 回执：标记复核，设备版本不变，确认无意义（409）
        mockMvc.perform(post("/api/tasks/" + failedTask + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc2\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.reviewStatus").value("CERTIFICATE_REVOKED_REVIEW"));
        mockMvc.perform(get("/api/devices/dev-2"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        mockMvc.perform(post("/api/tasks/" + failedTask + "/confirm").contentType("application/json")
                        .content("{\"requestId\":\"cf1\",\"operator\":\"op-9\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_PENDING_REVIEW"));

        // 撤销前已完成任务不受撤销影响：重复回执仍成功且无需复核
        mockMvc.perform(post("/api/tasks/" + successTask + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc3\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("NONE"));

        mockMvc.perform(get("/api/releases/" + releaseId + "/receipt-reviews"))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].taskId").value(failedTask));
    }

    @Test
    void 未登记证书链的固件保持无证书投放行为() throws Exception {
        registerDevice("d1", "dev-1", "m1", "1.0.0", 1);
        createRelease("r1", "m1", "1.0.0", "9.7.0");
        mockMvc.perform(post("/api/devices/dev-1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.certChainVersion").doesNotExist())
                .andExpect(jsonPath("$.task.reviewStatus").value("NONE"));
    }
}
