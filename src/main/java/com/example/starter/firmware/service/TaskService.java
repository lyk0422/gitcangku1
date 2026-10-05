package com.example.starter.firmware.service;

import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseKind;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RollbackReason;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.RollbackRecordRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 投放任务：设备拉取与回执。与取消、恢复并发时统一先锁发布单行，再操作任务，形成一致提交顺序。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 * 双分区任务：试启动进入 TRIAL（不切换版本），60 秒内健康确认才 COMMITTED 并切换分区与版本，
 * 到期或 FAILED 即回滚（保留旧分区旧版本，记录回滚且每个任务至多一条）；到期任务由查询与回执懒惰结算。
 */
@Service
public class TaskService {

    /**
     * 双分区试启动健康确认窗口（秒）：试启动时刻起 60 秒内确认有效，达到 60 秒即到期回滚。
     */
    static final long TRIAL_WINDOW_SECONDS = 60;

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final RollbackRecordRepository rollbackRecordRepository;
    private final DeviceService deviceService;
    private final IdempotencyService idempotency;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, PauseRecordRepository pauseRecordRepository,
                       RollbackRecordRepository rollbackRecordRepository, DeviceService deviceService,
                       IdempotencyService idempotency, TransactionTemplate transactionTemplate, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.rollbackRecordRepository = rollbackRecordRepository;
        this.deviceService = deviceService;
        this.idempotency = idempotency;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在未终结任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     * PAUSED 时不创建新任务，已有任务仍可查看与回执。
     * 双分区任务先结算到期 TRIAL；最新尝试已回滚且仍具备投放资格时，以递增尝试序号创建新任务（新任务ID）。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = String.join("|", "task.pull", deviceId);
        return idempotency.execute(requestId, "task.pull", fingerprint, () -> {
            Device device = deviceService.findDevice(deviceId);
            var activeOrder = releaseRepository.findActiveByModel(device.model());
            if (activeOrder.isEmpty()) {
                return new PullResponse(null);
            }
            ReleaseOrder order = releaseRepository.findByIdForUpdate(activeOrder.get().id())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            Instant now = Instant.now(clock);
            var existing = taskRepository.findLatestByReleaseAndDevice(order.id(), deviceId);
            if (existing.isPresent()) {
                RolloutTask task = existing.get();
                if (order.kind() == ReleaseKind.DUAL_PARTITION
                        && task.status() == TaskStatus.TRIAL && trialExpired(task, now)) {
                    rollbackLocked(task, order, RollbackReason.EXPIRED, now);
                    task = taskRepository.findById(task.id()).orElseThrow();
                }
                if (order.kind() != ReleaseKind.DUAL_PARTITION
                        || task.status() != TaskStatus.ROLLED_BACK) {
                    return new PullResponse(TaskView.of(task, order));
                }
                // 双分区已回滚：重试必须产生新任务ID
                if (!eligible(order, device)) {
                    return new PullResponse(null);
                }
                return createTask(order, deviceId, task.attemptNo() + 1);
            }
            if (!eligible(order, device)) {
                return new PullResponse(null);
            }
            return createTask(order, deviceId, 1);
        }, PullResponse.class);
    }

    private boolean eligible(ReleaseOrder order, Device device) {
        return order.status() == ReleaseStatus.ACTIVE
                && device.currentVersion().equals(order.fromVersion())
                && device.bucketNo() < order.ratio();
    }

    private PullResponse createTask(ReleaseOrder order, String deviceId, int attemptNo) {
        long taskId;
        try {
            taskId = taskRepository.insert(order.id(), deviceId, attemptNo);
        } catch (DuplicateKeyException e) {
            RolloutTask task = taskRepository.findLatestByReleaseAndDevice(order.id(), deviceId)
                    .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
            return new PullResponse(TaskView.of(task, order));
        }
        RolloutTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
        return new PullResponse(TaskView.of(task, order));
    }

    /**
     * 回执：普通任务首次回执终结并计入完成时所在监控轮次，仅 SUCCESS 更新设备当前版本；
     * 双分区任务按 PENDING/TRIAL/COMMITTED/ROLLED_BACK 状态机流转，见 dualReceipt。
     * 同结果重复成功（不重复计数），改结果 409；已取消任务的后到回执 409 且不更新设备版本。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = idempotency.structuredFingerprint("task.receipt", taskId, request.operator(),
                request.result().name(), request.targetPartition(), request.targetVersion());
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            if (lockedOrder.kind() == ReleaseKind.DUAL_PARTITION) {
                return dualReceipt(task, lockedOrder, request, Instant.now(clock));
            }
            return grayReceipt(task, lockedOrder, request);
        }, TaskView.class);
    }

    /**
     * 普通灰度任务回执：规则保持不变。
     */
    private TaskView grayReceipt(RolloutTask task, ReleaseOrder lockedOrder, ReceiptRequest request) {
        return switch (task.status()) {
            case PENDING -> {
                taskRepository.complete(task.id(), request.result());
                releaseRepository.incrementRoundStats(lockedOrder.id(), request.result());
                if (request.result() == ReceiptResult.SUCCESS) {
                    deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
                }
                ReleaseOrder updated = releaseRepository.findById(lockedOrder.id()).orElseThrow();
                pauseIfThresholdReached(updated, task.id());
                yield TaskView.of(taskRepository.findById(task.id()).orElseThrow(), updated);
            }
            case SUCCESS, FAILED -> {
                if (task.firstResult() == request.result()) {
                    yield TaskView.of(task, lockedOrder);
                }
                throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                        "任务已终结为 " + task.firstResult() + "，不能改为 " + request.result());
            }
            case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            default -> throw new IllegalStateException("普通任务非法状态: " + task.status());
        };
    }

    /**
     * 双分区任务回执：先懒惰结算到期 TRIAL，再按状态机处理。
     * PENDING+SUCCESS 为试启动（记录目标分区、目标版本与服务端 UTC 试启动时刻，不更新 currentVersion）；
     * TRIAL+SUCCESS 为健康确认（窗口内才 COMMITTED 并切换分区与版本）；FAILED 在任一未终结状态回滚。
     */
    private TaskView dualReceipt(RolloutTask task, ReleaseOrder order, ReceiptRequest request, Instant now) {
        if (task.status() == TaskStatus.TRIAL && trialExpired(task, now)) {
            rollbackLocked(task, order, RollbackReason.EXPIRED, now);
            task = taskRepository.findById(task.id()).orElseThrow();
        }
        return switch (task.status()) {
            case PENDING -> dualPendingReceipt(task, order, request, now);
            case TRIAL -> dualTrialReceipt(task, order, request, now);
            case COMMITTED -> {
                if (request.result() == ReceiptResult.SUCCESS) {
                    yield TaskView.of(task, order);
                }
                throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                        "任务已确认为 COMMITTED，不能改为 FAILED");
            }
            case ROLLED_BACK -> {
                if (request.result() == ReceiptResult.FAILED) {
                    yield TaskView.of(task, order);
                }
                throw ApiException.conflict("TASK_ROLLED_BACK", "任务已回滚，健康确认不再受理");
            }
            case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            default -> throw new IllegalStateException("双分区任务非法状态: " + task.status());
        };
    }

    /**
     * 试启动登记：发布暂停禁止新试启动；只能切换到非当前分区；目标版本须与发布单一致。
     */
    private TaskView dualPendingReceipt(RolloutTask task, ReleaseOrder order, ReceiptRequest request,
                                        Instant now) {
        if (request.result() == ReceiptResult.FAILED) {
            rollbackLocked(task, order, RollbackReason.FAILED, now);
            return TaskView.of(taskRepository.findById(task.id()).orElseThrow(), order);
        }
        if (order.status() != ReleaseStatus.ACTIVE) {
            throw ApiException.conflict("RELEASE_NOT_ACTIVE",
                    "发布单状态为 " + order.status() + "，禁止新试启动");
        }
        if (request.targetPartition() == null || request.targetVersion() == null) {
            throw ApiException.badRequest("TRIAL_FIELDS_REQUIRED",
                    "试启动回执必须携带 targetPartition 与 targetVersion");
        }
        Device device = deviceRepository.findById(task.deviceId())
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + task.deviceId()));
        if (request.targetPartition().equals(device.activePartition())) {
            throw ApiException.conflict("TARGET_PARTITION_CURRENT",
                    "只能切换到非当前分区，当前分区: " + device.activePartition());
        }
        if (!request.targetVersion().equals(order.toVersion())) {
            throw ApiException.conflict("TARGET_VERSION_MISMATCH",
                    "试启动目标版本与发布单目标版本不一致: " + order.toVersion());
        }
        if (taskRepository.startTrial(task.id(), request.targetPartition(), request.targetVersion(),
                now.toString()) != 1) {
            throw new IllegalStateException("试启动状态迁移冲突: " + task.id());
        }
        return TaskView.of(taskRepository.findById(task.id()).orElseThrow(), order);
    }

    /**
     * 健康确认：试启动字段不可改；窗口内确认才 COMMITTED 并切换设备分区与版本，计入本轮成功统计。
     */
    private TaskView dualTrialReceipt(RolloutTask task, ReleaseOrder order, ReceiptRequest request,
                                      Instant now) {
        if (request.result() == ReceiptResult.FAILED) {
            rollbackLocked(task, order, RollbackReason.FAILED, now);
            return TaskView.of(taskRepository.findById(task.id()).orElseThrow(), order);
        }
        if (request.targetPartition() != null && !request.targetPartition().equals(task.targetPartition())) {
            throw ApiException.conflict("TRIAL_FIELDS_IMMUTABLE", "试启动目标分区不可修改");
        }
        if (request.targetVersion() != null && !request.targetVersion().equals(task.targetVersion())) {
            throw ApiException.conflict("TRIAL_FIELDS_IMMUTABLE", "试启动目标版本不可修改");
        }
        if (taskRepository.commitTrial(task.id()) != 1) {
            throw new IllegalStateException("健康确认状态迁移冲突: " + task.id());
        }
        deviceRepository.updateBoot(task.deviceId(), task.targetPartition(), task.targetVersion());
        releaseRepository.incrementRoundStats(order.id(), ReceiptResult.SUCCESS);
        ReleaseOrder updated = releaseRepository.findById(order.id()).orElseThrow();
        pauseIfThresholdReached(updated, task.id());
        return TaskView.of(taskRepository.findById(task.id()).orElseThrow(), updated);
    }

    /**
     * 回滚：任务转 ROLLED_BACK（终态），设备保留旧分区旧版本；记录回滚（每任务至多一条，由唯一约束兜底），
     * 计入本轮失败统计（不计为升级成功），并评估失败率暂停。调用方持有发布单行锁与任务行锁。
     */
    private void rollbackLocked(RolloutTask task, ReleaseOrder order, RollbackReason reason, Instant now) {
        if (taskRepository.rollback(task.id()) != 1) {
            throw new IllegalStateException("回滚状态迁移冲突: " + task.id());
        }
        Device device = deviceRepository.findById(task.deviceId())
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + task.deviceId()));
        rollbackRecordRepository.insert(task.id(), order.id(), task.deviceId(), device.activePartition(),
                device.currentVersion(), reason, now.toString());
        releaseRepository.incrementRoundStats(order.id(), ReceiptResult.FAILED);
        ReleaseOrder updated = releaseRepository.findById(order.id()).orElseThrow();
        pauseIfThresholdReached(updated, task.id());
    }

    /**
     * 结算发布单下所有到期的 TRIAL 任务（查询触发，不依赖后台线程）。
     */
    public void settleExpiredTrials(long releaseId) {
        transactionTemplate.executeWithoutResult(status -> {
            var order = releaseRepository.findByIdForUpdate(releaseId);
            order.ifPresent(o -> settleExpiredTrialsLocked(o, Instant.now(clock)));
        });
    }

    private void settleExpiredTrialsLocked(ReleaseOrder order, Instant now) {
        if (order.kind() != ReleaseKind.DUAL_PARTITION) {
            return;
        }
        for (RolloutTask task : taskRepository.findTrialsByRelease(order.id())) {
            if (trialExpired(task, now)) {
                rollbackLocked(task, order, RollbackReason.EXPIRED, now);
            }
        }
    }

    /**
     * 试启动是否已到期：以服务端记录的试启动时刻加 60 秒为截止，达到即到期；不信任设备时间。
     */
    private boolean trialExpired(RolloutTask task, Instant now) {
        return task.trialStartedAtUtc() != null
                && !now.isBefore(Instant.parse(task.trialStartedAtUtc()).plusSeconds(TRIAL_WINDOW_SECONDS));
    }

    /**
     * 样本数达到下限且 FAILED×100 >= 样本数×阈值 时，将仍为 ACTIVE 的发布单原子转为 PAUSED。
     * 调用方持有发布单行锁，并发回执串行通过，每轮至多生成一条暂停记录。
     */
    private void pauseIfThresholdReached(ReleaseOrder order, long triggerTaskId) {
        if (order.status() != ReleaseStatus.ACTIVE) {
            return;
        }
        int samples = order.roundSuccess() + order.roundFailed();
        if (samples < order.sampleFloor()) {
            return;
        }
        if ((long) order.roundFailed() * 100 < (long) samples * order.failureThresholdPercent()) {
            return;
        }
        if (releaseRepository.pauseIfActive(order.id()) == 1) {
            pauseRecordRepository.insert(order.id(), order.monitorRound(), triggerTaskId,
                    order.roundSuccess(), order.roundFailed(), Instant.now(clock).toString());
        }
    }

    /**
     * 任务明细查询：先在同一事务内结算到期 TRIAL 任务再返回，不依赖后台线程。
     */
    public TaskListResponse listByRelease(long releaseId, TaskStatus statusFilter) {
        return transactionTemplate.execute(status -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            settleExpiredTrialsLocked(order, Instant.now(clock));
            List<TaskView> tasks = taskRepository.findByRelease(releaseId, statusFilter).stream()
                    .map(task -> TaskView.of(task, order))
                    .toList();
            return new TaskListResponse(tasks);
        });
    }
}