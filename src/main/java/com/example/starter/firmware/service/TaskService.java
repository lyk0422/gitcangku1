package com.example.starter.firmware.service;

import com.example.starter.firmware.api.BatchStartResponse;
import com.example.starter.firmware.api.ConfirmReviewRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.Rejection;
import com.example.starter.firmware.api.ReviewListResponse;
import com.example.starter.firmware.api.StartReleaseRequest;
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
import com.example.starter.firmware.error.BatchStartRejectedException;
import com.example.starter.firmware.repo.CertChainRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.DeviceTrustRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;

/**
 * 投放任务：设备拉取、批量启动、回执与人工复核确认。
 * 与取消、恢复并发时统一先锁发布单行，再操作任务，形成一致提交顺序。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 * 任务创建时按请求时刻校验证书链与设备信任集合并固化链版本；证书撤销后的回执转人工复核，
 * 不自动更新设备版本，人工确认后才完成版本更新。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final CertChainRepository certChainRepository;
    private final DeviceTrustRepository deviceTrustRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, CertChainRepository certChainRepository,
                       DeviceTrustRepository deviceTrustRepository,
                       PauseRecordRepository pauseRecordRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.certChainRepository = certChainRepository;
        this.deviceTrustRepository = deviceTrustRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回（任务已固化证书链，不受后续轮换/撤销影响）；
     * 否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     * 目标版本已登记证书链时，按请求时刻校验当前链与设备信任集合，失败返回 422 且不创建任务。
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
            CertChain chain = requireCurrentChain(order.toVersion(), deviceId);
            return new PullResponse(TaskView.of(insertTask(order, deviceId, chain), order));
        }, PullResponse.class);
    }

    /**
     * 批量启动发布：先校验全体设备的最终信任状态与固件兼容性，任一设备不通过则整体 422、
     * 不创建任何任务；全部通过后为每台设备创建（或复用已有）任务，任务固化当前证书链。
     */
    public BatchStartResponse startRelease(long releaseId, StartReleaseRequest request) {
        List<String> deviceIds = new ArrayList<>(new LinkedHashSet<>(request.deviceIds()));
        String fingerprint = String.join("|", "release.start", String.valueOf(releaseId),
                String.join(",", new TreeSet<>(deviceIds)));
        return idempotency.execute(request.requestId(), "release.start", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() != ReleaseStatus.ACTIVE) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE",
                        "发布单状态为 " + order.status() + "，不能启动发布");
            }
            CertChain chain = requireCurrentChain(order.toVersion(), null);
            List<Device> accepted = new ArrayList<>();
            List<Rejection> rejections = new ArrayList<>();
            for (String deviceId : deviceIds) {
                var deviceOpt = deviceRepository.findById(deviceId);
                if (deviceOpt.isEmpty()) {
                    rejections.add(new Rejection(deviceId, "DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
                    continue;
                }
                Device device = deviceOpt.get();
                if (!device.model().equals(order.model())) {
                    rejections.add(new Rejection(deviceId, "MODEL_MISMATCH",
                            "设备型号 " + device.model() + " 与发布单型号 " + order.model() + " 不匹配"));
                    continue;
                }
                if (!device.currentVersion().equals(order.fromVersion())) {
                    rejections.add(new Rejection(deviceId, "VERSION_MISMATCH",
                            "设备当前版本 " + device.currentVersion() + " 与来源版本 "
                                    + order.fromVersion() + " 不兼容"));
                    continue;
                }
                if (chain != null && !deviceTrustRepository.exists(deviceId, chain.id())) {
                    rejections.add(new Rejection(deviceId, "DEVICE_NOT_TRUSTED",
                            "设备不信任当前证书链: " + chain.chainVersion()));
                    continue;
                }
                accepted.add(device);
            }
            if (!rejections.isEmpty()) {
                throw new BatchStartRejectedException(rejections);
            }
            List<TaskView> tasks = new ArrayList<>();
            for (Device device : accepted) {
                tasks.add(TaskView.of(insertTask(order, device.deviceId(), chain), order));
            }
            return new BatchStartResponse(order.id(), tasks);
        }, BatchStartResponse.class);
    }

    /**
     * 回执：首次回执终结任务并计入完成时所在监控轮次；证书未撤销时 SUCCESS 直接更新设备版本。
     * 任务固化的证书链已撤销时回执仍受理，但标记 CERTIFICATE_REVOKED_REVIEW 且不更新设备版本，
     * 待人工确认。同结果重复成功（不重复计数），改结果 409；已取消任务的后到回执 409。
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
                    ReviewStatus review = reviewFor(task);
                    taskRepository.complete(taskId, request.result(), review);
                    releaseRepository.incrementRoundStats(snapshot.releaseId(), request.result());
                    if (request.result() == ReceiptResult.SUCCESS && review == null) {
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
     * 人工确认复核：仅 CERTIFICATE_REVOKED_REVIEW 状态可确认；确认后 SUCCESS 任务完成设备版本更新。
     * 已确认任务重复确认（新 requestId）幂等返回当前状态。
     */
    public TaskView confirmReview(long taskId, ConfirmReviewRequest request) {
        String fingerprint = String.join("|", "task.confirmReview", String.valueOf(taskId), request.operator());
        return idempotency.execute(request.requestId(), "task.confirmReview", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            if (task.reviewStatus() == null) {
                throw ApiException.conflict("NO_REVIEW_PENDING", "任务无待确认的回执复核");
            }
            if (task.reviewStatus() == ReviewStatus.REVIEW_CONFIRMED) {
                return TaskView.of(task, lockedOrder);
            }
            taskRepository.confirmReview(taskId);
            if (task.firstResult() == ReceiptResult.SUCCESS) {
                deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
            }
            return TaskView.of(taskRepository.findById(taskId).orElseThrow(), lockedOrder);
        }, TaskView.class);
    }

    /**
     * 任务查询：含固化的证书链版本与复核标记。
     */
    public TaskView getTask(long taskId) {
        RolloutTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        return TaskView.of(task, releaseService.findOrder(task.releaseId()));
    }

    /**
     * 回执复核查询：发布单下带复核标记（含已确认）的任务。
     */
    public ReviewListResponse listReviewByRelease(long releaseId) {
        ReleaseOrder order = releaseService.findOrder(releaseId);
        List<TaskView> reviews = taskRepository.findReviewsByRelease(releaseId).stream()
                .map(task -> TaskView.of(task, order))
                .toList();
        return new ReviewListResponse(reviews);
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
     * 创建（或复用已有）任务并固化当前证书链；并发下唯一约束冲突时复用已提交任务。
     */
    private RolloutTask insertTask(ReleaseOrder order, String deviceId, CertChain chain) {
        Long chainId = chain == null ? null : chain.id();
        String chainVersion = chain == null ? null : chain.chainVersion();
        var existing = taskRepository.findByReleaseAndDevice(order.id(), deviceId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            long taskId = taskRepository.insert(order.id(), deviceId, chainId, chainVersion);
            return taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
        } catch (DuplicateKeyException e) {
            return taskRepository.findByReleaseAndDevice(order.id(), deviceId)
                    .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
        }
    }

    /**
     * 解析请求时刻的当前有效证书链。目标版本未登记任何链时返回 null（兼容无证书投放）；
     * 已登记但当前无有效链时按可区分原因 422；deviceId 非空时同时校验设备信任集合。
     */
    private CertChain requireCurrentChain(String firmwareVersion, String deviceId) {
        List<CertChain> chains = certChainRepository.findByFirmwareVersion(firmwareVersion);
        if (chains.isEmpty()) {
            return null;
        }
        Instant now = Instant.now(clock);
        var covering = chains.stream().filter(chain -> chain.covers(now)).findFirst();
        if (covering.isEmpty()) {
            boolean anyFuture = chains.stream()
                    .anyMatch(chain -> Instant.parse(chain.validFromUtc()).isAfter(now));
            if (anyFuture) {
                throw ApiException.unprocessableEntity("CERT_CHAIN_NOT_YET_EFFECTIVE",
                        "新证书链尚未生效，当前时刻无有效链: " + firmwareVersion);
            }
            throw ApiException.unprocessableEntity("CERT_CHAIN_EXPIRED",
                    "证书链已到期，当前时刻无有效链: " + firmwareVersion);
        }
        CertChain chain = covering.get();
        if (chain.revokedAtUtc() != null) {
            throw ApiException.unprocessableEntity("CERT_CHAIN_REVOKED",
                    "当前证书链已撤销: " + chain.chainVersion());
        }
        if (deviceId != null && !deviceTrustRepository.exists(deviceId, chain.id())) {
            throw ApiException.unprocessableEntity("DEVICE_NOT_TRUSTED",
                    "设备不信任当前证书链: " + chain.chainVersion());
        }
        return chain;
    }

    /**
     * 回执复核判定：任务固化的证书链在回执时刻已撤销（按已提交状态裁决）时标记待人工复核。
     */
    private ReviewStatus reviewFor(RolloutTask task) {
        if (task.chainId() == null) {
            return null;
        }
        return certChainRepository.findById(task.chainId())
                .filter(chain -> chain.revokedAtUtc() != null)
                .map(chain -> ReviewStatus.CERTIFICATE_REVOKED_REVIEW)
                .orElse(null);
    }
}
