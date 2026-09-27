package com.example.starter.firmware.service;

import com.example.starter.firmware.api.BatchStartRequest;
import com.example.starter.firmware.api.BatchStartResponse;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskReviewRequest;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.CertChain;
import com.example.starter.firmware.domain.CertChainStatus;
import com.example.starter.firmware.domain.CertReviewStatus;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 投放任务：设备拉取、批量启动与回执。与取消、恢复并发时统一先锁发布单行，再操作任务，形成一致提交顺序。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 *
 * 证书：新建任务前由 {@link CertificateService} 按请求时刻校验兼容矩阵、当前有效链与设备信任集合，
 * 任一不过抛 422；任务创建时固化证书链版本与指纹。回执时若固化链已撤销，任务终结但进入
 * CERTIFICATE_REVOKED_REVIEW 人工复核，SUCCESS 也不自动更新设备版本，须人工确认后才更新。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final CertificateService certificateService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, PauseRecordRepository pauseRecordRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       CertificateService certificateService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.certificateService = certificateService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     * PAUSED 时不创建新任务，已有任务仍可查看与回执。
     * 目标固件纳管证书时，还要通过兼容矩阵、当前有效链与设备信任集合校验（不过返回 422），
     * 并把当前链版本与指纹固化到任务上。
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
                return new PullResponse(TaskView.of(existing.get(), order));
            }
            if (order.status() != ReleaseStatus.ACTIVE
                    || !device.currentVersion().equals(order.fromVersion())
                    || device.bucketNo() >= order.ratio()) {
                return new PullResponse(null);
            }
            CertChain chain = certificateService.resolvePullChain(device, order);
            long taskId;
            try {
                taskId = taskRepository.insert(order.id(), deviceId,
                        chain == null ? null : chain.chainVersion(),
                        chain == null ? null : chain.fingerprint());
            } catch (DuplicateKeyException e) {
                RolloutTask task = taskRepository.findByReleaseAndDevice(order.id(), deviceId)
                        .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                return new PullResponse(TaskView.of(task, order));
            }
            RolloutTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
            return new PullResponse(TaskView.of(task, order));
        }, PullResponse.class);
    }

    /**
     * 批量启动：先对全体设备（去重后、且尚无任务的）做最终校验——登记存在、型号/当前版本匹配、
     * 分桶命中、发布单 ACTIVE、兼容矩阵允许、当前有效链存在且设备信任——任一不过抛 422，
     * 已创建的任务随事务回滚，不留半成品。已有任务的设备直接返回既有任务。
     */
    public BatchStartResponse batchStart(long releaseId, BatchStartRequest request) {
        List<String> deviceIds = normalizeDevices(request.deviceIds());
        String fingerprint = String.join("|", "task.batchStart", String.valueOf(releaseId),
                String.join(",", deviceIds), request.operator());
        return idempotency.execute(request.requestId(), "task.batchStart", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() != ReleaseStatus.ACTIVE) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE",
                        "发布单状态为 " + order.status() + "，不能批量启动");
            }
            List<Device> devices = new ArrayList<>();
            List<RolloutTask> existingTasks = new ArrayList<>();
            for (String deviceId : deviceIds) {
                Device device = deviceService.findDevice(deviceId);
                RolloutTask existing = taskRepository.findByReleaseAndDevice(releaseId, deviceId).orElse(null);
                if (existing != null) {
                    existingTasks.add(existing);
                    continue;
                }
                if (!device.model().equals(order.model())
                        || !device.currentVersion().equals(order.fromVersion())
                        || device.bucketNo() >= order.ratio()) {
                    throw ApiException.unprocessable("DEVICE_NOT_ELIGIBLE",
                            "设备 " + deviceId + " 不满足投放条件（型号/当前版本/分桶）");
                }
                // 任一设备不信任等校验失败：整体 422，全部任务不创建
                certificateService.resolvePullChain(device, order);
                devices.add(device);
            }
            List<TaskView> views = new ArrayList<>(existingTasks.stream()
                    .map(t -> TaskView.of(t, order)).toList());
            for (Device device : devices) {
                CertChain chain = certificateService.resolvePullChain(device, order);
                long taskId;
                try {
                    taskId = taskRepository.insert(releaseId, device.deviceId(),
                            chain == null ? null : chain.chainVersion(),
                            chain == null ? null : chain.fingerprint());
                } catch (DuplicateKeyException e) {
                    RolloutTask task = taskRepository.findByReleaseAndDevice(releaseId, device.deviceId())
                            .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                    views.add(TaskView.of(task, order));
                    continue;
                }
                RolloutTask created = taskRepository.findById(taskId)
                        .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
                views.add(TaskView.of(created, order));
            }
            return new BatchStartResponse(releaseId, views);
        }, BatchStartResponse.class);
    }

    /**
     * 回执：首次回执终结任务并计入完成时所在监控轮次；同结果重复成功（不重复计数），改结果 409；
     * 已取消任务的后到回执 409 且不更新设备版本。
     * 任务固化的证书链已撤销时：任务正常终结、计入轮次统计，但标记 CERTIFICATE_REVOKED_REVIEW，
     * 即使 SUCCESS 也不自动更新设备版本，等待人工确认。
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
                    boolean chainRevoked = task.certChainVersion() != null
                            && certificateService.getSnapshotChain(lockedOrder.model(),
                                    lockedOrder.toVersion(), task.certChainVersion()).status()
                            == CertChainStatus.REVOKED;
                    if (chainRevoked) {
                        String reason = "证书链版本 " + task.certChainVersion() + " 已撤销，回执待人工复核";
                        taskRepository.completeForReview(taskId, request.result(), reason);
                    } else {
                        taskRepository.complete(taskId, request.result());
                    }
                    releaseRepository.incrementRoundStats(snapshot.releaseId(), request.result());
                    if (!chainRevoked && request.result() == ReceiptResult.SUCCESS) {
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
     * 人工复核撤销链回执：
     * CONFIRMED——确认回执有效；首次回执为 SUCCESS 时在此刻才把设备版本更新到目标版本；
     * REJECTED——拒绝，永不更新设备版本。仅待复核任务可裁决，重复裁决 409。
     */
    public TaskView review(long taskId, TaskReviewRequest request) {
        String fingerprint = String.join("|", "task.review", String.valueOf(taskId),
                request.action().name(), request.reason(), request.operator());
        return idempotency.execute(request.requestId(), "task.review", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            if (task.reviewStatus() != CertReviewStatus.CERTIFICATE_REVOKED_REVIEW) {
                throw ApiException.conflict("REVIEW_NOT_PENDING",
                        "任务当前复核状态为 " + task.reviewStatus() + "，无需人工裁决");
            }
            CertReviewStatus decision = switch (request.action()) {
                case CONFIRMED -> CertReviewStatus.CONFIRMED;
                case REJECTED -> CertReviewStatus.REJECTED;
            };
            taskRepository.updateReview(taskId, decision, request.reason(), request.operator(),
                    Instant.now(clock).toString());
            if (decision == CertReviewStatus.CONFIRMED && task.firstResult() == ReceiptResult.SUCCESS) {
                deviceRepository.updateCurrentVersion(task.deviceId(), order.toVersion());
            }
            return TaskView.of(taskRepository.findById(taskId).orElseThrow(), order);
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

    private List<String> normalizeDevices(List<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            throw ApiException.badRequest("EMPTY_DEVICE_SET", "设备集合不能为空");
        }
        List<String> distinct = deviceIds.stream().distinct().toList();
        if (distinct.size() != deviceIds.size()) {
            throw ApiException.badRequest("DUPLICATE_DEVICE_IDS", "设备集合存在重复设备");
        }
        return deviceIds;
    }
}
