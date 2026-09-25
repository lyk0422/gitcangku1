package com.example.starter.firmware.service;

import com.example.starter.firmware.api.QuarantineHistoryResponse;
import com.example.starter.firmware.api.QuarantineRecordView;
import com.example.starter.firmware.api.QuarantineRequest;
import com.example.starter.firmware.api.RejectedReceiptListResponse;
import com.example.starter.firmware.api.RejectedReceiptView;
import com.example.starter.firmware.api.UnquarantineRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.DeviceStatus;
import com.example.starter.firmware.domain.QuarantineOperation;
import com.example.starter.firmware.domain.QuarantineRecord;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.QuarantineRecordRepository;
import com.example.starter.firmware.repo.RejectedReceiptRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 设备隔离与解除隔离。隔离在同一事务内回查设备全部进行中任务：
 * 未开始（PENDING）任务转 CANCELLED 并写入不可变取消原因，已开始（IN_PROGRESS）任务保持进行中；
 * 任一任务状态变化失败则整次回滚。解除隔离须由不同运维人确认且设备不存在进行中任务。
 * 与拉取、开始、回执、发布启动并发时统一按 设备 → 发布单 → 任务 的行锁顺序裁决。
 */
@Service
public class QuarantineService {

    private final DeviceRepository deviceRepository;
    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final QuarantineRecordRepository quarantineRecordRepository;
    private final RejectedReceiptRepository rejectedReceiptRepository;
    private final DeviceService deviceService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public QuarantineService(DeviceRepository deviceRepository, TaskRepository taskRepository,
                             ReleaseRepository releaseRepository,
                             QuarantineRecordRepository quarantineRecordRepository,
                             RejectedReceiptRepository rejectedReceiptRepository,
                             DeviceService deviceService, IdempotencyService idempotency, Clock clock) {
        this.deviceRepository = deviceRepository;
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.quarantineRecordRepository = quarantineRecordRepository;
        this.rejectedReceiptRepository = rejectedReceiptRepository;
        this.deviceService = deviceService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 隔离设备：状态转 QUARANTINED，并原子回查取消全部未开始任务（写入隔离原因）。
     * 已开始任务保持进行中，其后的回执被拒（422）且不更新设备版本。
     */
    public QuarantineRecordView quarantine(String deviceId, QuarantineRequest request) {
        String fingerprint = String.join("|", "device.quarantine", deviceId, request.operator(),
                request.expectedVersion(), QuarantineOperation.QUARANTINE.name(), request.reasonCode());
        return idempotency.execute(request.requestId(), "device.quarantine", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (device.status() == DeviceStatus.QUARANTINED) {
                throw ApiException.conflict("DEVICE_ALREADY_QUARANTINED", "设备已处于隔离状态: " + deviceId);
            }
            if (!device.currentVersion().equals(request.expectedVersion())) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与设备当前版本不一致: " + device.currentVersion());
            }
            deviceRepository.updateStatus(deviceId, DeviceStatus.QUARANTINED);
            cancelPendingTasksOfDevice(deviceId, request.reasonCode());
            long recordId = quarantineRecordRepository.insert(deviceId, QuarantineOperation.QUARANTINE,
                    request.operator(), request.reasonCode(), device.currentVersion(),
                    Instant.now(clock).toString());
            return QuarantineRecordView.of(quarantineRecordRepository.findById(recordId).orElseThrow());
        }, QuarantineRecordView.class);
    }

    /**
     * 解除隔离：须由与隔离人不同的运维人确认原因已消除，且设备不存在进行中任务，否则 409。
     * 解除后仅允许后续拉取；历史取消任务与被拒回执不可改写。
     */
    public QuarantineRecordView unquarantine(String deviceId, UnquarantineRequest request) {
        String fingerprint = String.join("|", "device.unquarantine", deviceId, request.operator(),
                request.expectedVersion(), QuarantineOperation.UNQUARANTINE.name(), request.reason());
        return idempotency.execute(request.requestId(), "device.unquarantine", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (device.status() != DeviceStatus.QUARANTINED) {
                throw ApiException.conflict("DEVICE_NOT_QUARANTINED", "设备未处于隔离状态: " + deviceId);
            }
            if (!device.currentVersion().equals(request.expectedVersion())) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与设备当前版本不一致: " + device.currentVersion());
            }
            QuarantineRecord latest = quarantineRecordRepository.findLatestByDevice(deviceId)
                    .filter(record -> record.operation() == QuarantineOperation.QUARANTINE)
                    .orElseThrow(() -> new IllegalStateException("隔离状态设备缺少隔离记录: " + deviceId));
            if (latest.operatorName().equals(request.operator())) {
                throw ApiException.conflict("SAME_OPERATOR",
                        "解除隔离须由不同运维人确认原因已消除: " + request.operator());
            }
            if (taskRepository.countActiveByDevice(deviceId) > 0) {
                throw ApiException.conflict("DEVICE_TASKS_IN_PROGRESS",
                        "设备存在进行中任务，不能解除隔离: " + deviceId);
            }
            deviceRepository.updateStatus(deviceId, DeviceStatus.NORMAL);
            long recordId = quarantineRecordRepository.insert(deviceId, QuarantineOperation.UNQUARANTINE,
                    request.operator(), request.reason(), device.currentVersion(),
                    Instant.now(clock).toString());
            return QuarantineRecordView.of(quarantineRecordRepository.findById(recordId).orElseThrow());
        }, QuarantineRecordView.class);
    }

    /**
     * 隔离回查：取消设备全部未开始任务并写入不可变取消原因；已开始任务保持进行中。
     * 先按发布单ID升序锁发布单行，再按任务ID升序锁任务行，与回执/开始形成一致提交顺序；
     * 调用方持有设备行锁，回查期间不会有新任务插入。任一任务状态变化失败抛错整次回滚。
     */
    private void cancelPendingTasksOfDevice(String deviceId, String reasonCode) {
        List<RolloutTask> active = taskRepository.findActiveByDevice(deviceId);
        active.stream().map(RolloutTask::releaseId).distinct().sorted().forEach(releaseId ->
                releaseRepository.findByIdForUpdate(releaseId)
                        .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND",
                                "发布单不存在: " + releaseId)));
        for (RolloutTask snapshot : active) {
            RolloutTask locked = taskRepository.findByIdForUpdate(snapshot.id())
                    .orElseThrow(() -> new IllegalStateException("任务回查期间消失: " + snapshot.id()));
            if (locked.status() == TaskStatus.PENDING
                    && taskRepository.cancelIfPending(locked.id(), reasonCode) != 1) {
                throw new IllegalStateException("任务状态变化失败，整次回滚: " + locked.id());
            }
        }
    }

    /**
     * 设备隔离历史（只读），按操作时间升序。
     */
    public QuarantineHistoryResponse history(String deviceId) {
        deviceService.findDevice(deviceId);
        List<QuarantineRecordView> records = quarantineRecordRepository.findByDevice(deviceId).stream()
                .map(QuarantineRecordView::of).toList();
        return new QuarantineHistoryResponse(deviceId, records);
    }

    /**
     * 设备被拒回执列表（只读），按拒绝时间升序。
     */
    public RejectedReceiptListResponse rejectedReceipts(String deviceId) {
        deviceService.findDevice(deviceId);
        List<RejectedReceiptView> receipts = rejectedReceiptRepository.findByDevice(deviceId).stream()
                .map(RejectedReceiptView::of).toList();
        return new RejectedReceiptListResponse(deviceId, receipts);
    }
}
