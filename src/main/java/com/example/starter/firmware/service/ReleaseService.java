package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ExpandReleaseRequest;
import com.example.starter.firmware.api.IncompatibleListResponse;
import com.example.starter.firmware.api.IncompatibleRecordView;
import com.example.starter.firmware.api.ModelStatsResponse;
import com.example.starter.firmware.api.MonitorView;
import com.example.starter.firmware.api.PauseRecordView;
import com.example.starter.firmware.api.ReleaseHistoryResponse;
import com.example.starter.firmware.api.ReleaseView;
import com.example.starter.firmware.api.ResumeRecordView;
import com.example.starter.firmware.api.ResumeReleaseRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.FirmwareCompat;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.IncompatibleRecordRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.ResumeRecordRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 发布单生命周期：创建（版本从1开始）、扩量（版本校验+只增不减）、失败率自动暂停、
 * 人工恢复（版本加一并开启新监控轮次）、取消（未终结任务转 CANCELLED）。
 */
@Service
public class ReleaseService {

    private final ReleaseRepository releaseRepository;
    private final TaskRepository taskRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final ResumeRecordRepository resumeRecordRepository;
    private final DeviceRepository deviceRepository;
    private final IncompatibleRecordRepository incompatibleRecordRepository;
    private final FirmwareCompatService firmwareCompatService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ReleaseService(ReleaseRepository releaseRepository, TaskRepository taskRepository,
                          PauseRecordRepository pauseRecordRepository,
                          ResumeRecordRepository resumeRecordRepository,
                          DeviceRepository deviceRepository,
                          IncompatibleRecordRepository incompatibleRecordRepository,
                          FirmwareCompatService firmwareCompatService,
                          IdempotencyService idempotency, Clock clock) {
        this.releaseRepository = releaseRepository;
        this.taskRepository = taskRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.resumeRecordRepository = resumeRecordRepository;
        this.deviceRepository = deviceRepository;
        this.incompatibleRecordRepository = incompatibleRecordRepository;
        this.firmwareCompatService = firmwareCompatService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public ReleaseView create(CreateReleaseRequest request) {
        if (request.fromVersion().equals(request.toVersion())) {
            throw ApiException.badRequest("SAME_VERSION", "目标版本必须与来源版本不同");
        }
        int sampleFloor = request.effectiveSampleFloor();
        int threshold = request.effectiveFailureThresholdPercent();
        String fingerprint = String.join("|", "release.create", request.model(), request.fromVersion(),
                request.toVersion(), String.valueOf(request.ratio()), String.valueOf(sampleFloor),
                String.valueOf(threshold));
        return idempotency.execute(request.requestId(), "release.create", fingerprint, () -> {
            precheckCompatibility(request);
            long id;
            try {
                id = releaseRepository.insert(request.model(), request.fromVersion(), request.toVersion(),
                        request.ratio(), sampleFloor, threshold);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ACTIVE_RELEASE_EXISTS", "型号已存在未终结发布单: " + request.model());
            }
            return ReleaseView.of(findOrder(id));
        }, ReleaseView.class);
    }

    /**
     * 发布预检：候选设备（型号匹配、当前版本等于来源版本、分桶落入比例）全部不兼容目标固件时
     * 拒绝启动，返回 422 并给出按硬件型号汇总；部分不兼容不阻断兼容设备，无候选设备不阻断。
     */
    private void precheckCompatibility(CreateReleaseRequest request) {
        FirmwareCompat compat = firmwareCompatService.findCompat(request.toVersion());
        if (compat.allowedModels().isEmpty()) {
            return;
        }
        List<Device> candidates = deviceRepository.findCandidates(request.model(),
                request.fromVersion(), request.ratio());
        if (candidates.isEmpty()) {
            return;
        }
        Map<String, Long> incompatibleByModel = candidates.stream()
                .filter(device -> !compat.allows(device.hardwareModel()))
                .collect(Collectors.groupingBy(Device::hardwareModel, TreeMap::new, Collectors.counting()));
        long incompatibleTotal = incompatibleByModel.values().stream().mapToLong(Long::longValue).sum();
        if (incompatibleTotal == candidates.size()) {
            throw ApiException.unprocessable("ALL_CANDIDATES_INCOMPATIBLE",
                    "全部候选设备与目标固件 " + request.toVersion() + " 不兼容，发布单不得启动",
                    incompatibleByModel);
        }
    }

    public ReleaseView expand(long releaseId, ExpandReleaseRequest request) {
        String fingerprint = String.join("|", "release.expand", String.valueOf(releaseId),
                String.valueOf(request.expectedVersion()), String.valueOf(request.ratio()));
        return idempotency.execute(request.requestId(), "release.expand", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() != ReleaseStatus.ACTIVE) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE",
                        "发布单状态为 " + order.status() + "，不能扩量");
            }
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + order.version());
            }
            if (request.ratio() < order.ratio()) {
                throw ApiException.conflict("RATIO_DECREASE", "投放比例只增不减，当前: " + order.ratio());
            }
            releaseRepository.expand(releaseId, request.expectedVersion(), request.ratio());
            return ReleaseView.of(findOrder(releaseId));
        }, ReleaseView.class);
    }

    /**
     * 人工恢复：仅 PAUSED 可恢复为 ACTIVE；版本加一、开启新监控轮次、本轮统计清零。
     * 与取消并发时按行锁提交顺序决定唯一结果：取消先提交则恢复 409。
     */
    public ReleaseView resume(long releaseId, ResumeReleaseRequest request) {
        String fingerprint = String.join("|", "release.resume", String.valueOf(releaseId),
                String.valueOf(request.expectedVersion()), request.reason());
        return idempotency.execute(request.requestId(), "release.resume", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() == ReleaseStatus.CANCELLED) {
                throw ApiException.conflict("RELEASE_CANCELLED", "发布单已取消，不能恢复");
            }
            if (order.status() != ReleaseStatus.PAUSED) {
                throw ApiException.conflict("RELEASE_NOT_PAUSED",
                        "发布单状态为 " + order.status() + "，仅 PAUSED 可恢复");
            }
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + order.version());
            }
            releaseRepository.resume(releaseId, request.expectedVersion());
            ReleaseOrder resumed = findOrder(releaseId);
            resumeRecordRepository.insert(releaseId, resumed.monitorRound(), request.reason(),
                    Instant.now(clock).toString());
            return ReleaseView.of(resumed);
        }, ReleaseView.class);
    }

    public ReleaseView cancel(long releaseId, String requestId) {
        String fingerprint = String.join("|", "release.cancel", String.valueOf(releaseId));
        return idempotency.execute(requestId, "release.cancel", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() == ReleaseStatus.ACTIVE || order.status() == ReleaseStatus.PAUSED) {
                releaseRepository.cancel(releaseId);
                taskRepository.cancelPendingByRelease(releaseId);
            }
            return ReleaseView.of(findOrder(releaseId));
        }, ReleaseView.class);
    }

    /**
     * 当前监控轮次统计（只读，不触发状态变化）。
     */
    public MonitorView monitor(long releaseId) {
        return MonitorView.of(findOrder(releaseId));
    }

    /**
     * 暂停/恢复历史（只读，不触发状态变化）。
     */
    public ReleaseHistoryResponse history(long releaseId) {
        findOrder(releaseId);
        var pauses = pauseRecordRepository.findByRelease(releaseId).stream()
                .map(PauseRecordView::of).toList();
        var resumes = resumeRecordRepository.findByRelease(releaseId).stream()
                .map(ResumeRecordView::of).toList();
        return new ReleaseHistoryResponse(releaseId, pauses, resumes);
    }

    public ReleaseOrder findOrder(long releaseId) {
        return releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
    }

    /**
     * 设备不兼容拦截记录（只读）。
     */
    public IncompatibleListResponse incompatibleRecords(long releaseId) {
        findOrder(releaseId);
        var records = incompatibleRecordRepository.findByRelease(releaseId).stream()
                .map(IncompatibleRecordView::of).toList();
        return new IncompatibleListResponse(releaseId, records);
    }

    /**
     * 按硬件型号的投放统计（只读）：任务各状态数量与被拦截设备数。
     */
    public ModelStatsResponse modelStats(long releaseId) {
        findOrder(releaseId);
        Map<String, long[]> byModel = new TreeMap<>();
        for (TaskRepository.ModelStatusCount row : taskRepository.countByModelAndStatus(releaseId)) {
            long[] counts = byModel.computeIfAbsent(row.hardwareModel(), key -> new long[5]);
            switch (TaskStatus.valueOf(row.status())) {
                case PENDING -> counts[0] = row.count();
                case SUCCESS -> counts[1] = row.count();
                case FAILED -> counts[2] = row.count();
                case CANCELLED -> counts[3] = row.count();
            }
        }
        for (IncompatibleRecordRepository.ModelCount row : incompatibleRecordRepository.countByModel(releaseId)) {
            byModel.computeIfAbsent(row.hardwareModel(), key -> new long[5])[4] = row.count();
        }
        List<ModelStatsResponse.ModelStats> stats = byModel.entrySet().stream()
                .map(entry -> new ModelStatsResponse.ModelStats(entry.getKey(),
                        entry.getValue()[0], entry.getValue()[1], entry.getValue()[2],
                        entry.getValue()[3], entry.getValue()[4]))
                .toList();
        return new ModelStatsResponse(releaseId, stats);
    }
}
