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
import com.example.starter.firmware.domain.StagingReservation;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.StagingReservationRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 投放任务：设备拉取与回执。与取消、恢复并发时统一先锁发布单行，再锁设备行，形成一致提交顺序。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 * 领取按"旧镜像+压缩包+新镜像不超过总空间"核验暂存峰值，不足返回422且不生成任务或预留；
 * SUCCESS 原子删除旧镜像、压缩包及预留并切换当前镜像，FAILED/CANCELLED 只释放包与新镜像预留。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final StagingReservationRepository stagingReservationRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository,
                       StagingReservationRepository stagingReservationRepository,
                       PauseRecordRepository pauseRecordRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.stagingReservationRepository = stagingReservationRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     * PAUSED 时不创建新任务，已有任务仍可查看与回执。
     * 设备与发布单均声明容量时核验暂存峰值并落预留；空间不足 422，不生成任务或预留。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = RequestFingerprint.of("api", "task.pull", "deviceId", deviceId);
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
                return new PullResponse(toView(existing.get(), order));
            }
            if (order.status() != ReleaseStatus.ACTIVE
                    || !device.currentVersion().equals(order.fromVersion())
                    || device.bucketNo() >= order.ratio()) {
                return new PullResponse(null);
            }
            boolean stagingEnabled = device.spaceProfileEnabled() && order.stagingProfileEnabled();
            Long peakBytes = null;
            Long oldImageBytes = null;
            if (stagingEnabled) {
                Device locked = lockDevice(deviceId);
                oldImageBytes = locked.currentImageBytes();
                peakBytes = oldImageBytes + order.packageBytes() + order.imageBytes();
                if (peakBytes > locked.totalSpaceBytes()) {
                    throw ApiException.unprocessable("INSUFFICIENT_STAGING_SPACE",
                            "暂存峰值 " + peakBytes + " 超过设备总空间 " + locked.totalSpaceBytes());
                }
                if (stagingReservationRepository.findByDeviceId(deviceId).isPresent()) {
                    throw ApiException.conflict("STAGING_ALREADY_ACTIVE", "设备已存在活跃暂存: " + deviceId);
                }
            }
            long taskId;
            try {
                taskId = taskRepository.insert(order.id(), deviceId, peakBytes);
            } catch (DuplicateKeyException e) {
                RolloutTask task = taskRepository.findByReleaseAndDevice(order.id(), deviceId)
                        .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                return new PullResponse(toView(task, order));
            }
            if (stagingEnabled) {
                try {
                    stagingReservationRepository.insert(taskId, order.id(), deviceId,
                            oldImageBytes, order.packageBytes(), order.imageBytes());
                } catch (DuplicateKeyException e) {
                    throw ApiException.conflict("STAGING_ALREADY_ACTIVE", "设备已存在活跃暂存: " + deviceId);
                }
            }
            RolloutTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
            return new PullResponse(toView(task, order));
        }, PullResponse.class);
    }

    /**
     * 回执：首次回执终结任务并计入完成时所在监控轮次，仅 SUCCESS 更新设备当前版本；
     * 同结果重复成功（不重复计数），改结果 409；已取消任务的后到回执 409 且不更新设备版本。
     * 样本达到下限且失败率越限时，同事务将仍为 ACTIVE 的发布单原子转为 PAUSED 并落暂停记录。
     * 首次终结时在设备行锁内释放暂存预留并推进空间档案版本。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = RequestFingerprint.of("api", "task.receipt", "taskId", taskId,
                "result", request.result().name());
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case PENDING -> {
                    lockDevice(task.deviceId());
                    taskRepository.complete(taskId, request.result());
                    releaseRepository.incrementRoundStats(snapshot.releaseId(), request.result());
                    terminateStaging(task, request.result(), lockedOrder.toVersion());
                    ReleaseOrder updated = releaseRepository.findById(snapshot.releaseId()).orElseThrow();
                    pauseIfThresholdReached(updated, taskId);
                    yield toView(taskRepository.findById(taskId).orElseThrow(), updated);
                }
                case SUCCESS, FAILED -> {
                    if (task.firstResult() == request.result()) {
                        yield toView(task, lockedOrder);
                    }
                    throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                            "任务已终结为 " + task.firstResult() + "，不能改为 " + request.result());
                }
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            };
        }, TaskView.class);
    }

    /**
     * 任务首次终结的空间处理：释放预留并推进空间档案版本。
     * SUCCESS 原子删除旧镜像、压缩包及预留，当前镜像改为新字节数并切换固件版本；
     * FAILED 只释放包与新镜像预留，旧镜像与当前版本不变。
     */
    private void terminateStaging(RolloutTask task, ReceiptResult result, String toVersion) {
        var reservation = stagingReservationRepository.findByTaskId(task.id());
        if (result == ReceiptResult.SUCCESS) {
            Long newImageBytes = reservation.map(StagingReservation::newImageBytes).orElse(null);
            reservation.ifPresent(r -> stagingReservationRepository.deleteByTaskId(task.id()));
            deviceRepository.completeSuccess(task.deviceId(), toVersion, newImageBytes);
        } else {
            reservation.ifPresent(r -> stagingReservationRepository.deleteByTaskId(task.id()));
            deviceRepository.bumpSpaceVersion(task.deviceId());
        }
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
                .map(task -> toView(task, order))
                .toList();
        return new TaskListResponse(tasks);
    }

    /**
     * 任务视图：当时峰值取任务快照（不漂移），当前余量按设备实时状态计算。
     */
    private TaskView toView(RolloutTask task, ReleaseOrder order) {
        Device device = deviceService.findDevice(task.deviceId());
        return TaskView.of(task, order, device, deviceService.reservedBytes(task.deviceId()));
    }

    private Device lockDevice(String deviceId) {
        return deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
    }
}
