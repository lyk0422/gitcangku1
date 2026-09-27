package com.example.starter.firmware;

import com.example.starter.firmware.api.BatchStartRequest;
import com.example.starter.firmware.api.CertChainListResponse;
import com.example.starter.firmware.api.CertChainView;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterCertChainRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.RegisterFirmwareRequest;
import com.example.starter.firmware.api.RevokeCertChainRequest;
import com.example.starter.firmware.api.RolloverCertChainRequest;
import com.example.starter.firmware.api.TaskReviewRequest;
import com.example.starter.firmware.api.UpsertCompatRequest;
import com.example.starter.firmware.domain.CertReviewStatus;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceTrustRepository;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.CertificateService;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固件签名证书轮换：主流程、证书时态、唯一当前链、设备信任、兼容矩阵、任务快照、
 * 撤销回执人工复核与幂等边界（真实 H2 内存库，MODE=MySQL；业务时钟为可控时钟）。
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.datasource.url=jdbc:h2:mem:firmware_cert_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
class FirmwareCertificateTest {

    static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    static final String CHAIN1_FROM = "2026-09-01T00:00:00Z";
    static final String ROLLOVER_AT = "2026-11-01T00:00:00Z";

    /**
     * 可控业务时钟，替换应用中的 systemUTC Clock。
     */
    static final MutableClock MUTABLE_CLOCK = new MutableClock(T0);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock businessClock() {
            return MUTABLE_CLOCK;
        }
    }

    static class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private CertificateService certificateService;
    @Autowired
    private DeviceTrustRepository deviceTrustRepository;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanUp() {
        MUTABLE_CLOCK.setInstant(T0);
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_trust");
        jdbc.update("DELETE FROM firmware_cert_chain");
        jdbc.update("DELETE FROM firmware_version");
        jdbc.update("DELETE FROM firmware_compat");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    // ---------- 场景构造助手 ----------

    private void device(String rid, String id, String model, String version, int bucket) {
        deviceService.register(new RegisterDeviceRequest(rid, id, model, version, bucket));
    }

    private void managedTarget(String model, String target, List<String> chain1Devices, String fromUtc) {
        certificateService.registerFirmware(
                new RegisterFirmwareRequest("fw-" + model + target, model, target, "alice"));
        certificateService.upsertCompat(new UpsertCompatRequest(
                "compat-" + model + target, model, "1.0.0", target, true, "alice"));
        certificateService.registerChain(new RegisterCertChainRequest(
                "chain1-" + model + target, model, target, 1, "fp-v1", fromUtc, null,
                chain1Devices, "alice"));
    }

    private long release(String rid, String model, String to, int ratio) {
        return releaseService.create(new CreateReleaseRequest(rid, model, "1.0.0", to, ratio)).releaseId();
    }

    private CertChainView rollover(String rid, String model, String target, List<String> devices) {
        return certificateService.rollover(new RolloverCertChainRequest(
                rid, model, target, 1, 2, "fp-v2", ROLLOVER_AT, devices, "alice"));
    }

    private long pull(String rid, String deviceId) {
        PullResponse response = taskService.pull(deviceId, rid);
        assertThat(response.task()).isNotNull();
        return response.task().taskId();
    }

    private ApiException expectApi(Runnable action, HttpStatus status, String code) {
        Throwable thrown = catchThrowable(action::run);
        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException ex = (ApiException) thrown;
        assertThat(ex.status()).isEqualTo(status);
        assertThat(ex.code()).isEqualTo(code);
        return ex;
    }

    // ---------- 主流程与信任集合 ----------

    @Test
    void 链登记后信任设备拉取固化链版本与指纹_不信任422() {
        device("rd1", "d1", "m1", "1.0.0", 1);
        device("rd2", "d2", "m1", "1.0.0", 2);
        managedTarget("m1", "2.0.0", List.of("d1"), CHAIN1_FROM);
        long releaseId = release("rr", "m1", "2.0.0", 100);

        long taskId = pull("rp1", "d1");
        var task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.certChainVersion()).isEqualTo(1);
        assertThat(task.certFingerprint()).isEqualTo("fp-v1");
        assertThat(task.reviewStatus()).isNull();

        // d2 不在链1的信任设备集合：422，且不创建任务
        expectApi(() -> taskService.pull("d2", "rp2"), HttpStatus.UNPROCESSABLE_ENTITY,
                "DEVICE_CERT_NOT_TRUSTED");
        assertThat(taskRepository.countByReleaseId(releaseId)).isEqualTo(1);

        CertChainListResponse chains = certificateService.listChains("m1", "2.0.0");
        assertThat(chains.currentChainVersion()).isEqualTo(1);
        assertThat(chains.chains()).hasSize(1);
        assertThat(chains.chains().get(0).current()).isTrue();
    }

    @Test
    void http层_不信任设备拉取返回422且错误码可区分() throws Exception {
        device("rd1", "d1", "m1", "1.0.0", 1);
        managedTarget("m1", "2.0.0", List.of("d1"), CHAIN1_FROM);
        release("rr", "m1", "2.0.0", 100);

        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.certChainVersion").value(1))
                .andExpect(jsonPath("$.task.certFingerprint").value("fp-v1"));

        // 未登记设备拉取：404
        mockMvc.perform(post("/api/devices/ghost/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp-x\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------- 证书时态 ----------

    @Test
    void 时态_新链生效前不可用_生效时刻切换当前链_旧任务快照不变() {
        device("rd1", "d1", "m1", "1.0.0", 1);
        device("rd2", "d2", "m1", "1.0.0", 2);
        device("rd3", "d3", "m1", "1.0.0", 3);
        managedTarget("m1", "2.0.0", List.of("d1"), CHAIN1_FROM);
        long releaseId = release("rr", "m1", "2.0.0", 100);

        // 生效前 d1 用 v1 拉取，固化 v1
        long t1 = pull("rp1", "d1");
        // d2 只在轮换后的新链信任集合中：此刻当前链仍是 v1，422
        expectApi(() -> taskService.pull("d2", "rp2"), HttpStatus.UNPROCESSABLE_ENTITY,
                "DEVICE_CERT_NOT_TRUSTED");

        CertChainView v2 = rollover("rr-roll", "m1", "2.0.0", List.of("d1", "d2"));
        assertThat(v2.chainVersion()).isEqualTo(2);
        assertThat(v2.validFromUtc()).isEqualTo(ROLLOVER_AT);
        assertThat(v2.validToUtc()).isNull();

        // 生效时刻前一刻：唯一当前链仍是 v1
        MUTABLE_CLOCK.setInstant(Instant.parse("2026-10-31T23:59:59Z"));
        assertThat(certificateService.listChains("m1", "2.0.0").currentChainVersion()).isEqualTo(1);
        expectApi(() -> taskService.pull("d2", "rp3"), HttpStatus.UNPROCESSABLE_ENTITY,
                "DEVICE_CERT_NOT_TRUSTED");

        // 恰好生效时刻：当前链切换为 v2
        MUTABLE_CLOCK.setInstant(Instant.parse(ROLLOVER_AT));
        CertChainListResponse chains = certificateService.listChains("m1", "2.0.0");
        assertThat(chains.currentChainVersion()).isEqualTo(2);
        assertThat(chains.chains()).hasSize(2);
        assertThat(chains.chains().get(0).validToUtc()).isEqualTo(ROLLOVER_AT);
        assertThat(chains.chains().get(0).current()).isFalse();
        assertThat(chains.chains().get(1).current()).isTrue();

        // d2 信任新链：新建任务固化 v2
        long t2 = pull("rp4", "d2");
        assertThat(taskRepository.findById(t2).orElseThrow().certChainVersion()).isEqualTo(2);
        // d1 重复拉取返回旧任务，快照仍为 v1，不因轮换改变
        PullResponse again = taskService.pull("d1", "rp5");
        assertThat(again.task().taskId()).isEqualTo(t1);
        assertThat(again.task().certChainVersion()).isEqualTo(1);
        // d3 任何链都不信任：422
        expectApi(() -> taskService.pull("d3", "rp6"), HttpStatus.UNPROCESSABLE_ENTITY,
                "DEVICE_CERT_NOT_TRUSTED");
        assertThat(taskRepository.countByReleaseId(releaseId)).isEqualTo(2);
    }

    // ---------- 轮换规则 ----------

    @Test
    void 轮换_必须指定旧新版本_区间不得重叠_只能闭合最新开放链() {
        device("rd1", "d1", "m1", "1.0.0", 1);
        managedTarget("m1", "2.0.0", List.of("d1"), CHAIN1_FROM);

        // 固件未登记
        expectApi(() -> certificateService.rollover(new RolloverCertChainRequest(
                "x1", "m9", "2.0.0", 1, 2, "fp", ROLLOVER_AT, List.of("d1"), "alice")),
                HttpStatus.NOT_FOUND, "FIRMWARE_NOT_FOUND");
        // 旧链不存在
        expectApi(() -> certificateService.rollover(new RolloverCertChainRequest(
                "x2", "m1", "2.0.0", 9, 2, "fp", ROLLOVER_AT, List.of("d1"), "alice")),
                HttpStatus.NOT_FOUND, "OLD_CHAIN_NOT_FOUND");
        // 新链版本不接续
        expectApi(() -> certificateService.rollover(new RolloverCertChainRequest(
                "x3", "m1", "2.0.0", 1, 3, "fp", ROLLOVER_AT, List.of("d1"), "alice")),
                HttpStatus.CONFLICT, "NEW_CHAIN_VERSION_SEQUENCE");
        // 生效时刻早于旧链生效时刻：区间倒挂
        expectApi(() -> certificateService.rollover(new RolloverCertChainRequest(
                "x4", "m1", "2.0.0", 1, 2, "fp", "2025-01-01T00:00:00Z", List.of("d1"), "alice")),
                HttpStatus.BAD_REQUEST, "INVALID_VALID_WINDOW");

        // 合法轮换
        rollover("x5", "m1", "2.0.0", List.of("d1"));
        // v1 已闭合，不能再被轮换
        expectApi(() -> rollover("x6", "m1", "2.0.0", List.of("d1")),
                HttpStatus.CONFLICT, "OLD_CHAIN_NOT_OPEN");
        // 只能接续 v2->v3
        CertChainView v3 = certificateService.rollover(new RolloverCertChainRequest(
                "x7", "m1", "2.0.0", 2, 3, "fp-v3", "2026-12-01T00:00:00Z", List.of("d1"), "alice"));
        assertThat(v3.chainVersion()).isEqualTo(3);

        // 撤销 v1（已闭合但仍 ACTIVE）成功，再次撤销 409
        certificateService.revoke(new RevokeCertChainRequest("rv1", "m1", "2.0.0", 1, "key-leak", "bob"));
        expectApi(() -> certificateService.revoke(new RevokeCertChainRequest(
                "rv2", "m1", "2.0.0", 1, "key-leak", "bob")),
                HttpStatus.CONFLICT, "CHAIN_NOT_ACTIVE");
    }

    @Test
    void 首链登记_固件必须存在_链版本必须为1_区间与时刻格式校验() {
        device("rd1", "d1", "m1", "1.0.0", 1);

        // 固件未登记：404
        expectApi(() -> certificateService.registerChain(new RegisterCertChainRequest(
                "c0", "m1", "2.0.0", 1, "fp", CHAIN1_FROM, null, List.of("d1"), "alice")),
                HttpStatus.NOT_FOUND, "FIRMWARE_NOT_FOUND");

        certificateService.registerFirmware(new RegisterFirmwareRequest("f1", "m1", "2.0.0", "alice"));
        // 首链版本不是 1
        expectApi(() -> certificateService.registerChain(new RegisterCertChainRequest(
                "c1", "m1", "2.0.0", 2, "fp", CHAIN1_FROM, null, List.of("d1"), "alice")),
                HttpStatus.CONFLICT, "CHAIN_VERSION_SEQUENCE");
        // 非法时刻
        expectApi(() -> certificateService.registerChain(new RegisterCertChainRequest(
                "c2", "m1", "2.0.0", 1, "fp", "not-a-time", null, List.of("d1"), "alice")),
                HttpStatus.BAD_REQUEST, "INVALID_INSTANT");
        // 闭区间不晚于开区间
        expectApi(() -> certificateService.registerChain(new RegisterCertChainRequest(
                "c3", "m1", "2.0.0", 1, "fp", CHAIN1_FROM, CHAIN1_FROM, List.of("d1"), "alice")),
                HttpStatus.BAD_REQUEST, "INVALID_VALID_WINDOW");
        // 设备不存在
        expectApi(() -> certificateService.registerChain(new RegisterCertChainRequest(
                "c4", "m1", "2.0.0", 1, "fp", CHAIN1_FROM, null, List.of("ghost"), "alice")),
                HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND");
        // 合法登记
        CertChainView v1 = certificateService.registerChain(new RegisterCertChainRequest(
                "c5", "m1", "2.0.0", 1, "fp", CHAIN1_FROM, null, List.of("d1"), "alice"));
        assertThat(v1.chainVersion()).isEqualTo(1);
        // 固件已有链：再登记首链冲突，必须走轮换
        expectApi(() -> certificateService.registerChain(new RegisterCertChainRequest(
                "c6", "m1", "2.0.0", 1, "fp2", CHAIN1_FROM, null, List.of("d1"), "alice")),
                HttpStatus.CONFLICT, "CHAIN_ALREADY_EXISTS");
    }

    // ---------- 启动守卫与兼容矩阵 ----------

    @Test
    void 启动发布_纳管固件须兼容矩阵允许且当前有有效链() {
        device("rd1", "d1", "m1", "1.0.0", 1);
        certificateService.registerFirmware(new RegisterFirmwareRequest("f1", "m1", "2.0.0", "alice"));

        // 缺少兼容规则
        expectApi(() -> release("r1", "m1", "2.0.0", 100),
                HttpStatus.UNPROCESSABLE_ENTITY, "COMPAT_RULE_MISSING");

        // 明确拒绝
        certificateService.upsertCompat(new UpsertCompatRequest(
                "k1", "m1", "1.0.0", "2.0.0", false, "alice"));
        expectApi(() -> release("r2", "m1", "2.0.0", 100),
                HttpStatus.UNPROCESSABLE_ENTITY, "COMPAT_NOT_ALLOWED");

        // 改为允许，但链在未来生效：当前无有效链
        certificateService.upsertCompat(new UpsertCompatRequest(
                "k2", "m1", "1.0.0", "2.0.0", true, "alice"));
        certificateService.registerChain(new RegisterCertChainRequest(
                "c1", "m1", "2.0.0", 1, "fp", "2027-01-01T00:00:00Z", null, List.of("d1"), "alice"));
        expectApi(() -> release("r3", "m1", "2.0.0", 100),
                HttpStatus.UNPROCESSABLE_ENTITY, "NO_VALID_CERT_CHAIN");

        // 时钟走到生效后：启动成功
        MUTABLE_CLOCK.setInstant(Instant.parse("2027-06-01T00:00:00Z"));
        long releaseId = release("r4", "m1", "2.0.0", 100);
        assertThat(releaseId).isPositive();

        // 未纳管证书的固件保持旧行为：无兼容矩阵也能发布
        device("rd2", "d2", "m2", "1.0.0", 1);
        assertThat(release("r5", "m2", "9.9.9", 100)).isPositive();
    }

    // ---------- 撤销、回执复核、人工确认 ----------

    @Test
    void 撤销后新拉取422_在途回执标记复核_确认才更新版本_拒绝不更新() {
        device("rd1", "d1", "m1", "1.0.0", 1);
        device("rd2", "d2", "m1", "1.0.0", 2);
        device("rd3", "d3", "m1", "1.0.0", 3);
        device("rd4", "d4", "m1", "1.0.0", 4);
        managedTarget("m1", "2.0.0", List.of("d1", "d2", "d3", "d4"), CHAIN1_FROM);
        long releaseId = release("rr", "m1", "2.0.0", 100);
        long t1 = pull("rp1", "d1");
        long t3 = pull("rp3", "d3");
        long t4 = pull("rp4", "d4");

        CertChainView revoked = certificateService.revoke(
                new RevokeCertChainRequest("rv", "m1", "2.0.0", 1, "private-key-leak", "bob"));
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.revokedAtUtc()).isEqualTo(T0.toString());
        assertThat(revoked.revokeReason()).isEqualTo("private-key-leak");
        // 撤销后无当前有效链
        assertThat(certificateService.listChains("m1", "2.0.0").currentChainVersion()).isNull();

        // 未拉取的 d2 此刻新拉取：422，不创建任务
        expectApi(() -> taskService.pull("d2", "rp2"), HttpStatus.UNPROCESSABLE_ENTITY,
                "NO_VALID_CERT_CHAIN");
        assertThat(taskRepository.countByReleaseId(releaseId)).isEqualTo(3);

        // d1 在途任务仍可回执 SUCCESS：终结但进入复核，设备版本不自动更新
        var receipt1 = taskService.receipt(t1, new ReceiptRequest("rc1", ReceiptResult.SUCCESS));
        assertThat(receipt1.status()).isEqualTo("SUCCESS");
        assertThat(receipt1.reviewStatus()).isEqualTo("CERTIFICATE_REVOKED_REVIEW");
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("1.0.0");

        // 人工确认后才完成版本更新
        var confirmed = taskService.review(t1, new TaskReviewRequest(
                "rv-ok", TaskReviewRequest.ReviewAction.CONFIRMED, "核验升级正常", "carol"));
        assertThat(confirmed.reviewStatus()).isEqualTo("CONFIRMED");
        assertThat(confirmed.reviewedBy()).isEqualTo("carol");
        assertThat(confirmed.reviewedAtUtc()).isEqualTo(T0.toString());
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("2.0.0");
        // 已裁决任务重复裁决：409
        expectApi(() -> taskService.review(t1, new TaskReviewRequest(
                "rv-again", TaskReviewRequest.ReviewAction.REJECTED, "改主意", "carol")),
                HttpStatus.CONFLICT, "REVIEW_NOT_PENDING");

        // FAILED 回执即使人工确认也不更新版本
        taskService.receipt(t3, new ReceiptRequest("rc3", ReceiptResult.FAILED));
        assertThat(taskRepository.findById(t3).orElseThrow().reviewStatus())
                .isEqualTo(CertReviewStatus.CERTIFICATE_REVOKED_REVIEW);
        taskService.review(t3, new TaskReviewRequest(
                "rv-fail", TaskReviewRequest.ReviewAction.CONFIRMED, "失败可接受", "carol"));
        assertThat(deviceService.get("d3").currentVersion()).isEqualTo("1.0.0");
        assertThat(taskRepository.findById(t3).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);

        // SUCCESS 回执被人工拒绝：永不更新版本
        taskService.receipt(t4, new ReceiptRequest("rc4", ReceiptResult.SUCCESS));
        var rejected = taskService.review(t4, new TaskReviewRequest(
                "rv-reject", TaskReviewRequest.ReviewAction.REJECTED, "签名来源存疑", "carol"));
        assertThat(rejected.reviewStatus()).isEqualTo("REJECTED");
        assertThat(rejected.reviewReason()).isEqualTo("签名来源存疑");
        assertThat(deviceService.get("d4").currentVersion()).isEqualTo("1.0.0");

        // 复核数量与暂停统计：复核任务回执仍计入监控轮次（1成功在确认前已计数，不触发暂停）
        assertThat(taskRepository.countReviewByRelease(releaseId, CertReviewStatus.CONFIRMED)).isEqualTo(2);
        assertThat(taskRepository.countReviewByRelease(releaseId, CertReviewStatus.REJECTED)).isEqualTo(1);
        assertThat(releaseService.monitor(releaseId).roundSuccess()).isEqualTo(2);
    }

    // ---------- 批量启动全体校验 ----------

    @Test
    void 批量启动_任一不信任422且全部不创建_矩阵变更后拒绝_重放不重复建任务() {
        device("rd1", "d1", "m1", "1.0.0", 1);
        device("rd2", "d2", "m1", "1.0.0", 2);
        device("rd3", "d3", "m1", "1.0.0", 3);
        // 仅 d1 信任
        managedTarget("m1", "2.0.0", List.of("d1"), CHAIN1_FROM);
        long releaseId = release("rr", "m1", "2.0.0", 100);

        expectApi(() -> taskService.batchStart(releaseId, new BatchStartRequest(
                "b1", List.of("d1", "d2"), "alice")),
                HttpStatus.UNPROCESSABLE_ENTITY, "DEVICE_CERT_NOT_TRUSTED");
        assertThat(taskRepository.countByReleaseId(releaseId)).isZero();

        // d1 单独批量启动成功
        var ok = taskService.batchStart(releaseId, new BatchStartRequest("b2", List.of("d1"), "alice"));
        assertThat(ok.tasks()).hasSize(1);
        // 同 requestId 同参重放：返回同一任务，不重复创建
        var replay = taskService.batchStart(releaseId, new BatchStartRequest("b2", List.of("d1"), "alice"));
        assertThat(replay.tasks()).hasSize(1);
        assertThat(replay.tasks().get(0).taskId()).isEqualTo(ok.tasks().get(0).taskId());
        assertThat(taskRepository.countByReleaseId(releaseId)).isEqualTo(1);

        // 兼容矩阵事后改为拒绝：新设备批量启动 422，已有任务保留
        certificateService.upsertCompat(new UpsertCompatRequest(
                "k-off", "m1", "1.0.0", "2.0.0", false, "alice"));
        expectApi(() -> taskService.batchStart(releaseId, new BatchStartRequest(
                "b3", List.of("d2"), "alice")),
                HttpStatus.UNPROCESSABLE_ENTITY, "COMPAT_NOT_ALLOWED");
        assertThat(taskRepository.countByReleaseId(releaseId)).isEqualTo(1);

        // 恢复矩阵并给 d2 补信任（通过再登记不行——直接校验无独立信任接口；用新链轮换授权）
        certificateService.upsertCompat(new UpsertCompatRequest(
                "k-on", "m1", "1.0.0", "2.0.0", true, "alice"));
        expectApi(() -> taskService.batchStart(releaseId, new BatchStartRequest(
                "b4", List.of("d2"), "alice")),
                HttpStatus.UNPROCESSABLE_ENTITY, "DEVICE_CERT_NOT_TRUSTED");

        // 不满足投放条件（型号/版本/分桶）：可区分错误码
        device("rd9", "d9", "m1", "9.9.9", 1);
        expectApi(() -> taskService.batchStart(releaseId, new BatchStartRequest(
                "b5", List.of("d9"), "alice")),
                HttpStatus.UNPROCESSABLE_ENTITY, "DEVICE_NOT_ELIGIBLE");
    }

    // ---------- 快照跨轮换与撤销 ----------

    @Test
    void 快照_旧任务随v1撤销进复核_新任务用v2正常更新() {
        device("rd1", "d1", "m1", "1.0.0", 1);
        device("rd2", "d2", "m1", "1.0.0", 2);
        managedTarget("m1", "2.0.0", List.of("d1", "d2"), CHAIN1_FROM);
        long releaseId = release("rr", "m1", "2.0.0", 100);

        long t1 = pull("rp1", "d1");
        rollover("rr-roll", "m1", "2.0.0", List.of("d1", "d2"));
        MUTABLE_CLOCK.setInstant(Instant.parse(ROLLOVER_AT));
        long t2 = pull("rp2", "d2");
        assertThat(taskRepository.findById(t1).orElseThrow().certChainVersion()).isEqualTo(1);
        assertThat(taskRepository.findById(t2).orElseThrow().certChainVersion()).isEqualTo(2);

        // 撤销旧链 v1：d1 的在途任务回执须复核
        certificateService.revoke(new RevokeCertChainRequest("rv1", "m1", "2.0.0", 1, "leak", "bob"));
        taskService.receipt(t1, new ReceiptRequest("rc1", ReceiptResult.SUCCESS));
        assertThat(taskRepository.findById(t1).orElseThrow().reviewStatus())
                .isEqualTo(CertReviewStatus.CERTIFICATE_REVOKED_REVIEW);
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("1.0.0");
        taskService.review(t1, new TaskReviewRequest(
                "rv-ok", TaskReviewRequest.ReviewAction.CONFIRMED, "确认", "carol"));
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("2.0.0");

        // v2 仍有效：d2 回执正常成功，立即更新版本，无复核
        var receipt2 = taskService.receipt(t2, new ReceiptRequest("rc2", ReceiptResult.SUCCESS));
        assertThat(receipt2.reviewStatus()).isNull();
        assertThat(deviceService.get("d2").currentVersion()).isEqualTo("2.0.0");

        // 任务查询可见冻结链版本、指纹与复核结论
        var view1 = taskRepository.findById(t1).orElseThrow();
        var view2 = taskRepository.findById(t2).orElseThrow();
        assertThat(view1.certChainVersion()).isEqualTo(1);
        assertThat(view1.certFingerprint()).isEqualTo("fp-v1");
        assertThat(view1.reviewStatus()).isEqualTo(CertReviewStatus.CONFIRMED);
        assertThat(view2.certChainVersion()).isEqualTo(2);
        assertThat(view2.certFingerprint()).isEqualTo("fp-v2");
        assertThat(view2.reviewStatus()).isNull();
        assertThat(deviceTrustRepository.countByChain("m1", "2.0.0", 2)).isEqualTo(2);
        assertThat(releaseId).isPositive();
    }

    // ---------- 幂等 ----------

    @Test
    void 幂等_certKey同键重放_异参409_失败不占键() {
        device("rd1", "d1", "m1", "1.0.0", 1);
        device("rd2", "d2", "m1", "1.0.0", 2);

        // 固件登记重放
        var reqFw = new RegisterFirmwareRequest("fw1", "m1", "2.0.0", "alice");
        assertThat(certificateService.registerFirmware(reqFw)).isEqualTo("m1:2.0.0");
        assertThat(certificateService.registerFirmware(reqFw)).isEqualTo("m1:2.0.0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM firmware_version", Long.class)).isEqualTo(1);
        expectApi(() -> certificateService.registerFirmware(
                new RegisterFirmwareRequest("fw1", "m1", "2.0.0", "bob")),
                HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT");

        // 首链登记重放：只产生一条链、一组信任关系
        certificateService.upsertCompat(new UpsertCompatRequest(
                "k1", "m1", "1.0.0", "2.0.0", true, "alice"));
        var reqChain = new RegisterCertChainRequest("c1", "m1", "2.0.0", 1, "fp-v1",
                CHAIN1_FROM, null, List.of("d1"), "alice");
        CertChainView first = certificateService.registerChain(reqChain);
        CertChainView replay = certificateService.registerChain(reqChain);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM firmware_cert_chain", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_trust", Long.class)).isEqualTo(1);
        // certKey 含设备集合与操作者：换操作者即异参
        expectApi(() -> certificateService.registerChain(new RegisterCertChainRequest(
                "c1", "m1", "2.0.0", 1, "fp-v1", CHAIN1_FROM, null, List.of("d1", "d2"), "alice")),
                HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT");

        // 失败不占键：设备不存在先 404，同 requestId 改正后成功
        expectApi(() -> certificateService.rollover(new RolloverCertChainRequest(
                "ro1", "m1", "2.0.0", 1, 2, "fp-v2", ROLLOVER_AT, List.of("ghost"), "alice")),
                HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND");
        CertChainView v2 = certificateService.rollover(new RolloverCertChainRequest(
                "ro1", "m1", "2.0.0", 1, 2, "fp-v2", ROLLOVER_AT, List.of("d1", "d2"), "alice"));
        assertThat(v2.chainVersion()).isEqualTo(2);

        // 撤销重放：链只撤销一次
        var reqRevoke = new RevokeCertChainRequest("rv1", "m1", "2.0.0", 1, "leak", "bob");
        assertThat(certificateService.revoke(reqRevoke).status()).isEqualTo("REVOKED");
        assertThat(certificateService.revoke(reqRevoke).status()).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM firmware_cert_chain WHERE status = 'REVOKED'", Long.class))
                .isEqualTo(1);
    }
}
