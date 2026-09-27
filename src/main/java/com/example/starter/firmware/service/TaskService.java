package com.example.starter.firmware.service;

import com.example.starter.firmware.api.BatchStartRequest;
import com.example.starter.firmware.api.BatchStartResponse;
import com.example.starter.firmware.api.ConfirmTaskRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.CertChain;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.ReviewStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.error.CertRejectionException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 投放任务：设备拉取、批量启动、回执与人工确认。与取消、恢复并发时统一先锁发布单行，再操作任务，
 * 形成一致提交顺序。回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 * 任务创建时固化当前有效证书链版本；固化链被撤销后回执标记 CERTIFICATE_REVOKED_REVIEW 且不自动更新
 * 设备版本，人工确认后才完成版本更新。证书域失败在事务回滚后补写拒绝原因审计日志。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final CertService certService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, PauseRecordRepository pauseRecordRepository,
                       DeviceService deviceService, ReleaseService releaseService, CertService certService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.certService = certService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     * PAUSED 时不创建新任务，已有任务仍可查看与回执。目标固件已登记证书链时，新任务必须使用
     * 请求时刻的当前有效链且设备信任该链，否则 422 且不创建任务。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = String.join("|", "task.pull", deviceId);
        try {
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
                    return new PullResponse(TaskView.of(existing.get(), order));
                }
                if (order.status() != ReleaseStatus.ACTIVE
                        || !device.currentVersion().equals(order.fromVersion())
                        || device.bucketNo() >= order.ratio()) {
                    return new PullResponse(null);
                }
                CertChain chain = certService.requireCurrentChain(order.toVersion(), deviceId, "PULL");
                String chainVersion = chain == null ? null : chain.chainVersion();
                long taskId;
                try {
                    taskId = taskRepository.insert(order.id(), deviceId, chainVersion);
                } catch (DuplicateKeyException e) {
                    RolloutTask task = taskRepository.findByReleaseAndDevice(order.id(), deviceId)
                            .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                    return new PullResponse(TaskView.of(task, order));
                }
                RolloutTask task = taskRepository.findById(taskId)
                        .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
                return new PullResponse(TaskView.of(task, order));
            }, PullResponse.class);
        } catch (CertRejectionException e) {
            certService.recordRejection(e);
            throw e;
        }
    }

    /**
     * 批量启动：先校验全体设备的兼容性与最终信任状态，任一不通过则 422 且全部任务不创建；
     * 全部通过后为每台设备创建任务（已存在则复用），任务固化当前有效证书链版本。
     * 批量启动由操作者显式指定设备集合，不适用灰度分桶过滤。
     */
    public BatchStartResponse start(long releaseId, BatchStartRequest request) {
        List<String> deviceIds = List.copyOf(new TreeSet<>(request.deviceIds()));
        String fingerprint = String.join("|", "release.start", String.valueOf(releaseId),
                String.join(",", deviceIds));
        try {
            return idempotency.execute(request.requestId(), "release.start", fingerprint, () -> {
                ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                        .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND",
                                "发布单不存在: " + releaseId));
                if (order.status() != ReleaseStatus.ACTIVE) {
                    throw ApiException.conflict("RELEASE_NOT_ACTIVE",
                            "发布单状态为 " + order.status() + "，不能批量启动");
                }
                // 阶段一：校验全体设备兼容性与最终信任状态，任一失败即整体拒绝
                List<Device> devices = new ArrayList<>();
                for (String deviceId : deviceIds) {
                    Device device = deviceRepository.findById(deviceId)
                            .orElseThrow(() -> CertRejectionException.unprocessable("DEVICE_NOT_FOUND",
                                    "设备不存在: " + deviceId, "START", order.toVersion(), null, deviceId));
                    if (!device.model().equals(order.model())) {
                        throw CertRejectionException.unprocessable("DEVICE_MODEL_MISMATCH",
                                "设备型号 " + device.model() + " 与发布单型号 " + order.model() + " 不匹配",
                                "START", order.toVersion(), null, deviceId);
                    }
                    if (!device.currentVersion().equals(order.fromVersion())) {
                        throw CertRejectionException.unprocessable("VERSION_MISMATCH",
                                "设备当前版本 " + device.currentVersion() + " 与来源版本 "
                                        + order.fromVersion() + " 不匹配",
                                "START", order.toVersion(), null, deviceId);
                    }
                    devices.add(device);
                }
                CertChain chain = null;
                for (Device device : devices) {
                    chain = certService.requireCurrentChain(order.toVersion(), device.deviceId(), "START");
                }
                // 阶段二：全部校验通过后创建任务
                String chainVersion = chain == null ? null : chain.chainVersion();
                List<TaskView> views = new ArrayList<>();
                for (Device device : devices) {
                    var existing = taskRepository.findByReleaseAndDevice(releaseId, device.deviceId());
                    if (existing.isPresent()) {
                        views.add(TaskView.of(existing.get(), order));
                        continue;
                    }
                    long taskId = taskRepository.insert(releaseId, device.deviceId(), chainVersion);
                    views.add(TaskView.of(taskRepository.findById(taskId)
                            .orElseThrow(() -> new IllegalStateException("任务创建后读取失败")), order));
                }
                return new BatchStartResponse(views);
            }, BatchStartResponse.class);
        } catch (CertRejectionException e) {
            certService.recordRejection(e);
            throw e;
        }
    }

    /**
     * 回执：首次回执终结任务并计入完成时所在监控轮次；任务固化的证书链已撤销时仍可回执，
     * 但标记 CERTIFICATE_REVOKED_REVIEW 且不自动更新设备版本，待人工确认。
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
                    ReviewStatus reviewStatus = ReviewStatus.NONE;
                    if (task.certChainVersion() != null
                            && certService.isChainRevoked(lockedOrder.toVersion(), task.certChainVersion())) {
                        reviewStatus = ReviewStatus.CERTIFICATE_REVOKED_REVIEW;
                    }
                    taskRepository.complete(taskId, request.result(), reviewStatus);
                    releaseRepository.incrementRoundStats(snapshot.releaseId(), request.result());
                    if (request.result() == ReceiptResult.SUCCESS && reviewStatus == ReviewStatus.NONE) {
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
     * 人工确认：仅对证书撤销复核中且首次回执为 SUCCESS 的任务有效；
     * 确认后完成设备版本更新并转 CONFIRMED。与撤销、回执按提交顺序裁决。
     */
    public TaskView confirm(long taskId, ConfirmTaskRequest request) {
        String fingerprint = String.join("|", "task.confirm", String.valueOf(taskId), request.operator());
        try {
            return idempotency.execute(request.requestId(), "task.confirm", fingerprint, () -> {
                RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                        .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
                ReleaseOrder order = releaseService.findOrder(task.releaseId());
                if (task.status() != TaskStatus.SUCCESS
                        || task.reviewStatus() != ReviewStatus.CERTIFICATE_REVOKED_REVIEW) {
                    throw CertRejectionException.conflict("NO_PENDING_REVIEW",
                            "任务无待确认的证书撤销复核，状态: " + task.status() + "/" + task.reviewStatus(),
                            "CONFIRM", order.toVersion(), task.certChainVersion(), task.deviceId());
                }
                taskRepository.confirmReview(taskId);
                deviceRepository.updateCurrentVersion(task.deviceId(), order.toVersion());
                return TaskView.of(taskRepository.findById(taskId).orElseThrow(), order);
            }, TaskView.class);
        } catch (CertRejectionException e) {
            certService.recordRejection(e);
            throw e;
        }
    }

    /**
     * 任务详情（只读）：含固化的证书链版本与复核状态。
     */
    public TaskView get(long taskId) {
        RolloutTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        return TaskView.of(task, releaseService.findOrder(task.releaseId()));
    }

    /**
     * 发布单下的回执复核列表（只读）：证书撤销待复核及已确认的任务。
     */
    public TaskListResponse reviews(long releaseId) {
        ReleaseOrder order = releaseService.findOrder(releaseId);
        List<TaskView> tasks = taskRepository.findReviewsByRelease(releaseId).stream()
                .map(task -> TaskView.of(task, order))
                .toList();
        return new TaskListResponse(tasks);
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
