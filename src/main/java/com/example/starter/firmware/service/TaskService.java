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
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.ShardReceiptRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 投放任务：设备拉取与安装回执。与取消、恢复并发时统一先锁发布单行，再操作任务，形成一致提交顺序。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 * 已登记分片清单的发布单：任务必须逐分片提交并核验通过（INSTALLABLE）后才允许安装回执；
 * 完整性失败（INTEGRITY_FAILED）禁止安装与成功回执、不计设备执行失败率，
 * 重新拉取为未安装任务建立新尝试代次，旧失败记录保留不改写。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final ShardReceiptRepository shardReceiptRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, PauseRecordRepository pauseRecordRepository,
                       ShardReceiptRepository shardReceiptRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.shardReceiptRepository = shardReceiptRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回最新代次；否则仅当型号与当前版本匹配、分桶号小于比例且发布单
     * ACTIVE 时创建。PAUSED 时不创建新任务，已有任务仍可查看与回执。
     * 最新代次为 INTEGRITY_FAILED 且发布单仍 ACTIVE、设备仍匹配时，重新拉取建立新尝试代次，
     * 旧失败记录与证据保留不改写。
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
                RolloutTask latest = existing.get();
                if (latest.status() == TaskStatus.INTEGRITY_FAILED && order.status() == ReleaseStatus.ACTIVE
                        && device.currentVersion().equals(order.fromVersion())
                        && device.bucketNo() < order.ratio()) {
                    return new PullResponse(TaskView.of(createAttempt(order, deviceId,
                            latest.attemptNo() + 1), order));
                }
                return new PullResponse(TaskView.of(latest, order));
            }
            if (order.status() != ReleaseStatus.ACTIVE
                    || !device.currentVersion().equals(order.fromVersion())
                    || device.bucketNo() >= order.ratio()) {
                return new PullResponse(null);
            }
            return new PullResponse(TaskView.of(createAttempt(order, deviceId, 1), order));
        }, PullResponse.class);
    }

    /**
     * 建立新一代任务尝试；唯一约束冲突时返回已有最新代次（并发拉取至多一条成功）。
     */
    private RolloutTask createAttempt(ReleaseOrder order, String deviceId, int attemptNo) {
        try {
            long taskId = taskRepository.insert(order.id(), deviceId, attemptNo);
            return taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
        } catch (DuplicateKeyException e) {
            return taskRepository.findLatestByReleaseAndDevice(order.id(), deviceId)
                    .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
        }
    }

    /**
     * 安装回执：仅 INSTALLABLE（已登记清单时）或 PENDING（未登记清单的旧流程）可回执；
     * INTEGRITY_FAILED 禁止安装和成功回执且不计失败率；已取消任务的后到回执 409。
     * 首次回执终结任务并计入完成时所在监控轮次，仅 SUCCESS 更新设备当前版本；
     * 同结果重复成功（不重复计数），改结果 409。
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
                    if (lockedOrder.shardCount() != null) {
                        long received = shardReceiptRepository
                                .findByTaskAndAttempt(taskId, task.attemptNo()).size();
                        throw ApiException.conflict("TASK_NOT_INSTALLABLE",
                                "任务分片未核验通过，禁止安装回执：已接收分片 " + received
                                        + "，要求 " + lockedOrder.shardCount());
                    }
                    yield completeInstall(taskId, request.result(), snapshot.releaseId(), lockedOrder, task);
                }
                case INSTALLABLE ->
                        completeInstall(taskId, request.result(), snapshot.releaseId(), lockedOrder, task);
                case INTEGRITY_FAILED -> throw ApiException.conflict("TASK_INTEGRITY_FAILED",
                        "任务完整性核验失败，禁止安装和成功回执；请重新拉取建立新尝试代次");
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
     * 首次安装回执终结任务：落状态、累计本轮统计、SUCCESS 更新设备版本，并按阈值原子暂停。
     */
    private TaskView completeInstall(long taskId, ReceiptResult result, long releaseId,
                                     ReleaseOrder lockedOrder, RolloutTask task) {
        taskRepository.complete(taskId, result);
        releaseRepository.incrementRoundStats(releaseId, result);
        if (result == ReceiptResult.SUCCESS) {
            deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
        }
        ReleaseOrder updated = releaseRepository.findById(releaseId).orElseThrow();
        pauseIfThresholdReached(updated, taskId);
        return TaskView.of(taskRepository.findById(taskId).orElseThrow(), updated);
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
}
