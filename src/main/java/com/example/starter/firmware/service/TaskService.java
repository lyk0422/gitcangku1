package com.example.starter.firmware.service;

import com.example.starter.firmware.api.AttemptHistoryResponse;
import com.example.starter.firmware.api.AttemptView;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RetryTaskRequest;
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
import java.util.List;

/**
 * 投放任务：设备拉取、显式有限重试与回执。与取消、恢复并发时统一先锁发布单行，再操作任务，
 * 形成一致提交顺序。每次尝试各占一行，只可从最新失败尝试追加新 PENDING 尝试，历史不可改。
 * 回执首次终结某次尝试时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 */
@Service
public class TaskService {

    /**
     * 每个发布单与设备的最大尝试次数，原始任务算第 1 次。
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
     * 设备拉取：已有任意尝试时返回最新一次尝试（未显式重试则仍是原任务，不自动重开）；
     * 否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建第 1 次尝试。
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
            var latest = taskRepository.findLatestByReleaseAndDevice(order.id(), deviceId);
            if (latest.isPresent()) {
                return new PullResponse(TaskView.of(latest.get(), order));
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
     * 显式重试：仅发布单 ACTIVE、旧任务为最新尝试且 FAILED、设备当前版本仍等于 fromVersion 时，
     * 从最新失败尝试追加一个新的 PENDING 尝试。原任务及回执不可改，不增加发布版本、不改比例，
     * 也不立即计入监控样本。次数用尽 422；暂停/取消、PENDING/SUCCESS、非最新失败任务、版本不符 409。
     */
    public TaskView retry(long oldTaskId, RetryTaskRequest request) {
        String fingerprint = String.join("|", "task.retry", String.valueOf(oldTaskId),
                String.valueOf(request.expectedVersion()));
        return idempotency.execute(request.requestId(), "task.retry", fingerprint, () -> {
            RolloutTask referenced = taskRepository.findById(oldTaskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + oldTaskId));
            ReleaseOrder order = releaseRepository.findByIdForUpdate(referenced.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask oldTask = taskRepository.findByIdForUpdate(oldTaskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + oldTaskId));
            Device device = deviceService.findDevice(oldTask.deviceId());

            if (order.status() != ReleaseStatus.ACTIVE) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE",
                        "发布单状态为 " + order.status() + "，不允许重试");
            }
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + order.version());
            }
            RolloutTask latest = taskRepository
                    .findLatestByReleaseAndDevice(order.id(), oldTask.deviceId())
                    .orElseThrow(() -> new IllegalStateException("旧任务存在但未找到最新尝试"));
            if (latest.id() != oldTask.id()) {
                throw ApiException.conflict("NOT_LATEST_ATTEMPT",
                        "只可从最新一次尝试发起重试，最新尝试任务ID: " + latest.id());
            }
            if (oldTask.status() != TaskStatus.FAILED) {
                throw ApiException.conflict("TASK_NOT_FAILED",
                        "仅 FAILED 任务可重试，当前状态: " + oldTask.status());
            }
            if (!device.currentVersion().equals(order.fromVersion())) {
                throw ApiException.conflict("DEVICE_VERSION_CHANGED",
                        "设备当前版本已不是发布来源版本 " + order.fromVersion() + "，不允许重试");
            }
            if (oldTask.attemptNo() >= MAX_ATTEMPTS) {
                throw ApiException.unprocessableEntity("RETRY_EXHAUSTED",
                        "每个发布单与设备最多 " + MAX_ATTEMPTS + " 次尝试，已用尽");
            }
            long newTaskId;
            try {
                newTaskId = taskRepository.insertRetry(order.id(), oldTask.deviceId(),
                        oldTask.attemptNo() + 1, oldTask.id());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("RETRY_ALREADY_EXISTS", "并发重试已创建后继任务");
            }
            RolloutTask newTask = taskRepository.findById(newTaskId)
                    .orElseThrow(() -> new IllegalStateException("重试任务创建后读取失败"));
            return TaskView.of(newTask, order);
        }, TaskView.class);
    }

    /**
     * 回执：首次回执终结该次尝试并计入完成时所在监控轮次，仅 SUCCESS 更新设备当前版本；
     * 同结果重复成功（不重复计数），改结果 409；已取消任务的后到回执 409 且不更新设备版本。
     * 旧任务迟到的不同结果命中 RECEIPT_RESULT_CONFLICT，不改变设备或新尝试。
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
     * 尝试历史：按设备、尝试序号升序列出前后继及结果；仅有一次原始任务的数据兼容为序号 1。
     */
    public AttemptHistoryResponse history(long releaseId, String deviceId) {
        releaseService.findOrder(releaseId);
        deviceService.findDevice(deviceId);
        List<AttemptView> attempts = taskRepository.findChainByReleaseAndDevice(releaseId, deviceId).stream()
                .map(AttemptView::of)
                .toList();
        return new AttemptHistoryResponse(releaseId, deviceId, attempts);
    }
}
