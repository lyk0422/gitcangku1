package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ExpandReleaseRequest;
import com.example.starter.firmware.api.MonitorView;
import com.example.starter.firmware.api.PauseRecordView;
import com.example.starter.firmware.api.ReleaseHistoryResponse;
import com.example.starter.firmware.api.ReleaseTargetView;
import com.example.starter.firmware.api.ReleaseView;
import com.example.starter.firmware.api.ResumeRecordView;
import com.example.starter.firmware.api.ResumeReleaseRequest;
import com.example.starter.firmware.api.TargetDiagnosticsView;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.TargetPolicy;
import com.example.starter.firmware.domain.TargetPolicyRecord;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceTagRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.ResumeRecordRepository;
import com.example.starter.firmware.repo.TagDictionaryRepository;
import com.example.starter.firmware.repo.TargetPolicyRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final DeviceTagRepository deviceTagRepository;
    private final TagDictionaryRepository tagDictionaryRepository;
    private final TargetPolicyRepository targetPolicyRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ReleaseService(ReleaseRepository releaseRepository, TaskRepository taskRepository,
                          PauseRecordRepository pauseRecordRepository,
                          ResumeRecordRepository resumeRecordRepository,
                          DeviceTagRepository deviceTagRepository,
                          TagDictionaryRepository tagDictionaryRepository,
                          TargetPolicyRepository targetPolicyRepository,
                          IdempotencyService idempotency, Clock clock) {
        this.releaseRepository = releaseRepository;
        this.taskRepository = taskRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.resumeRecordRepository = resumeRecordRepository;
        this.deviceTagRepository = deviceTagRepository;
        this.tagDictionaryRepository = tagDictionaryRepository;
        this.targetPolicyRepository = targetPolicyRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public ReleaseView create(CreateReleaseRequest request) {
        if (request.fromVersion().equals(request.toVersion())) {
            throw ApiException.badRequest("SAME_VERSION", "目标版本必须与来源版本不同");
        }
        TargetPolicy policy = request.targetPolicy() == null ? null
                : TargetPolicy.of(request.targetPolicy().includeAll(),
                        request.targetPolicy().excludeAny(), request.targetPolicy().includeAny());
        int sampleFloor = request.effectiveSampleFloor();
        int threshold = request.effectiveFailureThresholdPercent();
        String fingerprint = Fingerprints.of("api", "release.create",
                "model", request.model(), "fromVersion", request.fromVersion(),
                "toVersion", request.toVersion(), "ratio", request.ratio(),
                "sampleFloor", sampleFloor, "failureThresholdPercent", threshold,
                "includeAll", policy == null ? null : policy.includeAll(),
                "excludeAny", policy == null ? null : policy.excludeAny(),
                "includeAny", policy == null ? null : policy.includeAny());
        return idempotency.execute(request.requestId(), "release.create", fingerprint, () -> {
            Long tagDictVersion = null;
            if (policy != null) {
                // 持字典行锁解析快照：与设备登记、标签批量变更按提交顺序串行
                tagDictVersion = tagDictionaryRepository.currentVersionForUpdate();
            }
            long id;
            try {
                id = releaseRepository.insert(request.model(), request.fromVersion(), request.toVersion(),
                        request.ratio(), sampleFloor, threshold);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ACTIVE_RELEASE_EXISTS", "型号已存在未终结发布单: " + request.model());
            }
            if (policy != null) {
                List<String> targets = resolveTargets(request.model(), policy);
                if (targets.isEmpty()) {
                    throw ApiException.unprocessable("TARGET_SNAPSHOT_EMPTY",
                            "目标标签策略解析结果为空，发布单不创建");
                }
                targetPolicyRepository.insertPolicy(id, policy, tagDictVersion,
                        Instant.now(clock).toString(), targets.size());
                targetPolicyRepository.insertSnapshot(id, targets);
            }
            return ReleaseView.of(findOrder(id));
        }, ReleaseView.class);
    }

    /**
     * 按标签表达式解析目标设备快照：型号匹配且标签命中策略，按 deviceId 升序规范排序。
     * 调用方已持有标签字典行锁，读取的是已提交的标签状态。
     */
    private List<String> resolveTargets(String model, TargetPolicy policy) {
        Map<String, Set<String>> tagsByDevice = deviceTagRepository.findByModel(model);
        return tagsByDevice.entrySet().stream()
                .filter(entry -> policy.matches(entry.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
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
     * 目标策略与快照明细（只读）。返回创建时固化的表达式、字典版本、解析时刻与规范排序设备。
     */
    public ReleaseTargetView target(long releaseId) {
        findOrder(releaseId);
        TargetPolicyRecord record = targetPolicyRepository.findPolicy(releaseId)
                .orElseThrow(() -> ApiException.notFound("TARGET_POLICY_NOT_FOUND",
                        "发布单未定义目标标签策略: " + releaseId));
        List<String> devices = targetPolicyRepository.findSnapshotDevices(releaseId);
        return new ReleaseTargetView(releaseId, record.policy().includeAll(),
                record.policy().excludeAny(), record.policy().includeAny(),
                record.tagDictVersion(), record.resolvedAtUtc(), record.targetCount(), devices);
    }

    /**
     * 目标投放诊断（只读）：快照规模、已下发与各状态任务数；notPulledCount 为
     * 快照数与已下发数的差额。
     */
    public TargetDiagnosticsView targetDiagnostics(long releaseId) {
        findOrder(releaseId);
        TargetPolicyRecord record = targetPolicyRepository.findPolicy(releaseId)
                .orElseThrow(() -> ApiException.notFound("TARGET_POLICY_NOT_FOUND",
                        "发布单未定义目标标签策略: " + releaseId));
        Map<String, Long> counts = taskRepository.countByReleaseGroupByStatus(releaseId);
        long pending = counts.getOrDefault("PENDING", 0L);
        long success = counts.getOrDefault("SUCCESS", 0L);
        long failed = counts.getOrDefault("FAILED", 0L);
        long cancelled = counts.getOrDefault("CANCELLED", 0L);
        long pulled = pending + success + failed + cancelled;
        return new TargetDiagnosticsView(releaseId, record.targetCount(), (int) pulled,
                record.targetCount() - (int) pulled, (int) pending, (int) success, (int) failed,
                (int) cancelled);
    }
}
