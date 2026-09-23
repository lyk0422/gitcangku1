package com.example.starter.firmware;

import com.example.starter.firmware.api.AssignDeviceRequest;
import com.example.starter.firmware.api.CommandReceiptRequest;
import com.example.starter.firmware.api.CommandView;
import com.example.starter.firmware.api.CreateCohortRequest;
import com.example.starter.firmware.api.CreateRegionRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.MigrationActivateResponse;
import com.example.starter.firmware.api.MigrationDetailResponse;
import com.example.starter.firmware.api.MigrationItemInput;
import com.example.starter.firmware.api.MigrationPreviewResponse;
import com.example.starter.firmware.api.ResumeCohortRequest;
import com.example.starter.firmware.domain.AssignmentCommand;
import com.example.starter.firmware.domain.Cohort;
import com.example.starter.firmware.domain.CohortAssignment;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.AssignmentRepository;
import com.example.starter.firmware.repo.CohortRepository;
import com.example.starter.firmware.repo.MigrationRepository;
import com.example.starter.firmware.service.CohortService;
import com.example.starter.firmware.service.MigrationService;
import com.example.starter.firmware.service.ReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 跨投放队列迁移 H2 数据库测试（MODE=MySQL）：批量迁移、配额/灰度/区域回滚、
 * 提交前后旧代次回执结算边界、LATE 存档、新代次回执一次性结算与队列自动暂停/恢复、幂等。
 */
@SpringBootTest
class FirmwareCohortMigrationTest {

    @Autowired
    private MigrationService migrationService;
    @Autowired
    private CohortService cohortService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private CohortRepository cohortRepository;
    @Autowired
    private AssignmentRepository assignmentRepository;
    @Autowired
    private MigrationRepository migrationRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private long releaseId;
    private long cohortA;
    private long cohortB;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM receipt_history");
        jdbc.update("DELETE FROM cohort_migration_item");
        jdbc.update("DELETE FROM cohort_migration_order");
        jdbc.update("DELETE FROM assignment_command");
        jdbc.update("DELETE FROM cohort_assignment");
        jdbc.update("DELETE FROM cohort");
        jdbc.update("DELETE FROM cohort_region");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private void setupTwoCohorts(int capA, int canaryA, int capB, int canaryB, int regionQuota) {
        releaseId = releaseService.create(
                new CreateReleaseRequest("req-rel", "m1", "1.0.0", "2.0.0", 100)).releaseId();
        cohortService.createRegion(new CreateRegionRequest("req-region", releaseId, "R1", regionQuota));
        cohortA = cohortService.createCohort(new CreateCohortRequest("req-ca", releaseId, "A", "fw-9",
                "R1", capA, canaryA, 2, 50)).cohortId();
        cohortB = cohortService.createCohort(new CreateCohortRequest("req-cb", releaseId, "B", "fw-9",
                "R1", capB, canaryB, 2, 50)).cohortId();
    }

    private long assign(String deviceId, long cohortId, String requestId) {
        return cohortService.assignDevice(
                new AssignDeviceRequest(requestId, releaseId, deviceId, cohortId)).commandId();
    }

    private MigrationItemInput item(String deviceId, long from, int version, long to) {
        return new MigrationItemInput(deviceId, from, version, to);
    }

    @Test
    void 批量迁移_有未决指令原子废弃并生成新代次_无未决指令也递增代次() {
        setupTwoCohorts(10, 100, 10, 100, 100);
        long cmdD1 = assign("d1", cohortA, "req-a1");
        // d2 的未决指令先结算成功，确认安装前不得迁移；这里改用未结算场景：d2 保持 PENDING
        long cmdD2 = assign("d2", cohortA, "req-a2");

        MigrationActivateResponse resp = migrationService.activate(new MigrationItemInput.Activate(
                "req-mig", "mk-1", releaseId,
                List.of(item("d1", cohortA, 1, cohortB), item("d2", cohortA, 1, cohortB))));

        assertThat(resp.deviceCount()).isEqualTo(2);
        assertThat(resp.items()).hasSize(2);
        // 旧未决指令被原子置 SUPERSEDED
        assertThat(assignmentRepository.findCommandById(cmdD1).orElseThrow().status().name())
                .isEqualTo("SUPERSEDED");
        assertThat(assignmentRepository.findCommandById(cmdD2).orElseThrow().status().name())
                .isEqualTo("SUPERSEDED");
        // 设备归属切换，分配版本加一，代次加一
        CohortAssignment d1 = assignmentRepository.findAssignment(releaseId, "d1").orElseThrow();
        CohortAssignment d2 = assignmentRepository.findAssignment(releaseId, "d2").orElseThrow();
        assertThat(d1.cohortId()).isEqualTo(cohortB);
        assertThat(d1.assignmentVersion()).isEqualTo(2);
        assertThat(d1.currentGeneration()).isEqualTo(2);
        assertThat(d2.cohortId()).isEqualTo(cohortB);
        assertThat(d2.currentGeneration()).isEqualTo(2);
        // 目标队列各生成一条新一代 PENDING 指令
        resp.items().forEach(ir -> {
            AssignmentCommand cmd = assignmentRepository.findCommandById(ir.newCommandId()).orElseThrow();
            assertThat(cmd.cohortId()).isEqualTo(cohortB);
            assertThat(cmd.generation()).isEqualTo(2);
            assertThat(cmd.status().name()).isEqualTo("PENDING");
            assertThat(ir.oldGeneration()).isEqualTo(1);
            assertThat(ir.newGeneration()).isEqualTo(2);
            assertThat(ir.supersededCommandId()).isNotNull();
        });
        // 队列规模完整后态：A=0，B=2
        assertThat(cohortService.getCohort(cohortA).deviceCount()).isZero();
        assertThat(cohortService.getCohort(cohortB).deviceCount()).isEqualTo(2);
    }

    @Test
    void 无未决指令迁移_代次仍递增且旧指令字段为空() {
        setupTwoCohorts(10, 100, 10, 100, 100);
        assign("d1", cohortA, "req-a1");
        // d1 第一代指令结算 FAILED，无未决指令
        cohortService.receipt(assignmentRepository.findPendingCommandForUpdate(releaseId, "d1")
                .orElseThrow().id(), new CommandReceiptRequest("req-r1", ReceiptResult.FAILED));

        MigrationActivateResponse resp = migrationService.activate(new MigrationItemInput.Activate(
                "req-mig", "mk-1", releaseId, List.of(item("d1", cohortA, 1, cohortB))));

        var ir = resp.items().get(0);
        assertThat(ir.supersededCommandId()).isNull();
        assertThat(ir.oldGeneration()).isEqualTo(1);
        assertThat(ir.newGeneration()).isEqualTo(2);
        CohortAssignment d1 = assignmentRepository.findAssignment(releaseId, "d1").orElseThrow();
        assertThat(d1.currentGeneration()).isEqualTo(2);
        AssignmentCommand newCmd = assignmentRepository.findCommandById(ir.newCommandId()).orElseThrow();
        assertThat(newCmd.generation()).isEqualTo(2);
        assertThat(newCmd.status().name()).isEqualTo("PENDING");
    }

    @Test
    void 设备上限越界_整单422且不迁移任何设备不废弃指令() {
        setupTwoCohorts(10, 100, 1, 100, 100);
        long cmdD1 = assign("d1", cohortA, "req-a1");
        assign("d2", cohortA, "req-a2");

        assertThatThrownBy(() -> migrationService.activate(new MigrationItemInput.Activate(
                "req-mig", "mk-bad", releaseId,
                List.of(item("d1", cohortA, 1, cohortB), item("d2", cohortA, 1, cohortB)))))
                .isInstanceOfSatisfying(ApiException.class,
                        ae -> {
                            assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(ae.code()).isEqualTo("DEVICE_CAP_EXCEEDED");
                        });

        // 无部分迁移：两台设备仍在 A，版本仍为 1，旧指令仍 PENDING
        assertThat(assignmentRepository.findAssignment(releaseId, "d1").orElseThrow().cohortId())
                .isEqualTo(cohortA);
        assertThat(assignmentRepository.findAssignment(releaseId, "d2").orElseThrow().cohortId())
                .isEqualTo(cohortA);
        assertThat(assignmentRepository.findCommandById(cmdD1).orElseThrow().status().name())
                .isEqualTo("PENDING");
        assertThat(migrationRepository.findByKey("mk-bad")).isEmpty();
    }

    @Test
    void 灰度百分比越界_整单422回滚() {
        // B 设备上限10、灰度10% → ceil(10*10/100)=1，迁入2台越界
        setupTwoCohorts(10, 100, 10, 10, 100);
        assign("d1", cohortA, "req-a1");
        assign("d2", cohortA, "req-a2");

        assertThatThrownBy(() -> migrationService.activate(new MigrationItemInput.Activate(
                "req-mig", "mk-canary", releaseId,
                List.of(item("d1", cohortA, 1, cohortB), item("d2", cohortA, 1, cohortB)))))
                .isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ae.code()).isEqualTo("CANARY_LIMIT_EXCEEDED");
                });
        assertThat(cohortService.getCohort(cohortB).deviceCount()).isZero();
    }

    @Test
    void 区域配额越界_整单422回滚() {
        // 区域总配额1，A、B 同区，B 已有1台，再从 A 迁入1台 → 区域占用2 > 1
        setupTwoCohorts(10, 100, 10, 100, 1);
        assign("d1", cohortA, "req-a1");
        assign("d2", cohortB, "req-b1");

        assertThatThrownBy(() -> migrationService.activate(new MigrationItemInput.Activate(
                "req-mig", "mk-region", releaseId, List.of(item("d1", cohortA, 1, cohortB)))))
                .isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ae.code()).isEqualTo("REGION_QUOTA_EXCEEDED");
                });
        assertThat(assignmentRepository.findAssignment(releaseId, "d1").orElseThrow().cohortId())
                .isEqualTo(cohortA);
    }

    @Test
    void 固件版本不一致_整单422() {
        releaseId = releaseService.create(
                new CreateReleaseRequest("req-rel", "m1", "1.0.0", "2.0.0", 100)).releaseId();
        cohortService.createRegion(new CreateRegionRequest("req-region", releaseId, "R1", 100));
        cohortA = cohortService.createCohort(new CreateCohortRequest("req-ca", releaseId, "A", "fw-9",
                "R1", 10, 100, 2, 50)).cohortId();
        cohortB = cohortService.createCohort(new CreateCohortRequest("req-cb", releaseId, "B", "fw-10",
                "R1", 10, 100, 2, 50)).cohortId();
        assign("d1", cohortA, "req-a1");

        assertThatThrownBy(() -> migrationService.activate(new MigrationItemInput.Activate(
                "req-mig", "mk-fw", releaseId, List.of(item("d1", cohortA, 1, cohortB)))))
                .isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ae.code()).isEqualTo("FIRMWARE_MISMATCH");
                });
    }

    @Test
    void 已确认安装成功的设备不得迁移_422() {
        setupTwoCohorts(10, 100, 10, 100, 100);
        long cmd = assign("d1", cohortA, "req-a1");
        cohortService.receipt(cmd, new CommandReceiptRequest("req-ok", ReceiptResult.SUCCESS));
        assertThat(assignmentRepository.findAssignment(releaseId, "d1").orElseThrow().installConfirmed())
                .isTrue();

        assertThatThrownBy(() -> migrationService.activate(new MigrationItemInput.Activate(
                "req-mig", "mk-install", releaseId, List.of(item("d1", cohortA, 1, cohortB)))))
                .isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ae.code()).isEqualTo("INSTALL_CONFIRMED");
                });
    }

    @Test
    void 提交前旧代次回执_按旧队列结算_迁移后统计不搬移() {
        setupTwoCohorts(10, 100, 10, 100, 100);
        long oldCmd = assign("d1", cohortA, "req-a1");
        // 迁移提交前，旧代次 FAILED 回执按旧队列 A 结算（FAILED 不置安装确认，设备随后可迁移）
        cohortService.receipt(oldCmd, new CommandReceiptRequest("req-pre", ReceiptResult.FAILED));
        assertThat(cohortRepository.findCohortById(cohortA).orElseThrow().failedCount()).isEqualTo(1);

        migrationService.activate(new MigrationItemInput.Activate(
                "req-mig", "mk-pre", releaseId, List.of(item("d1", cohortA, 1, cohortB))));

        // 统计不随迁移搬移：A 保留 1 个失败，B 从 0 开始；设备归属已在 B
        assertThat(cohortRepository.findCohortById(cohortA).orElseThrow().failedCount()).isEqualTo(1);
        Cohort target = cohortRepository.findCohortById(cohortB).orElseThrow();
        assertThat(target.failedCount()).isZero();
        assertThat(target.successCount()).isZero();
        assertThat(assignmentRepository.findAssignment(releaseId, "d1").orElseThrow().cohortId())
                .isEqualTo(cohortB);
    }

    @Test
    void 提交后迟到旧代次回执_仅存LATE不改目标队列统计() {
        setupTwoCohorts(10, 100, 10, 100, 100);
        long oldCmd = assign("d1", cohortA, "req-a1");
        MigrationActivateResponse resp = migrationService.activate(new MigrationItemInput.Activate(
                "req-mig", "mk-late", releaseId, List.of(item("d1", cohortA, 1, cohortB))));
        long migrationId = resp.migrationId();

        // 旧代次指令已 SUPERSEDED，迟到 SUCCESS 回执只存档 LATE
        CommandView late = cohortService.receipt(oldCmd,
                new CommandReceiptRequest("req-late", ReceiptResult.SUCCESS));
        assertThat(late.status()).isEqualTo("SUPERSEDED");
        Cohort target = cohortRepository.findCohortById(cohortB).orElseThrow();
        assertThat(target.successCount()).isZero();
        assertThat(target.failedCount()).isZero();
        assertThat(target.paused()).isFalse();

        // 只读查询返回 LATE 证据
        MigrationDetailResponse detail = migrationService.get(migrationId);
        assertThat(detail.lateReceipts()).hasSize(1);
        var evidence = detail.lateReceipts().get(0);
        assertThat(evidence.result()).isEqualTo("LATE");
        assertThat(evidence.settled()).isFalse();
        assertThat(evidence.commandId()).isEqualTo(oldCmd);
        assertThat(evidence.cohortId()).isEqualTo(cohortA);
        assertThat(evidence.generation()).isEqualTo(1);
    }

    @Test
    void 新代次回执只结算一次_重复不计数改结果409() {
        setupTwoCohorts(10, 100, 10, 100, 100);
        long oldCmd = assign("d1", cohortA, "req-a1");
        MigrationActivateResponse resp = migrationService.activate(new MigrationItemInput.Activate(
                "req-mig", "mk-new", releaseId, List.of(item("d1", cohortA, 1, cohortB))));
        long newCmd = resp.items().get(0).newCommandId();

        cohortService.receipt(newCmd, new CommandReceiptRequest("req-s1", ReceiptResult.SUCCESS));
        // 同结果重复回执：成功返回但不重复计数
        cohortService.receipt(newCmd, new CommandReceiptRequest("req-s2", ReceiptResult.SUCCESS));
        assertThat(cohortRepository.findCohortById(cohortB).orElseThrow().successCount()).isEqualTo(1);
        // 改结果 409
        assertThatThrownBy(() -> cohortService.receipt(newCmd,
                new CommandReceiptRequest("req-s3", ReceiptResult.FAILED)))
                .isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ae.code()).isEqualTo("RECEIPT_RESULT_CONFLICT");
                });
        // 旧指令不受影响
        assertThat(assignmentRepository.findCommandById(oldCmd).orElseThrow().status().name())
                .isEqualTo("SUPERSEDED");
    }

    @Test
    void 新代次失败回执_触发队列自动暂停_人工恢复清零() {
        setupTwoCohorts(10, 100, 10, 100, 100);
        assign("d1", cohortA, "req-a1");
        assign("d2", cohortA, "req-a2");
        MigrationActivateResponse resp = migrationService.activate(new MigrationItemInput.Activate(
                "req-mig", "mk-pause", releaseId,
                List.of(item("d1", cohortA, 1, cohortB), item("d2", cohortA, 1, cohortB))));
        List<Long> newCmds = resp.items().stream().map(MigrationActivateResponse.ItemResult::newCommandId).toList();
        // B 阈值 sampleFloor=2, threshold=50：两条 FAILED 即 100% 暂停
        cohortService.receipt(newCmds.get(0), new CommandReceiptRequest("req-f1", ReceiptResult.FAILED));
        assertThat(cohortRepository.findCohortById(cohortB).orElseThrow().paused()).isFalse();
        cohortService.receipt(newCmds.get(1), new CommandReceiptRequest("req-f2", ReceiptResult.FAILED));
        Cohort paused = cohortRepository.findCohortById(cohortB).orElseThrow();
        assertThat(paused.paused()).isTrue();
        assertThat(paused.failedCount()).isEqualTo(2);

        // 人工恢复清零并解除暂停
        cohortService.resumeCohort(cohortB, new ResumeCohortRequest("req-resume", "修复"));
        Cohort resumed = cohortRepository.findCohortById(cohortB).orElseThrow();
        assertThat(resumed.paused()).isFalse();
        assertThat(resumed.failedCount()).isZero();
        assertThat(resumed.successCount()).isZero();
    }

    @Test
    void 预览_按完整后态计算且不写数据() {
        setupTwoCohorts(10, 100, 1, 100, 100);
        assign("d1", cohortA, "req-a1");
        assign("d2", cohortA, "req-a2");

        MigrationPreviewResponse preview = migrationService.preview(new MigrationItemInput.Preview(
                "mk-prev", releaseId,
                List.of(item("d1", cohortA, 1, cohortB), item("d2", cohortA, 1, cohortB))));
        assertThat(preview.feasible()).isFalse();
        assertThat(preview.violations()).anyMatch(v -> v.contains("超过设备上限"));
        // 预览不写数据：无迁移单，设备仍在 A
        assertThat(migrationRepository.findByKey("mk-prev")).isEmpty();
        assertThat(assignmentRepository.findAssignment(releaseId, "d1").orElseThrow().cohortId())
                .isEqualTo(cohortA);

        // 调整为合法后态（只迁1台）预览可行
        MigrationPreviewResponse ok = migrationService.preview(new MigrationItemInput.Preview(
                "mk-prev2", releaseId, List.of(item("d1", cohortA, 1, cohortB))));
        assertThat(ok.feasible()).isTrue();
        assertThat(ok.violations()).isEmpty();
        var stateB = ok.cohorts().stream().filter(c -> c.cohortId() == cohortB).findFirst().orElseThrow();
        assertThat(stateB.beforeCount()).isZero();
        assertThat(stateB.afterCount()).isEqualTo(1);
    }

    @Test
    void 幂等_同requestId同参重放快照_设备换序视为同参() {
        setupTwoCohorts(10, 100, 10, 100, 100);
        assign("d1", cohortA, "req-a1");
        assign("d2", cohortA, "req-a2");
        var items = List.of(item("d1", cohortA, 1, cohortB), item("d2", cohortA, 1, cohortB));

        MigrationActivateResponse first = migrationService.activate(
                new MigrationItemInput.Activate("req-same", "mk-idem", releaseId, items));
        // 设备项换序重放：同一 requestId，视为同参，返回首次快照
        MigrationActivateResponse replay = migrationService.activate(
                new MigrationItemInput.Activate("req-same", "mk-idem", releaseId,
                        List.of(items.get(1), items.get(0))));
        assertThat(replay.migrationId()).isEqualTo(first.migrationId());
        // 只有一张迁移单、两条明细
        assertThat(migrationRepository.findItemsByMigration(first.migrationId())).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cohort_migration_order", Long.class)).isEqualTo(1);
        // 代次只推进一次
        assertThat(assignmentRepository.findAssignment(releaseId, "d1").orElseThrow().currentGeneration())
                .isEqualTo(2);
    }

    @Test
    void 幂等_同requestId异参409_失败不占键_migrationKey唯一() {
        setupTwoCohorts(10, 100, 1, 100, 100);
        assign("d1", cohortA, "req-a1");
        assign("d2", cohortA, "req-a2");

        // 先失败（B 容量1，迁入2台越界），失败不占键
        assertThatThrownBy(() -> migrationService.activate(new MigrationItemInput.Activate(
                "req-reuse", "mk-x", releaseId,
                List.of(item("d1", cohortA, 1, cohortB), item("d2", cohortA, 1, cohortB)))))
                .isInstanceOf(ApiException.class);
        // requestId 可被不同参数复用（失败不占键）：改为只迁1台成功
        MigrationActivateResponse ok = migrationService.activate(new MigrationItemInput.Activate(
                "req-reuse", "mk-x", releaseId, List.of(item("d1", cohortA, 1, cohortB))));
        assertThat(ok.migrationId()).isPositive();

        // migrationKey 唯一：换 requestId 复用同一 key → 409
        assertThatThrownBy(() -> migrationService.activate(new MigrationItemInput.Activate(
                "req-other", "mk-x", releaseId, List.of(item("d2", cohortA, 1, cohortB)))))
                .isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ae.code()).isEqualTo("MIGRATION_KEY_EXISTS");
                });

        // 同 requestId 异参 → 409 REQUEST_ID_CONFLICT
        assertThatThrownBy(() -> migrationService.activate(new MigrationItemInput.Activate(
                "req-reuse", "mk-other", releaseId, List.of(item("d2", cohortA, 1, cohortB)))))
                .isInstanceOfSatisfying(ApiException.class, ae -> {
                    assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ae.code()).isEqualTo("REQUEST_ID_CONFLICT");
                });
    }

    @Test
    void assignmentVersion变化_整单409() {
        setupTwoCohorts(10, 100, 10, 100, 100);
        assign("d1", cohortA, "req-a1");
        // 先成功迁移一次使版本变为2
        migrationService.activate(new MigrationItemInput.Activate(
                "req-m1", "mk-v1", releaseId, List.of(item("d1", cohortA, 1, cohortB))));
        // 再用过期的 version=1、错误当前队列提交 → 409
        assertThatThrownBy(() -> migrationService.activate(new MigrationItemInput.Activate(
                "req-m2", "mk-v2", releaseId, List.of(item("d1", cohortA, 1, cohortB)))))
                .isInstanceOfSatisfying(ApiException.class, ae ->
                        assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
    }
}
