package com.example.starter.firmware.service;

import com.example.starter.firmware.api.BatchStartRequest;
import com.example.starter.firmware.api.BatchStartResponse;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.FirmwareCertChain;
import com.example.starter.firmware.domain.FirmwareVersion;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReceiptReview;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.CertChainRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReceiptReviewRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 投放任务：设备拉取与回执。与取消、恢复并发时统一先锁发布单行，再操作任务，形成一致提交顺序。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 *
 * 证书相关：新建任务按请求时刻取目标固件唯一当前有效链，校验设备信任集合与兼容矩阵后冻结链ID；
 * 新链生效前/旧链到期后不创建任务；冻结链事后被撤销时，SUCCESS 回执仍受理但转人工复核，
 * 不自动更新设备版本。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final ReceiptReviewRepository reviewRepository;
    private final CertChainRepository certChainRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final FirmwareService firmwareService;
    private final CertService certService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, PauseRecordRepository pauseRecordRepository,
                       ReceiptReviewRepository reviewRepository, CertChainRepository certChainRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       FirmwareService firmwareService, CertService certService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.reviewRepository = reviewRepository;
        this.certChainRepository = certChainRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.firmwareService = firmwareService;
        this.certService = certService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回（含创建时冻结的链版本，事后撤销/轮换不改变快照）；
     * 否则仅当发布单 ACTIVE、型号/版本/分桶匹配、兼容矩阵允许且请求时刻存在当前有效证书链时创建。
     * 设备不在链信任集合或显式不信任返回 422；新链未生效/旧链到期等纯时态原因暂不投放（task 为 null）。
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
                return new PullResponse(view(existing.get(), order));
            }
            if (order.status() != ReleaseStatus.ACTIVE
                    || !device.currentVersion().equals(order.fromVersion())
                    || device.bucketNo() >= order.ratio()) {
                return new PullResponse(null);
            }
            Long frozenChainId = resolveChainForNewTask(order, device);
            if (frozenChainId == null && isCertEnabled(order)) {
                // 启用证书但请求时刻无有效当前链：新链未生效或旧链已到期，暂不创建任务
                return new PullResponse(null);
            }
            long taskId;
            try {
                taskId = taskRepository.insert(order.id(), deviceId, frozenChainId);
            } catch (DuplicateKeyException e) {
                RolloutTask task = taskRepository.findByReleaseAndDevice(order.id(), deviceId)
                        .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                return new PullResponse(view(task, order));
            }
            RolloutTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
            return new PullResponse(view(task, order));
        }, PullResponse.class);
    }

    /**
     * 回执：首次回执终结任务并计入完成时所在监控轮次；SUCCESS 默认更新设备当前版本，
     * 但任务冻结链已被撤销时不更新版本，转 CERTIFICATE_REVOKED_REVIEW 待人工确认/拒绝。
     * 同结果重复成功（不重复计数、不重复更新），改结果 409；已取消任务的后到回执 409。
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
                    boolean withheldForReview = false;
                    if (request.result() == ReceiptResult.SUCCESS) {
                        withheldForReview = chainWasRevoked(task.frozenChainId());
                        if (!withheldForReview) {
                            deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
                        } else {
                            reviewRepository.insertPending(taskId, task.frozenChainId());
                        }
                    }
                    ReleaseOrder updated = releaseRepository.findById(snapshot.releaseId()).orElseThrow();
                    pauseIfThresholdReached(updated, taskId);
                    yield view(taskRepository.findById(taskId).orElseThrow(), updated);
                }
                case SUCCESS, FAILED -> {
                    if (task.firstResult() == request.result()) {
                        yield view(task, lockedOrder);
                    }
                    throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                            "任务已终结为 " + task.firstResult() + "，不能改为 " + request.result());
                }
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            };
        }, TaskView.class);
    }

    /**
     * 样本数达到下限且 FAILED×100 >= 样本数×阈值 时，将仍为 ACTIVE 的发布单原子转 PAUSED。
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
                .map(task -> view(task, order))
                .toList();
        return new TaskListResponse(tasks);
    }

    /**
     * 批量启动：先校验全体设备（存在性、型号/版本/分桶匹配、兼容矩阵、请求时刻当前有效链、最终信任状态），
     * 任一设备不信任或不在链授权集合返回 422，全部任务不创建；全部通过后才冻结同一当前链并创建任务。
     * 已有任务的设备不重复创建，响应中原样返回既有任务。请求设备去重后按首次出现顺序裁决。
     */
    public BatchStartResponse batchStart(long releaseId, BatchStartRequest request) {
        List<String> deviceIds = request.deviceIds().stream().distinct().toList();
        String fingerprint = String.join("|", "task.batchStart", String.valueOf(releaseId),
                String.join(",", deviceIds.stream().sorted().toList()));
        return idempotency.execute(request.requestId(), "task.batchStart", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() != ReleaseStatus.ACTIVE) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE",
                        "发布单状态为 " + order.status() + "，不能批量启动");
            }
            FirmwareVersion firmware = firmwareService.findFirmware(order.model(), order.toVersion());
            boolean certEnabled = firmware != null && isCertEnabled(order);
            FirmwareCertChain currentChain = null;
            if (certEnabled) {
                currentChain = certService.lockCurrentForFirmware(firmware.id(), Instant.now(clock))
                        .orElseThrow(() -> ApiException.conflict("NO_CURRENT_CERT_CHAIN",
                                "请求时刻不存在有效签名证书链，新链生效前/旧链到期后不可新建任务"));
            }
            // 第一阶段：纯校验，任何一个设备不通过则整体失败，不留下任何任务
            for (String deviceId : deviceIds) {
                Device device = deviceService.findDevice(deviceId);
                if (!device.model().equals(order.model())
                        || !device.currentVersion().equals(order.fromVersion())
                        || device.bucketNo() >= order.ratio()) {
                    throw ApiException.unprocessable("DEVICE_NOT_ELIGIBLE",
                            "设备不满足本次投放条件: " + deviceId);
                }
                firmwareService.assertCompatible(order.model(), device.currentVersion(), order.toVersion());
                if (certEnabled) {
                    certService.assertDeviceAccepted(currentChain, deviceId);
                }
            }
            // 第二阶段：全体通过后创建（已存在任务跳过），失败由唯一约束兜底后回读
            final FirmwareCertChain chainToFreeze = currentChain;
            List<TaskView> views = new java.util.ArrayList<>();
            for (String deviceId : deviceIds) {
                RolloutTask task = taskRepository.findByReleaseAndDevice(releaseId, deviceId)
                        .orElseGet(() -> {
                            try {
                                long id = taskRepository.insert(releaseId, deviceId,
                                        chainToFreeze == null ? null : chainToFreeze.id());
                                return taskRepository.findById(id).orElseThrow();
                            } catch (DuplicateKeyException e) {
                                return taskRepository.findByReleaseAndDevice(releaseId, deviceId)
                                        .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                            }
                        });
                views.add(view(task, order));
            }
            return new BatchStartResponse(views);
        }, BatchStartResponse.class);
    }

    /**
     * 新建任务时按请求时刻解析目标固件当前有效链并校验设备信任集合；
     * 返回需冻结到任务上的链ID。固件未登记/未配置任何链时返回 null（未启用证书的历史流程）。
     * 调用方已持有发布单行锁。
     */
    private Long resolveChainForNewTask(ReleaseOrder order, Device device) {
        firmwareService.assertCompatible(order.model(), device.currentVersion(), order.toVersion());
        FirmwareVersion firmware = firmwareService.findFirmware(order.model(), order.toVersion());
        if (firmware == null) {
            return null;
        }
        Optional<FirmwareCertChain> current =
                certService.lockCurrentForFirmware(firmware.id(), Instant.now(clock));
        if (current.isEmpty()) {
            return null;
        }
        FirmwareCertChain chain = current.get();
        certService.assertDeviceAccepted(chain, device.deviceId());
        return chain.id();
    }

    private boolean isCertEnabled(ReleaseOrder order) {
        FirmwareVersion firmware = firmwareService.findFirmware(order.model(), order.toVersion());
        return firmware != null && !certChainRepository.findByFirmware(firmware.id()).isEmpty();
    }

    private boolean chainWasRevoked(Long frozenChainId) {
        if (frozenChainId == null) {
            return false;
        }
        return certService.isChainRevokedForUpdate(frozenChainId);
    }

    /**
     * 组装任务视图：补查冻结链版本与回执复核状态。复核状态取值
     * CERTIFICATE_REVOKED_REVIEW（待人工确认）/ CONFIRMED / REJECTED，无复核单为 null。
     */
    public TaskView view(RolloutTask task, ReleaseOrder order) {
        Integer chainVersion = null;
        if (task.frozenChainId() != null) {
            chainVersion = certChainRepository.findById(task.frozenChainId())
                    .map(FirmwareCertChain::chainVersion).orElse(null);
        }
        String reviewStatus = reviewRepository.findByTask(task.id())
                .map(this::reviewStatusCode)
                .orElse(null);
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.frozenChainId(), chainVersion, reviewStatus);
    }

    private String reviewStatusCode(ReceiptReview review) {
        return switch (review.status()) {
            case PENDING_REVIEW -> "CERTIFICATE_REVOKED_REVIEW";
            case CONFIRMED -> "CONFIRMED";
            case REJECTED -> "REJECTED";
        };
    }
}
