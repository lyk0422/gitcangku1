package com.example.starter.firmware.service;

import com.example.starter.firmware.api.QuarantineRecordView;
import com.example.starter.firmware.api.QuarantineRequest;
import com.example.starter.firmware.api.RejectedReceiptView;
import com.example.starter.firmware.api.ReleaseQuarantineRequest;
import com.example.starter.firmware.api.TaskCancelReasonView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.DeviceStatus;
import com.example.starter.firmware.domain.QuarantineAction;
import com.example.starter.firmware.domain.QuarantineRecord;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.QuarantineRecordRepository;
import com.example.starter.firmware.repo.RejectedReceiptRepository;
import com.example.starter.firmware.repo.TaskCancelReasonRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 设备异常隔离与解除。
 * 隔离：设备行锁内校验版本与状态，同事务回查全部进行中任务——PENDING 转 CANCELLED 并写不可变
 * 取消原因，STARTED 保持进行中；任一任务状态变化失败整次回滚。
 * 解除：须由与最近隔离提交人不同的运维确认，且设备无进行中任务；解除后历史取消与被拒回执不可改写。
 * 与拉取、开始、回执、发布启动并发时统一先锁设备行，按事务提交顺序裁决。
 */
@Service
public class QuarantineService {

    private final DeviceRepository deviceRepository;
    private final TaskRepository taskRepository;
    private final QuarantineRecordRepository quarantineRecordRepository;
    private final TaskCancelReasonRepository taskCancelReasonRepository;
    private final RejectedReceiptRepository rejectedReceiptRepository;
    private final DeviceService deviceService;
    private final IdempotencyService idempotency;

    public QuarantineService(DeviceRepository deviceRepository, TaskRepository taskRepository,
                             QuarantineRecordRepository quarantineRecordRepository,
                             TaskCancelReasonRepository taskCancelReasonRepository,
                             RejectedReceiptRepository rejectedReceiptRepository,
                             DeviceService deviceService, IdempotencyService idempotency) {
        this.deviceRepository = deviceRepository;
        this.taskRepository = taskRepository;
        this.quarantineRecordRepository = quarantineRecordRepository;
        this.taskCancelReasonRepository = taskCancelReasonRepository;
        this.rejectedReceiptRepository = rejectedReceiptRepository;
        this.deviceService = deviceService;
        this.idempotency = idempotency;
    }

    /**
     * 提交隔离。isolationKey 指纹含操作者、设备版本、操作、原因；同键成功重放首次完整结果，失败不占键。
     */
    public QuarantineRecordView quarantine(String deviceId, QuarantineRequest request) {
        String fingerprint = String.join("|", "device.quarantine", deviceId, request.operator(),
                request.expectedVersion(), "QUARANTINE", request.reasonCode());
        return idempotency.execute(request.requestId(), "device.quarantine", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (!device.currentVersion().equals(request.expectedVersion())) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与设备当前版本不一致: " + device.currentVersion());
            }
            if (device.status() == DeviceStatus.QUARANTINED) {
                throw ApiException.conflict("ALREADY_QUARANTINED", "设备已处于隔离状态: " + deviceId);
            }
            deviceRepository.updateStatus(deviceId, DeviceStatus.QUARANTINED);
            quarantineRecordRepository.insert(deviceId, QuarantineAction.QUARANTINE, request.reasonCode(),
                    request.expectedVersion(), request.operator());
            // 同事务回查进行中任务：PENDING 逐条取消并写不可变原因；STARTED 保持进行中
            List<RolloutTask> inProgress = taskRepository.findInProgressByDeviceForUpdate(deviceId);
            for (RolloutTask task : inProgress) {
                if (task.status() != TaskStatus.PENDING) {
                    continue;
                }
                if (taskRepository.cancelTask(task.id()) != 1) {
                    throw new IllegalStateException("任务取消失败，整次回滚: taskId=" + task.id());
                }
                taskCancelReasonRepository.insert(task.id(), task.releaseId(), deviceId,
                        "DEVICE_QUARANTINED",
                        "设备隔离取消未开始任务，隔离原因: " + request.reasonCode(), request.operator());
            }
            return QuarantineRecordView.of(latestRecord(deviceId));
        }, QuarantineRecordView.class);
    }

    /**
     * 解除隔离：不同运维确认 + 无进行中任务，否则 409。解除后仅允许后续拉取。
     */
    public QuarantineRecordView release(String deviceId, ReleaseQuarantineRequest request) {
        String fingerprint = String.join("|", "device.releaseQuarantine", deviceId, request.operator(),
                request.expectedVersion(), "RELEASE", request.reasonCode());
        return idempotency.execute(request.requestId(), "device.releaseQuarantine", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (!device.currentVersion().equals(request.expectedVersion())) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与设备当前版本不一致: " + device.currentVersion());
            }
            if (device.status() != DeviceStatus.QUARANTINED) {
                throw ApiException.conflict("NOT_QUARANTINED", "设备未处于隔离状态: " + deviceId);
            }
            QuarantineRecord latest = quarantineRecordRepository.findLatestQuarantine(deviceId)
                    .orElseThrow(() -> new IllegalStateException("隔离状态设备缺少隔离记录: " + deviceId));
            if (latest.operator().equals(request.operator())) {
                throw ApiException.conflict("SAME_OPERATOR",
                        "解除隔离须由不同运维确认，隔离提交人: " + latest.operator());
            }
            if (taskRepository.countInProgressByDevice(deviceId) > 0) {
                throw ApiException.conflict("TASKS_IN_PROGRESS", "设备存在进行中任务，不能解除隔离: " + deviceId);
            }
            deviceRepository.updateStatus(deviceId, DeviceStatus.ACTIVE);
            quarantineRecordRepository.insert(deviceId, QuarantineAction.RELEASE, request.reasonCode(),
                    request.expectedVersion(), request.operator());
            return QuarantineRecordView.of(latestRecord(deviceId));
        }, QuarantineRecordView.class);
    }

    /**
     * 设备隔离历史（只读）。
     */
    public List<QuarantineRecordView> history(String deviceId) {
        deviceService.findDevice(deviceId);
        return quarantineRecordRepository.findByDevice(deviceId).stream()
                .map(QuarantineRecordView::of).toList();
    }

    /**
     * 设备任务取消原因（只读）。
     */
    public List<TaskCancelReasonView> cancelReasons(String deviceId) {
        deviceService.findDevice(deviceId);
        return taskCancelReasonRepository.findByDevice(deviceId).stream()
                .map(TaskCancelReasonView::of).toList();
    }

    /**
     * 单任务取消原因（只读）；任务或原因不存在均 404。
     */
    public TaskCancelReasonView cancelReasonOfTask(long taskId) {
        taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        return taskCancelReasonRepository.findByTask(taskId)
                .map(TaskCancelReasonView::of)
                .orElseThrow(() -> ApiException.notFound("CANCEL_REASON_NOT_FOUND",
                        "任务无取消原因: " + taskId));
    }

    /**
     * 设备被拒回执（只读）；解除隔离后历史记录仍不可改写。
     */
    public List<RejectedReceiptView> rejectedReceipts(String deviceId) {
        deviceService.findDevice(deviceId);
        return rejectedReceiptRepository.findByDevice(deviceId).stream()
                .map(RejectedReceiptView::of).toList();
    }

    private QuarantineRecord latestRecord(String deviceId) {
        List<QuarantineRecord> records = quarantineRecordRepository.findByDevice(deviceId);
        return records.get(records.size() - 1);
    }
}
