package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ExpandReleaseRequest;
import com.example.starter.firmware.api.ModelCompatSummary;
import com.example.starter.firmware.api.ModelRolloutStat;
import com.example.starter.firmware.api.ModelRolloutStatsResponse;
import com.example.starter.firmware.api.MonitorView;
import com.example.starter.firmware.api.PauseRecordView;
import com.example.starter.firmware.api.ReleaseHistoryResponse;
import com.example.starter.firmware.api.ReleaseView;
import com.example.starter.firmware.api.ResumeRecordView;
import com.example.starter.firmware.api.ResumeReleaseRequest;
import com.example.starter.firmware.api.StartReleaseRequest;
import com.example.starter.firmware.domain.CompatMatrix;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.error.PrecheckException;
import com.example.starter.firmware.repo.IncompatibleRecordRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.ResumeRecordRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布单生命周期：创建（DRAFT，版本从1开始）、启动（硬件兼容预检，通过后转 ACTIVE）、
 * 扩量（版本校验+只增不减）、失败率自动暂停、人工恢复（版本加一并开启新监控轮次）、取消（未终结任务转 CANCELLED）。
 */
@Service
public class ReleaseService {

    private final ReleaseRepository releaseRepository;
    private final TaskRepository taskRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final ResumeRecordRepository resumeRecordRepository;
    private final IncompatibleRecordRepository incompatibleRecordRepository;
    private final CompatService compatService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ReleaseService(ReleaseRepository releaseRepository, TaskRepository taskRepository,
                          PauseRecordRepository pauseRecordRepository,
                          ResumeRecordRepository resumeRecordRepository,
                          IncompatibleRecordRepository incompatibleRecordRepository,
                          CompatService compatService,
                          IdempotencyService idempotency, Clock clock) {
        this.releaseRepository = releaseRepository;
        this.taskRepository = taskRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.resumeRecordRepository = resumeRecordRepository;
        this.incompatibleRecordRepository = incompatibleRecordRepository;
        this.compatService = compatService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 创建发布单为 DRAFT：不参与拉取，需通过启动预检后才投放。
     */
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
     * 启动：先锁发布单行再锁目标固件矩阵行（与拉取同序），与矩阵缩窄按提交顺序裁决。
     * 候选设备（产品型号匹配且当前版本等于来源版本）全部不兼容时返回 422 并给出按硬件型号汇总；
     * 部分不兼容不阻断兼容设备；无候选设备时不因空集合而阻断。
     */
    public ReleaseView start(long releaseId, StartReleaseRequest request) {
        String fingerprint = String.join("|", "release.start", String.valueOf(releaseId),
                String.valueOf(request.expectedVersion()));
        return idempotency.execute(request.requestId(), "release.start", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + order.version());
            }
            if (order.status() == ReleaseStatus.ACTIVE || order.status() == ReleaseStatus.PAUSED) {
                throw ApiException.conflict("RELEASE_ALREADY_STARTED",
                        "发布单状态为 " + order.status() + "，无需重复启动");
            }
            if (order.status() == ReleaseStatus.CANCELLED) {
                throw ApiException.conflict("RELEASE_CANCELLED", "发布单已取消，不能启动");
            }
            CompatMatrix matrix = compatService.effectiveMatrixForUpdate(order.toVersion());
            List<ModelCompatSummary> summaries = buildPrecheckSummary(order, matrix);
            int totalCandidates = summaries.stream().mapToInt(ModelCompatSummary::candidates).sum();
            int totalCompatible = summaries.stream().mapToInt(ModelCompatSummary::compatible).sum();
            if (totalCandidates > 0 && totalCompatible == 0) {
                throw new PrecheckException("ALL_CANDIDATES_INCOMPATIBLE",
                        "全部候选设备均不兼容目标固件 " + order.toVersion() + "，发布单不得启动", summaries);
            }
            if (releaseRepository.startIfDraft(releaseId, request.expectedVersion()) != 1) {
                throw ApiException.conflict("VERSION_CONFLICT", "发布单已被并发变更，请重试");
            }
            return ReleaseView.of(findOrder(releaseId));
        }, ReleaseView.class);
    }

    /**
     * 预检汇总：按硬件型号统计候选设备数及其中通过矩阵的数量。调用方持有发布单与矩阵行锁。
     */
    private List<ModelCompatSummary> buildPrecheckSummary(ReleaseOrder order, CompatMatrix matrix) {
        return releaseRepository.summarizeCandidatesByHardwareModel(order.id()).stream()
                .map(raw -> {
                    int compatible = compatService.isCompatible(matrix, raw.hardwareModel())
                            ? raw.candidates() : 0;
                    return new ModelCompatSummary(raw.hardwareModel(), raw.candidates(), compatible,
                            raw.candidates() - compatible);
                })
                .toList();
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
            if (order.status() == ReleaseStatus.ACTIVE || order.status() == ReleaseStatus.PAUSED
                    || order.status() == ReleaseStatus.DRAFT) {
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

    /**
     * 按硬件型号汇总投放情况：已下发任务各状态计数 + 被兼容矩阵拦截、未产生任务的记录数。
     */
    public ModelRolloutStatsResponse modelStats(long releaseId) {
        findOrder(releaseId);
        Map<String, ModelRolloutStat> byModel = new LinkedHashMap<>();
        for (ModelRolloutStat stat : releaseRepository.summarizeTasksByHardwareModel(releaseId)) {
            byModel.put(stat.hardwareModel(), stat);
        }
        for (IncompatibleRecordRepository.IncompatibleCount count :
                incompatibleRecordRepository.countByReleaseGroupByModel(releaseId)) {
            ModelRolloutStat existing = byModel.get(count.hardwareModel());
            if (existing == null) {
                byModel.put(count.hardwareModel(),
                        new ModelRolloutStat(count.hardwareModel(), 0, 0, 0, 0, count.count()));
            } else {
                byModel.put(count.hardwareModel(), new ModelRolloutStat(existing.hardwareModel(),
                        existing.pending(), existing.success(), existing.failed(), existing.cancelled(),
                        count.count()));
            }
        }
        return new ModelRolloutStatsResponse(List.copyOf(byModel.values()));
    }

    public ReleaseOrder findOrder(long releaseId) {
        return releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
    }
}
