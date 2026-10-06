package com.example.starter.firmware.service;

import com.example.starter.firmware.api.BootReceiptRequest;
import com.example.starter.firmware.api.BootTaskListResponse;
import com.example.starter.firmware.api.BootTaskView;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.domain.BootReceiptPhase;
import com.example.starter.firmware.domain.BootTask;
import com.example.starter.firmware.domain.BootTaskStatus;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RollbackReason;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.BootRollbackRecordRepository;
import com.example.starter.firmware.repo.BootTaskRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 双分区试启动：设备拉取任务后提交试启动回执进入 TRIAL（记录目标分区、目标版本与服务端 UTC
 * 试启动时刻，不更新 currentVersion）；试启动后 60 秒内收到健康确认才 COMMITTED 并切换版本与分区，
 * 达到 60 秒即回滚，FAILED 也回滚。回滚保留旧分区旧版本、不计升级成功，重试产生新任务ID。
 * 所有时间取自可注入服务端 Clock；到期任务由查询与回执惰性结算，不依赖后台线程。
 * 与取消、恢复、普通回执并发时统一先锁发布单行、再锁任务行，健康确认/失败/到期竞争只产生一个终态，
 * 失败率统计只计一次。
 */
@Service
public class BootTaskService {

    /**
     * 试启动健康确认窗口：达到 60 秒即视为到期回滚。
     */
    public static final Duration TRIAL_WINDOW = Duration.ofSeconds(60);

    private final BootTaskRepository bootTaskRepository;
    private final BootRollbackRecordRepository rollbackRecordRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final FailureMonitor failureMonitor;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public BootTaskService(BootTaskRepository bootTaskRepository,
                           BootRollbackRecordRepository rollbackRecordRepository,
                           ReleaseRepository releaseRepository, DeviceRepository deviceRepository,
                           FailureMonitor failureMonitor, IdempotencyService idempotency,
                           ObjectMapper objectMapper, TransactionTemplate transactionTemplate,
                           Clock clock) {
        this.bootTaskRepository = bootTaskRepository;
        this.rollbackRecordRepository = rollbackRecordRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.failureMonitor = failureMonitor;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 双分区发布拉取（调用方已持有发布单行锁）：存在未终结任务直接返回（先结算到期）；
     * 最近一次尝试已回滚且设备仍命中投放时创建新尝试（新任务ID）；已提交/已取消返回现状。
     */
    public PullResponse pullDualPartition(Device device, ReleaseOrder order) {
        var latest = bootTaskRepository.findLatestByReleaseAndDevice(order.id(), device.deviceId());
        if (latest.isPresent()) {
            BootTask task = settleIfExpired(latest.get(), order);
            if (task.status() != BootTaskStatus.ROLLED_BACK || !eligible(device, order)) {
                return new PullResponse(null, BootTaskView.of(task, order));
            }
        } else if (!eligible(device, order)) {
            return new PullResponse(null, null);
        }
        int attemptNo = bootTaskRepository.nextAttemptNo(order.id(), device.deviceId());
        long taskId = bootTaskRepository.insert(order.id(), device.deviceId(), attemptNo);
        BootTask task = bootTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("试启动任务创建后读取失败"));
        return new PullResponse(null, BootTaskView.of(task, order));
    }

    private boolean eligible(Device device, ReleaseOrder order) {
        return order.status() == ReleaseStatus.ACTIVE
                && device.currentVersion().equals(order.fromVersion())
                && device.bucketNo() < order.ratio();
    }

    /**
     * 双分区回执：先结算到期，再按当前状态受理。PENDING 受理试启动与失败；TRIAL 受理健康确认、
     * 失败与同字段试启动重报；终态（COMMITTED/ROLLED_BACK/CANCELLED）一律 409。
     * 每阶段响应由 requestId 幂等冻结：提交旧试启动键重放首次响应，不返回后来终态。
     */
    public BootTaskView receipt(long taskId, BootReceiptRequest request) {
        // 到期结算独立提交：过期健康回执返回 409 时，回滚结果不随回执事务回滚
        settleExpiredTask(taskId);
        String fingerprint = fingerprint("bootTask.receipt", taskId, request.result(), request.phase(),
                request.targetPartition(), request.targetVersion());
        return idempotency.execute(request.requestId(), "bootTask.receipt", fingerprint, () -> {
            BootTask snapshot = bootTaskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            BootTask task = bootTaskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case PENDING -> receiptOnPending(task, order, request);
                case TRIAL -> receiptOnTrial(task, order, request);
                case COMMITTED, ROLLED_BACK -> throw ApiException.conflict("BOOT_TASK_TERMINATED",
                        "任务已终结为 " + task.status() + "，回执不再受理");
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            };
        }, BootTaskView.class);
    }

    /**
     * 回执前在独立事务内结算该任务的到期回滚（若已到期）。
     */
    private void settleExpiredTask(long taskId) {
        transactionTemplate.executeWithoutResult(status -> bootTaskRepository.findById(taskId)
                .ifPresent(snapshot -> {
                    ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                            .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
                    BootTask locked = bootTaskRepository.findByIdForUpdate(taskId).orElseThrow();
                    settleIfExpired(locked, order);
                }));
    }

    private BootTaskView receiptOnPending(BootTask task, ReleaseOrder order, BootReceiptRequest request) {
        if (request.result() == ReceiptResult.FAILED) {
            return BootTaskView.of(rollback(task, order, RollbackReason.FAILED), order);
        }
        if (request.phase() == null) {
            throw ApiException.badRequest("PHASE_REQUIRED",
                    "双分区任务 SUCCESS 回执必须携带 phase（TRIAL_BOOT 或 HEALTH_CONFIRM）");
        }
        if (request.phase() == BootReceiptPhase.HEALTH_CONFIRM) {
            throw ApiException.conflict("TRIAL_REQUIRED", "任务尚未试启动，不能直接健康确认");
        }
        // 发布暂停禁止新试启动
        if (order.status() != ReleaseStatus.ACTIVE) {
            throw ApiException.conflict("RELEASE_NOT_ACTIVE",
                    "发布单状态为 " + order.status() + "，禁止新试启动");
        }
        Device device = deviceRepository.findById(task.deviceId())
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + task.deviceId()));
        // 只能切换非当前分区
        if (!device.inactivePartition().equals(request.targetPartition())) {
            throw ApiException.conflict("INVALID_TARGET_PARTITION",
                    "目标分区必须是非当前分区 " + device.inactivePartition() + ": " + request.targetPartition());
        }
        if (!order.toVersion().equals(request.targetVersion())) {
            throw ApiException.conflict("INVALID_TARGET_VERSION",
                    "目标版本必须为 " + order.toVersion() + ": " + request.targetVersion());
        }
        String startedAtUtc = Instant.now(clock).toString();
        bootTaskRepository.startTrial(task.id(), request.targetPartition(), request.targetVersion(),
                startedAtUtc);
        return BootTaskView.of(bootTaskRepository.findById(task.id()).orElseThrow(), order);
    }

    private BootTaskView receiptOnTrial(BootTask task, ReleaseOrder order, BootReceiptRequest request) {
        if (request.result() == ReceiptResult.FAILED) {
            return BootTaskView.of(rollback(task, order, RollbackReason.FAILED), order);
        }
        if (request.phase() == null) {
            throw ApiException.badRequest("PHASE_REQUIRED",
                    "双分区任务 SUCCESS 回执必须携带 phase（TRIAL_BOOT 或 HEALTH_CONFIRM）");
        }
        if (request.phase() == BootReceiptPhase.TRIAL_BOOT) {
            // 同任务试启动字段不可改：同字段重报返回冻结的 TRIAL 阶段视图，异字段 409
            if (task.targetPartition().equals(request.targetPartition())
                    && task.targetVersion().equals(request.targetVersion())) {
                return BootTaskView.of(task, order);
            }
            throw ApiException.conflict("TRIAL_FIELDS_IMMUTABLE", "试启动字段已记录，不可修改");
        }
        // 健康确认：回执前已独立结算到期；此处持锁复查窗口，越过边界即回滚并拒绝
        if (isExpired(task)) {
            rollback(task, order, RollbackReason.EXPIRED);
            throw ApiException.conflict("HEALTH_CONFIRM_EXPIRED", "健康确认已超过60秒窗口，任务已回滚");
        }
        bootTaskRepository.commit(task.id());
        deviceRepository.updateBootState(task.deviceId(), task.targetVersion(), task.targetPartition());
        releaseRepository.incrementRoundStats(order.id(), ReceiptResult.SUCCESS);
        return BootTaskView.of(bootTaskRepository.findById(task.id()).orElseThrow(), order);
    }

    /**
     * 到期结算：TRIAL 任务达到 60 秒未确认即回滚。调用方必须已持有发布单行锁与任务行锁，
     * 与健康确认、失败回执竞争时只产生一个终态。
     */
    private BootTask settleIfExpired(BootTask task, ReleaseOrder order) {
        if (task.status() != BootTaskStatus.TRIAL || !isExpired(task)) {
            return task;
        }
        return rollback(task, order, RollbackReason.EXPIRED);
    }

    /**
     * 是否已达到 60 秒健康确认窗口（达到即视为到期）。
     */
    private boolean isExpired(BootTask task) {
        Instant deadline = Instant.parse(task.trialStartedAtUtc()).plus(TRIAL_WINDOW);
        return !Instant.now(clock).isBefore(deadline);
    }

    /**
     * 回滚：任务转 ROLLED_BACK，保留设备旧分区旧版本；回滚记录每任务至多一条（唯一约束兜底），
     * 失败率统计只计一次，达到阈值同事务暂停发布单。
     */
    private BootTask rollback(BootTask task, ReleaseOrder order, RollbackReason reason) {
        bootTaskRepository.markRolledBack(task.id());
        rollbackRecordRepository.insert(task.id(), order.id(), order.monitorRound(), reason,
                Instant.now(clock).toString());
        releaseRepository.incrementRoundStats(order.id(), ReceiptResult.FAILED);
        ReleaseOrder updated = releaseRepository.findById(order.id()).orElseThrow();
        failureMonitor.pauseIfThresholdReached(updated, task.id());
        return bootTaskRepository.findById(task.id()).orElseThrow();
    }

    /**
     * 任务列表查询：先惰性结算本发布单全部到期 TRIAL 任务，再返回视图。不依赖后台线程。
     */
    @Transactional
    public BootTaskListResponse listByRelease(long releaseId, BootTaskStatus statusFilter) {
        ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
        for (BootTask trial : bootTaskRepository.findTrialByRelease(releaseId)) {
            BootTask locked = bootTaskRepository.findByIdForUpdate(trial.id()).orElseThrow();
            settleIfExpired(locked, order);
        }
        List<BootTaskView> tasks = bootTaskRepository.findByRelease(releaseId, statusFilter).stream()
                .map(task -> BootTaskView.of(task, order))
                .toList();
        return new BootTaskListResponse(tasks);
    }

    /**
     * 结构化指纹：JSON 数组序列化操作与全部参数，避免分隔符碰撞。
     */
    private String fingerprint(Object... parts) {
        try {
            return objectMapper.writeValueAsString(Arrays.asList(parts));
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }
}
