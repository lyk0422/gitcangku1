package com.example.starter.firmware.service;

import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskAttempt;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.ChunkReceiptRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskAttemptRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 投放任务：设备拉取与回执。与取消、恢复并发时统一先锁发布单行，再操作任务，形成一致提交顺序。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 * 登记了分片清单的发布单：任务须先经完整性核验进入 INSTALLABLE 才可回执；
 * INTEGRITY_FAILED 任务禁止安装回执且不计入失败率，重新拉取建立新尝试代次，旧失败记录保留。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final TaskAttemptRepository taskAttemptRepository;
    private final ChunkReceiptRepository chunkReceiptRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, PauseRecordRepository pauseRecordRepository,
                       TaskAttemptRepository taskAttemptRepository,
                       ChunkReceiptRepository chunkReceiptRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.taskAttemptRepository = taskAttemptRepository;
        this.chunkReceiptRepository = chunkReceiptRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回；INTEGRITY_FAILED 任务重新拉取时建立新尝试代次（旧失败记录保留）；
     * 否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     * PAUSED 时不创建新任务，已有任务仍可查看与回执。
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
            var existing = taskRepository.findByReleaseAndDevice(order.id(), deviceId);
            if (existing.isPresent()) {
                RolloutTask task = existing.get();
                if (order.hasManifest() && task.status() == TaskStatus.INTEGRITY_FAILED) {
                    task = reopenAttempt(task, order);
                }
                return new PullResponse(viewOf(task, order));
            }
            if (order.status() != ReleaseStatus.ACTIVE
                    || !device.currentVersion().equals(order.fromVersion())
                    || device.bucketNo() >= order.ratio()) {
                return new PullResponse(null);
            }
            long taskId;
            try {
                taskId = taskRepository.insert(order.id(), deviceId);
            } catch (DuplicateKeyException e) {
                RolloutTask task = taskRepository.findByReleaseAndDevice(order.id(), deviceId)
                        .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                return new PullResponse(viewOf(task, order));
            }
            if (order.hasManifest()) {
                taskAttemptRepository.insert(taskId, 1, Instant.now(clock).toString());
            }
            RolloutTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
            return new PullResponse(viewOf(task, order));
        }, PullResponse.class);
    }

    /**
     * 重新拉取：仅未安装的 INTEGRITY_FAILED 任务建立新尝试代次，任务回到 PENDING，旧代次与证据保留。
     */
    private RolloutTask reopenAttempt(RolloutTask task, ReleaseOrder order) {
        TaskAttempt current = taskAttemptRepository.findCurrent(task.id())
                .orElseThrow(() -> new IllegalStateException("清单任务缺少尝试代次: " + task.id()));
        taskAttemptRepository.insert(task.id(), current.attemptNo() + 1, Instant.now(clock).toString());
        taskRepository.updateStatus(task.id(), TaskStatus.PENDING);
        return taskRepository.findById(task.id())
                .orElseThrow(() -> new IllegalStateException("任务重拉后读取失败"));
    }

    /**
     * 回执：首次回执终结任务并计入完成时所在监控轮次，仅 SUCCESS 更新设备当前版本；
     * 同结果重复成功（不重复计数），改结果 409；已取消任务的后到回执 409 且不更新设备版本。
     * 清单任务仅 INSTALLABLE 可回执；PENDING 返回 409 并给出接收进度（实际值/要求值）；
     * INTEGRITY_FAILED 禁止安装回执（409），不计入设备执行失败率。
     * 样本达到下限且失败率越限时，同事务将仍为 ACTIVE 的发布单原子转为 PAUSED 并落暂停记录。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = String.join("|", "task.receipt", String.valueOf(taskId), request.result().name());
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case PENDING -> {
                    if (lockedOrder.hasManifest()) {
                        int received = currentReceived(taskId);
                        throw ApiException.conflict("TASK_NOT_INSTALLABLE",
                                "任务未完成分片完整性核验，禁止安装回执：已接收 " + received + "/"
                                        + lockedOrder.chunkCount() + " 个分片");
                    }
                    yield completeTask(task, lockedOrder, request.result());
                }
                case INSTALLABLE -> completeTask(task, lockedOrder, request.result());
                case INTEGRITY_FAILED -> throw ApiException.conflict("TASK_INTEGRITY_FAILED",
                        "任务分片完整性失败，禁止安装回执；需重新拉取建立新尝试代次");
                case SUCCESS, FAILED -> {
                    if (task.firstResult() == request.result()) {
                        yield viewOf(task, lockedOrder);
                    }
                    throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                            "任务已终结为 " + task.firstResult() + "，不能改为 " + request.result());
                }
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            };
        }, TaskView.class);
    }

    /**
     * 任务撤销：未终结任务（PENDING/INSTALLABLE/INTEGRITY_FAILED）转为 CANCELLED；
     * 已取消幂等返回当前状态；已终结（SUCCESS/FAILED）返回 409。
     */
    public TaskView revoke(long taskId, String requestId) {
        String fingerprint = String.join("|", "task.revoke", String.valueOf(taskId));
        return idempotency.execute(requestId, "task.revoke", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case PENDING, INSTALLABLE, INTEGRITY_FAILED -> {
                    taskRepository.updateStatus(taskId, TaskStatus.CANCELLED);
                    finalizeAttempt(taskId, TaskStatus.CANCELLED);
                    yield viewOf(taskRepository.findById(taskId).orElseThrow(), lockedOrder);
                }
                case CANCELLED -> viewOf(task, lockedOrder);
                case SUCCESS, FAILED -> throw ApiException.conflict("TASK_ALREADY_FINAL",
                        "任务已终结为 " + task.status() + "，不能撤销");
            };
        }, TaskView.class);
    }

    /**
     * 任务详情（只读，不触发状态变化）。
     */
    public TaskView detail(long taskId) {
        RolloutTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        return viewOf(task, releaseService.findOrder(task.releaseId()));
    }

    /**
     * 首次回执终结任务：累计监控轮次统计、更新设备版本、终结当前尝试代次，必要时原子暂停。
     * 调用方持有发布单行锁与任务行锁。
     */
    private TaskView completeTask(RolloutTask task, ReleaseOrder lockedOrder, ReceiptResult result) {
        taskRepository.complete(task.id(), result);
        releaseRepository.incrementRoundStats(task.releaseId(), result);
        if (result == ReceiptResult.SUCCESS) {
            deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
        }
        finalizeAttempt(task.id(), result == ReceiptResult.SUCCESS ? TaskStatus.SUCCESS : TaskStatus.FAILED);
        ReleaseOrder updated = releaseRepository.findById(task.releaseId()).orElseThrow();
        pauseIfThresholdReached(updated, task.id());
        return viewOf(taskRepository.findById(task.id()).orElseThrow(), updated);
    }

    /**
     * 终结清单任务的当前尝试代次；旧式任务无代次记录，直接跳过。
     */
    private void finalizeAttempt(long taskId, TaskStatus finalStatus) {
        taskAttemptRepository.findCurrent(taskId).ifPresent(attempt ->
                taskAttemptRepository.updateStatus(taskId, attempt.attemptNo(), finalStatus,
                        Instant.now(clock).toString()));
    }

    private int currentReceived(long taskId) {
        return taskAttemptRepository.findCurrent(taskId)
                .map(attempt -> chunkReceiptRepository.countByTaskAndAttempt(taskId, attempt.attemptNo()))
                .orElse(0);
    }

    /**
     * 任务视图：清单任务携带当前尝试代次与接收进度，旧式任务完整性字段为 null。
     */
    private TaskView viewOf(RolloutTask task, ReleaseOrder order) {
        if (!order.hasManifest()) {
            return TaskView.of(task, order);
        }
        int attemptNo = taskAttemptRepository.findCurrent(task.id())
                .map(TaskAttempt::attemptNo)
                .orElseThrow(() -> new IllegalStateException("清单任务缺少尝试代次: " + task.id()));
        return TaskView.of(task, order, attemptNo,
                chunkReceiptRepository.countByTaskAndAttempt(task.id(), attemptNo));
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

    public TaskListResponse listByRelease(long releaseId, TaskStatus statusFilter) {
        ReleaseOrder order = releaseService.findOrder(releaseId);
        List<TaskView> tasks = taskRepository.findByRelease(releaseId, statusFilter).stream()
                .map(task -> viewOf(task, order))
                .toList();
        return new TaskListResponse(tasks);
    }
}
