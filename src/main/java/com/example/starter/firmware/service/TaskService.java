package com.example.starter.firmware.service;

import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RetryTaskRequest;
import com.example.starter.firmware.api.TaskAttemptView;
import com.example.starter.firmware.api.TaskHistoryResponse;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 投放任务：设备拉取与回执。与取消、恢复并发时统一先锁发布单行，再操作任务，形成一致提交顺序。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 */
@Service
public class TaskService {

    /**
     * 同一发布单与设备的最大尝试次数（含首次投放）。
     */
    public static final int MAX_ATTEMPTS = 3;

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, PauseRecordRepository pauseRecordRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务返回最新一次尝试（未显式重试时即原任务，不自动重开）；
     * 否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建首次任务。
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
            var existing = taskRepository.findLatestByReleaseAndDevice(order.id(), deviceId);
            if (existing.isPresent()) {
                return new PullResponse(TaskView.of(existing.get(), order));
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
                RolloutTask task = taskRepository.findLatestByReleaseAndDevice(order.id(), deviceId)
                        .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                return new PullResponse(TaskView.of(task, order));
            }
            RolloutTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
            return new PullResponse(TaskView.of(task, order));
        }, PullResponse.class);
    }

    /**
     * 回执：首次回执终结任务并计入完成时所在监控轮次，仅 SUCCESS 更新设备当前版本；
     * 同结果重复成功（不重复计数），改结果 409；已取消任务的后到回执 409 且不更新设备版本。
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
                    taskRepository.complete(taskId, request.result());
                    releaseRepository.incrementRoundStats(snapshot.releaseId(), request.result());
                    if (request.result() == ReceiptResult.SUCCESS) {
                        deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
                    }
                    ReleaseOrder updated = releaseRepository.findById(snapshot.releaseId()).orElseThrow();
                    pauseIfThresholdReached(updated, taskId);
                    yield TaskView.of(taskRepository.findById(taskId).orElseThrow(), updated);
                }
                case SUCCESS, FAILED -> {
                    if (task.firstResult() == request.result()) {
                        yield TaskView.of(task, lockedOrder);
                    }
                    throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                            "任务已终结为 " + task.firstResult() + "，不能改为 " + request.result());
                }
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            };
        }, TaskView.class);
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
                .map(task -> TaskView.of(task, order))
                .toList();
        return new TaskListResponse(tasks);
    }

    /**
     * 失败任务显式重试：仅发布单 ACTIVE、旧任务为最新失败尝试、设备当前版本仍等于 fromVersion 时，
     * 追加一条新的 PENDING 任务（独立ID、尝试序号加一、记录前驱），旧任务及回执不可改写。
     * 不增加发布单版本、不改变投放比例、不立即计入监控样本；同发布同设备最多 MAX_ATTEMPTS 次尝试。
     * 与回执、自动暂停、恢复、取消共用发布单行锁，按提交顺序裁决：取消先提交则重试 409，
     * 重试先提交则取消把新 PENDING 尝试一并终结，不会留下 PENDING。
     */
    public TaskView retry(long taskId, RetryTaskRequest request) {
        String fingerprint = String.join("|", "task.retry", String.valueOf(taskId),
                String.valueOf(request.expectedVersion()));
        return idempotency.execute(request.requestId(), "task.retry", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            if (order.status() != ReleaseStatus.ACTIVE) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE",
                        "发布单状态为 " + order.status() + "，不能重试");
            }
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + order.version());
            }
            RolloutTask latest = taskRepository.findLatestByReleaseAndDevice(order.id(), snapshot.deviceId())
                    .orElseThrow(() -> new IllegalStateException("任务链缺失: " + taskId));
            if (latest.id() != taskId) {
                throw ApiException.conflict("RETRY_NOT_LATEST", "只能对最新一次失败尝试追加重试");
            }
            if (latest.status() != TaskStatus.FAILED) {
                throw ApiException.conflict("TASK_NOT_FAILED",
                        "最新尝试状态为 " + latest.status() + "，仅 FAILED 可重试");
            }
            if (latest.attemptNo() >= MAX_ATTEMPTS) {
                throw ApiException.unprocessable("RETRY_ATTEMPTS_EXHAUSTED",
                        "同一发布与设备最多 " + MAX_ATTEMPTS + " 次尝试，已用尽");
            }
            Device device = deviceService.findDevice(snapshot.deviceId());
            if (!device.currentVersion().equals(order.fromVersion())) {
                throw ApiException.conflict("DEVICE_VERSION_CHANGED",
                        "设备当前版本已不等于来源版本: " + device.currentVersion());
            }
            long newTaskId;
            try {
                newTaskId = taskRepository.insertRetry(order.id(), device.deviceId(),
                        latest.attemptNo() + 1, latest.id());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("RETRY_CONFLICT", "并发重试冲突，已存在后继任务");
            }
            return TaskView.of(taskRepository.findById(newTaskId)
                    .orElseThrow(() -> new IllegalStateException("重试任务创建后读取失败")), order);
        }, TaskView.class);
    }

    /**
     * 设备任务历史：按发布单与尝试序号升序列出每次尝试的前驱、后继与结果；
     * 仅一次投放的历史数据兼容为序号 1。设备不存在返回 404。
     */
    public TaskHistoryResponse listDeviceHistory(String deviceId, Long releaseId) {
        deviceService.findDevice(deviceId);
        List<RolloutTask> tasks = taskRepository.findByDevice(deviceId, releaseId);
        Map<Long, Long> successorByPredecessor = new HashMap<>();
        for (RolloutTask task : tasks) {
            if (task.predecessorId() != null) {
                successorByPredecessor.put(task.predecessorId(), task.id());
            }
        }
        List<TaskAttemptView> attempts = tasks.stream()
                .map(task -> TaskAttemptView.of(task, successorByPredecessor.get(task.id())))
                .toList();
        return new TaskHistoryResponse(deviceId, attempts);
    }
}
