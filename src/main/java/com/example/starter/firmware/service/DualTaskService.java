package com.example.starter.firmware.service;

import com.example.starter.firmware.api.BootStateView;
import com.example.starter.firmware.api.DualPullRequest;
import com.example.starter.firmware.api.DualPullResponse;
import com.example.starter.firmware.api.DualReceiptRequest;
import com.example.starter.firmware.api.DualTaskListResponse;
import com.example.starter.firmware.api.DualTaskView;
import com.example.starter.firmware.api.DualTrialRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.DeviceBootState;
import com.example.starter.firmware.domain.DualRelease;
import com.example.starter.firmware.domain.DualReleaseStatus;
import com.example.starter.firmware.domain.DualTask;
import com.example.starter.firmware.domain.DualTaskStatus;
import com.example.starter.firmware.domain.RollbackReason;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceBootStateRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.DualReleaseRepository;
import com.example.starter.firmware.repo.DualTaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 双分区试启动任务：拉取、试启动回执、健康确认、失败回执与到期惰性结算。
 * 统一先锁发布单行再锁任务行，形成一致提交顺序；终态迁移由状态守卫 UPDATE 保证只生效一次，
 * 提交/回滚计数随之只计一次。所有时间来自可注入服务端 Clock，不信任设备给的截止。
 */
@Service
public class DualTaskService {

    /**
     * 试启动健康确认窗口：试启动后 60 秒内确认才 COMMITTED，达到 60 秒即回滚。
     */
    static final Duration TRIAL_TIMEOUT = Duration.ofSeconds(60);

    private final DualTaskRepository dualTaskRepository;
    private final DualReleaseRepository dualReleaseRepository;
    private final DeviceBootStateRepository bootStateRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceService deviceService;
    private final IdempotencyService idempotency;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public DualTaskService(DualTaskRepository dualTaskRepository, DualReleaseRepository dualReleaseRepository,
                           DeviceBootStateRepository bootStateRepository, DeviceRepository deviceRepository,
                           DeviceService deviceService, IdempotencyService idempotency,
                           TransactionTemplate transactionTemplate, Clock clock) {
        this.dualTaskRepository = dualTaskRepository;
        this.dualReleaseRepository = dualReleaseRepository;
        this.bootStateRepository = bootStateRepository;
        this.deviceRepository = deviceRepository;
        this.deviceService = deviceService;
        this.idempotency = idempotency;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 设备拉取：已有未终结任务（先结算到期）直接返回；回滚后重试产生新任务ID。
     * 仅当发布单 ACTIVE 且设备当前版本不等于目标版本时创建新任务；PAUSED 不创建新任务。
     */
    public DualPullResponse pull(String deviceId, DualPullRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("deviceId", deviceId);
        String fingerprint = Fingerprints.of("dual.task.pull", request.operator(), "device:" + deviceId, params);
        return idempotency.execute(request.requestId(), "dual.task.pull", fingerprint, () -> {
            Device device = deviceService.findDevice(deviceId);
            var activeRelease = dualReleaseRepository.findActiveByModel(device.model());
            if (activeRelease.isEmpty()) {
                return new DualPullResponse(null);
            }
            DualRelease release = dualReleaseRepository.findByIdForUpdate(activeRelease.get().id())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "双分区发布单不存在"));
            var existing = dualTaskRepository.findActiveByReleaseAndDevice(release.id(), deviceId);
            if (existing.isPresent()) {
                DualTask task = settleIfExpiredLocked(existing.get(), release.id());
                if (task.status() == DualTaskStatus.PENDING || task.status() == DualTaskStatus.TRIAL) {
                    return new DualPullResponse(DualTaskView.of(task));
                }
            }
            if (release.status() != DualReleaseStatus.ACTIVE
                    || device.currentVersion().equals(release.targetVersion())) {
                return new DualPullResponse(null);
            }
            long taskId;
            try {
                taskId = dualTaskRepository.insert(release.id(), deviceId);
            } catch (DuplicateKeyException e) {
                DualTask task = dualTaskRepository.findActiveByReleaseAndDevice(release.id(), deviceId)
                        .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                return new DualPullResponse(DualTaskView.of(task));
            }
            return new DualPullResponse(viewOf(taskId));
        }, DualPullResponse.class);
    }

    /**
     * 试启动回执：PENDING -> TRIAL，冻结目标分区、目标版本与服务端 UTC 试启动时刻；
     * 进入 TRIAL 不更新 currentVersion。只能切换非当前分区；同任务试启动字段不可改。
     */
    public DualTaskView trial(long taskId, DualTrialRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetPartition", request.targetPartition());
        params.put("targetVersion", request.targetVersion());
        String fingerprint = Fingerprints.of("dual.task.trial", request.operator(),
                "dual-task:" + taskId, params);
        return idempotency.execute(request.requestId(), "dual.task.trial", fingerprint, () -> {
            DualTask snapshot = dualTaskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            DualRelease release = dualReleaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "双分区发布单不存在"));
            DualTask task = dualTaskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            task = settleIfExpiredLocked(task, release.id());
            return switch (task.status()) {
                case PENDING -> {
                    if (release.status() != DualReleaseStatus.ACTIVE) {
                        throw ApiException.conflict("RELEASE_PAUSED", "发布单已暂停，禁止新试启动");
                    }
                    if (!request.targetVersion().equals(release.targetVersion())) {
                        throw ApiException.conflict("TRIAL_FIELD_MISMATCH",
                                "目标版本与发布单不一致: " + release.targetVersion());
                    }
                    String expectedPartition = DeviceBootState.otherPartition(
                            bootStateRepository.currentPartition(task.deviceId()));
                    if (!request.targetPartition().equals(expectedPartition)) {
                        throw ApiException.conflict("TRIAL_PARTITION_MISMATCH",
                                "只能切换非当前分区: " + expectedPartition);
                    }
                    dualTaskRepository.startTrial(taskId, request.targetPartition(),
                            request.targetVersion(), Instant.now(clock).toString());
                    yield viewOf(taskId);
                }
                case TRIAL -> {
                    if (!request.targetPartition().equals(task.targetPartition())
                            || !request.targetVersion().equals(task.targetVersion())) {
                        throw ApiException.conflict("TRIAL_FIELD_IMMUTABLE", "同任务试启动字段不可改");
                    }
                    yield DualTaskView.of(task);
                }
                case COMMITTED, ROLLED_BACK -> throw ApiException.conflict("TASK_ALREADY_SETTLED",
                        "任务已终结为 " + task.status() + "，试启动回执不再受理");
            };
        }, DualTaskView.class);
    }

    /**
     * 健康确认：TRIAL 且未到期 -> COMMITTED，切换设备当前版本并翻转启动分区；
     * 已到期先结算为 ROLLED_BACK 再返回 409。与失败、到期检查竞争只产生一个终态。
     */
    public DualTaskView confirm(long taskId, DualReceiptRequest request) {
        String fingerprint = Fingerprints.of("dual.task.confirm", request.operator(),
                "dual-task:" + taskId, Map.of());
        return idempotency.execute(request.requestId(), "dual.task.confirm", fingerprint, () -> {
            DualTask task = lockTaskWithRelease(taskId);
            DualRelease release = dualReleaseRepository.findById(task.releaseId()).orElseThrow();
            task = settleIfExpiredLocked(task, release.id());
            return switch (task.status()) {
                case TRIAL -> {
                    dualTaskRepository.commitIfTrial(taskId, Instant.now(clock).toString());
                    dualReleaseRepository.incrementCommitted(release.id());
                    deviceRepository.updateCurrentVersion(task.deviceId(), release.targetVersion());
                    bootStateRepository.upsert(task.deviceId(), task.targetPartition());
                    yield viewOf(taskId);
                }
                case COMMITTED -> DualTaskView.of(task);
                case ROLLED_BACK -> throw ApiException.conflict("TASK_ROLLED_BACK",
                        task.rollbackReason() == RollbackReason.EXPIRED
                                ? "健康确认已过期，任务已回滚" : "任务已回滚，健康确认不再受理");
                case PENDING -> throw ApiException.conflict("TASK_NOT_TRIAL", "任务尚未试启动，不能健康确认");
            };
        }, DualTaskView.class);
    }

    /**
     * 失败回执：PENDING/TRIAL -> ROLLED_BACK，保留旧分区与旧版本，不计为升级成功。
     * 已回滚任务重复失败回执返回当前终态；已提交任务 409。
     */
    public DualTaskView fail(long taskId, DualReceiptRequest request) {
        String fingerprint = Fingerprints.of("dual.task.fail", request.operator(),
                "dual-task:" + taskId, Map.of());
        return idempotency.execute(request.requestId(), "dual.task.fail", fingerprint, () -> {
            DualTask task = lockTaskWithRelease(taskId);
            task = settleIfExpiredLocked(task, task.releaseId());
            return switch (task.status()) {
                case PENDING, TRIAL -> {
                    dualTaskRepository.rollbackIfUnsettled(taskId, RollbackReason.FAILED,
                            Instant.now(clock).toString());
                    dualReleaseRepository.incrementRolledBack(task.releaseId());
                    yield viewOf(taskId);
                }
                case ROLLED_BACK -> DualTaskView.of(task);
                case COMMITTED -> throw ApiException.conflict("TASK_ALREADY_COMMITTED",
                        "任务已正式切换，失败回执不再受理");
            };
        }, DualTaskView.class);
    }

    /**
     * 结算发布单下所有到期 TRIAL 任务（查询与回执路径调用，不依赖后台线程）。
     */
    public void settleExpiredForRelease(long releaseId) {
        transactionTemplate.executeWithoutResult(status -> {
            dualReleaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "双分区发布单不存在: " + releaseId));
            for (DualTask task : dualTaskRepository.findTrialByRelease(releaseId)) {
                settleIfExpiredLocked(task, releaseId);
            }
        });
    }

    /**
     * 查询单个任务：先按服务端时钟惰性结算到期，再返回当前视图。
     */
    public DualTaskView getTask(long taskId) {
        return transactionTemplate.execute(status -> {
            DualTask task = lockTaskWithRelease(taskId);
            return DualTaskView.of(settleIfExpiredLocked(task, task.releaseId()));
        });
    }

    public DualTaskListResponse listByRelease(long releaseId) {
        settleExpiredForRelease(releaseId);
        List<DualTaskView> tasks = dualTaskRepository.findByRelease(releaseId).stream()
                .map(DualTaskView::of).toList();
        return new DualTaskListResponse(tasks);
    }

    public BootStateView bootView(String deviceId) {
        deviceService.findDevice(deviceId);
        return new BootStateView(deviceId, bootStateRepository.currentPartition(deviceId));
    }

    /**
     * 读取任务并按一致顺序加锁：先发布单行，再任务行。调用方处于事务内。
     */
    private DualTask lockTaskWithRelease(long taskId) {
        DualTask snapshot = dualTaskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        dualReleaseRepository.findByIdForUpdate(snapshot.releaseId())
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "双分区发布单不存在"));
        return dualTaskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
    }

    /**
     * 到期结算：TRIAL 且服务端当前时刻达到试启动后 60 秒即回滚（EXPIRED）。
     * 状态守卫 UPDATE 保证并发下至多一个事务写入终态，回滚只记录一次。调用方须持任务行锁。
     */
    private DualTask settleIfExpiredLocked(DualTask task, long releaseId) {
        if (task.status() != DualTaskStatus.TRIAL) {
            return task;
        }
        Instant now = Instant.now(clock);
        Instant deadline = Instant.parse(task.trialStartedAtUtc()).plus(TRIAL_TIMEOUT);
        if (now.isBefore(deadline)) {
            return task;
        }
        if (dualTaskRepository.rollbackIfUnsettled(task.id(), RollbackReason.EXPIRED, now.toString()) == 1) {
            dualReleaseRepository.incrementRolledBack(releaseId);
        }
        return dualTaskRepository.findById(task.id()).orElseThrow();
    }

    private DualTaskView viewOf(long taskId) {
        return DualTaskView.of(dualTaskRepository.findById(taskId).orElseThrow());
    }
}
