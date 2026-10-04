package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DualBootReceiptRequest;
import com.example.starter.firmware.api.DualBootTaskListResponse;
import com.example.starter.firmware.api.DualBootTaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.DualBootTask;
import com.example.starter.firmware.domain.DualBootTaskStatus;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.DualBootTaskRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 双分区试启动任务：设备拉取后提交试启动回执进入 TRIAL（不切换当前版本），
 * 60 秒内健康确认才 COMMITTED 并切换版本与分区；达到 60 秒或 FAILED 即回滚。
 * 试启动时刻与截止时刻全部来自可注入服务端 Clock，不信任设备上报。
 * 健康确认、失败与到期检查统一先锁发布单行再锁任务行，竞争只产生一个终态，
 * 失败率统计只计一次；查询与回执路径同步结算到期任务，不依赖后台线程。
 */
@Service
public class DualBootTaskService {

    /**
     * 试启动健康确认窗口：60 秒。第 59 秒确认有效，达到第 60 秒即过期。
     */
    public static final long TRIAL_TIMEOUT_SECONDS = 60;

    private final DualBootTaskRepository dualBootTaskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public DualBootTaskService(DualBootTaskRepository dualBootTaskRepository,
                               ReleaseRepository releaseRepository, DeviceRepository deviceRepository,
                               ReleaseService releaseService, IdempotencyService idempotency, Clock clock) {
        this.dualBootTaskRepository = dualBootTaskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 双分区发布拉取（调用方已持有发布单行锁）：有未终结任务先结算到期再返回；
     * 已回滚任务不复活，重试创建新任务ID；无匹配投放返回 null。
     */
    public DualBootTaskView pullForDevice(Device device, ReleaseOrder lockedOrder) {
        Instant now = Instant.now(clock);
        var open = dualBootTaskRepository.findOpenByReleaseAndDevice(lockedOrder.id(), device.deviceId());
        if (open.isPresent()) {
            DualBootTask task = settleIfExpired(lockedOrder, open.get(), now);
            if (task.status() == DualBootTaskStatus.PENDING || task.status() == DualBootTaskStatus.TRIAL) {
                return DualBootTaskView.of(task, lockedOrder);
            }
        }
        if (lockedOrder.status() != ReleaseStatus.ACTIVE
                || !device.currentVersion().equals(lockedOrder.fromVersion())
                || device.bucketNo() >= lockedOrder.ratio()) {
            return null;
        }
        long taskId;
        try {
            taskId = dualBootTaskRepository.insert(lockedOrder.id(), device.deviceId());
        } catch (DuplicateKeyException e) {
            DualBootTask task = dualBootTaskRepository
                    .findOpenByReleaseAndDevice(lockedOrder.id(), device.deviceId())
                    .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
            return DualBootTaskView.of(task, lockedOrder);
        }
        return DualBootTaskView.of(dualBootTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务创建后读取失败")), lockedOrder);
    }

    /**
     * 双分区回执：TRIAL_BOOT 试启动、HEALTHY 健康确认、FAILED 失败回滚。
     * 进入处理前先结算到期 TRIAL；过期健康回执 409。同 requestId 同参重放首次完整响应（阶段冻结）。
     */
    public DualBootTaskView receipt(long taskId, DualBootReceiptRequest request) {
        String fingerprint = Fingerprint.of("dualBoot.receipt", taskId, request.action().name(),
                request.targetPartition(), request.targetVersion());
        return idempotency.execute(request.requestId(), "dualBoot.receipt", fingerprint, () -> {
            DualBootTask snapshot = dualBootTaskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            DualBootTask task = dualBootTaskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            boolean expiredNow = false;
            if (task.status() == DualBootTaskStatus.TRIAL) {
                DualBootTask settled = settleIfExpired(lockedOrder, task, Instant.now(clock));
                expiredNow = settled.status() == DualBootTaskStatus.ROLLED_BACK;
                task = settled;
            }
            DualBootTask current = task;
            boolean expired = expiredNow;
            return switch (request.action()) {
                case TRIAL_BOOT -> trialBoot(lockedOrder, current, request);
                case HEALTHY -> healthy(lockedOrder, current, expired);
                case FAILED -> failed(lockedOrder, current);
            };
        }, DualBootTaskView.class);
    }

    /**
     * 试启动：仅 PENDING 可进入 TRIAL；发布暂停禁止新试启动；只能切换非当前分区；
     * 目标版本必须等于发布单目标版本。TRIAL 中同参重复回执返回当前视图，异参 409（字段不可改）。
     */
    private DualBootTaskView trialBoot(ReleaseOrder order, DualBootTask task,
                                       DualBootReceiptRequest request) {
        switch (task.status()) {
            case PENDING -> {
                if (order.status() != ReleaseStatus.ACTIVE) {
                    throw ApiException.conflict("RELEASE_NOT_ACTIVE",
                            "发布单状态为 " + order.status() + "，禁止新试启动");
                }
                if (request.targetPartition() == null || request.targetVersion() == null) {
                    throw ApiException.badRequest("MISSING_TRIAL_FIELDS",
                            "试启动回执必须携带 targetPartition 与 targetVersion");
                }
                if (!request.targetVersion().equals(order.toVersion())) {
                    throw ApiException.conflict("TARGET_VERSION_MISMATCH",
                            "目标版本与发布单不一致: " + request.targetVersion());
                }
                Device device = deviceRepository.findById(task.deviceId())
                        .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在"));
                if (request.targetPartition().equals(device.activePartition())) {
                    throw ApiException.conflict("INVALID_TARGET_PARTITION",
                            "只能切换非当前分区，当前: " + device.activePartition());
                }
                Instant started = Instant.now(clock);
                dualBootTaskRepository.startTrial(task.id(), request.targetPartition(),
                        request.targetVersion(), started.toString(),
                        started.plusSeconds(TRIAL_TIMEOUT_SECONDS).toString());
                return DualBootTaskView.of(dualBootTaskRepository.findById(task.id()).orElseThrow(), order);
            }
            case TRIAL -> {
                if (request.targetPartition() != null && request.targetVersion() != null
                        && request.targetPartition().equals(task.targetPartition())
                        && request.targetVersion().equals(task.targetVersion())) {
                    return DualBootTaskView.of(task, order);
                }
                throw ApiException.conflict("TRIAL_FIELDS_CONFLICT", "同任务试启动字段不可改");
            }
            case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            default -> throw ApiException.conflict("TASK_ALREADY_TERMINAL",
                    "任务已终结为 " + task.status() + "，不能试启动");
        }
    }

    /**
     * 健康确认：仅未到期 TRIAL 可 COMMITTED，同时切换设备版本与分区并计一次成功；
     * 过期（含本次结算）409；重复确认返回当前视图。
     */
    private DualBootTaskView healthy(ReleaseOrder order, DualBootTask task, boolean expiredNow) {
        switch (task.status()) {
            case TRIAL -> {
                dualBootTaskRepository.terminate(task.id(), DualBootTaskStatus.COMMITTED);
                deviceRepository.updateVersionAndPartition(task.deviceId(), task.targetVersion(),
                        task.targetPartition());
                releaseRepository.incrementRoundStats(order.id(), ReceiptResult.SUCCESS);
                ReleaseOrder updated = releaseRepository.findById(order.id()).orElseThrow();
                releaseService.pauseIfThresholdReached(updated, task.id());
                return DualBootTaskView.of(dualBootTaskRepository.findById(task.id()).orElseThrow(), updated);
            }
            case COMMITTED -> {
                return DualBootTaskView.of(task, order);
            }
            case ROLLED_BACK -> throw ApiException.conflict(
                    expiredNow ? "HEALTH_CONFIRM_EXPIRED" : "TASK_ROLLED_BACK",
                    expiredNow ? "健康确认已超过60秒截止时刻，任务已回滚" : "任务已回滚，健康确认不再受理");
            case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            default -> throw ApiException.conflict("TRIAL_NOT_STARTED", "任务尚未试启动，不能健康确认");
        }
    }

    /**
     * 失败回执：PENDING/TRIAL 回滚并计一次失败；已回滚重复失败返回当前视图。
     */
    private DualBootTaskView failed(ReleaseOrder order, DualBootTask task) {
        switch (task.status()) {
            case PENDING, TRIAL -> {
                rollback(order, task);
                return DualBootTaskView.of(dualBootTaskRepository.findById(task.id()).orElseThrow(), order);
            }
            case ROLLED_BACK -> {
                return DualBootTaskView.of(task, order);
            }
            case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            default -> throw ApiException.conflict("TASK_ALREADY_COMMITTED", "任务已确认切换，不能失败回滚");
        }
    }

    /**
     * 单任务查询：先结算到期再返回。
     */
    @Transactional
    public DualBootTaskView get(long taskId) {
        DualBootTask snapshot = dualBootTaskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
        DualBootTask task = dualBootTaskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        task = settleIfExpired(lockedOrder, task, Instant.now(clock));
        return DualBootTaskView.of(task, lockedOrder);
    }

    /**
     * 任务明细查询：先结算发布单下全部到期 TRIAL 再返回，不依赖后台线程。
     */
    @Transactional
    public DualBootTaskListResponse listByRelease(long releaseId, DualBootTaskStatus statusFilter) {
        ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
        Instant now = Instant.now(clock);
        for (DualBootTask trial : dualBootTaskRepository.findTrialsByRelease(releaseId)) {
            settleIfExpired(lockedOrder, trial, now);
        }
        List<DualBootTaskView> tasks = dualBootTaskRepository.findByRelease(releaseId, statusFilter)
                .stream().map(task -> DualBootTaskView.of(task, lockedOrder)).toList();
        return new DualBootTaskListResponse(tasks);
    }

    /**
     * 到期结算：TRIAL 且当前时刻不早于截止时刻则回滚。调用方持有发布单行锁；
     * 任务行再加锁复核，与健康确认/失败竞争只产生一个终态。
     */
    private DualBootTask settleIfExpired(ReleaseOrder lockedOrder, DualBootTask task, Instant now) {
        if (task.status() != DualBootTaskStatus.TRIAL || notExpired(task, now)) {
            return task;
        }
        DualBootTask locked = dualBootTaskRepository.findByIdForUpdate(task.id()).orElseThrow();
        if (locked.status() != DualBootTaskStatus.TRIAL || notExpired(locked, now)) {
            return locked;
        }
        rollback(lockedOrder, locked);
        return dualBootTaskRepository.findById(locked.id()).orElseThrow();
    }

    private boolean notExpired(DualBootTask task, Instant now) {
        return now.isBefore(Instant.parse(task.trialDeadlineUtc()));
    }

    /**
     * 回滚：保留旧分区与旧版本（不改设备表），计一次失败并触发暂停判定。
     * 受状态守卫 UPDATE 保护，竞争下只终结一次、统计只计一次。
     */
    private void rollback(ReleaseOrder lockedOrder, DualBootTask task) {
        if (dualBootTaskRepository.terminate(task.id(), DualBootTaskStatus.ROLLED_BACK) == 1) {
            releaseRepository.incrementRoundStats(lockedOrder.id(), ReceiptResult.FAILED);
            ReleaseOrder updated = releaseRepository.findById(lockedOrder.id()).orElseThrow();
            releaseService.pauseIfThresholdReached(updated, task.id());
        }
    }
}