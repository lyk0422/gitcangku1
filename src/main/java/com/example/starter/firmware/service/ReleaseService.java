package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ExpandReleaseRequest;
import com.example.starter.firmware.api.MonitorView;
import com.example.starter.firmware.api.PauseRecordView;
import com.example.starter.firmware.api.ReleaseHistoryResponse;
import com.example.starter.firmware.api.ReleaseView;
import com.example.starter.firmware.api.ResumeRecordView;
import com.example.starter.firmware.api.ResumeReleaseRequest;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.ResumeRecordRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

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
    private final IdempotencyService idempotency;
    private final FreezeService freezeService;
    private final Clock clock;

    public ReleaseService(ReleaseRepository releaseRepository, TaskRepository taskRepository,
                          PauseRecordRepository pauseRecordRepository,
                          ResumeRecordRepository resumeRecordRepository,
                          IdempotencyService idempotency, FreezeService freezeService, Clock clock) {
        this.releaseRepository = releaseRepository;
        this.taskRepository = taskRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.resumeRecordRepository = resumeRecordRepository;
        this.idempotency = idempotency;
        this.freezeService = freezeService;
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
                String.valueOf(threshold), grantFingerprint(request.emergencyGrant()));
        return idempotency.execute(request.requestId(), "release.create", fingerprint, () -> {
            // 冻结守卫：命中生效窗口的型号无完整双人例外即 422，本事务整体回滚
            freezeService.guardReleaseStart(request.model(), request.emergencyGrant(), request.requestId());
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
     * 批量启动发布：先按最终冻结范围整体预校验（型号版本冲突、冻结冲突、紧急例外不全），
     * 任一不合法则 422/409 且全部不写入；全部通过后在同一事务插入并返回。
     */
    public com.example.starter.firmware.api.BatchReleaseStartResponse createBatch(
            com.example.starter.firmware.api.BatchReleaseStartRequest request) {
        var items = request.items();
        // 业务预校验（事务外只读）：来源/目标版本不同
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            if (item.fromVersion().equals(item.toVersion())) {
                throw ApiException.badRequest("SAME_VERSION",
                        "目标版本必须与来源版本不同（批量第" + i + "项）");
            }
        }
        String fingerprint = "release.batch.start|" + items.size() + "|"
                + items.stream().map(it -> String.join("~", it.model(), it.fromVersion(), it.toVersion(),
                        String.valueOf(it.ratio()), String.valueOf(it.effectiveSampleFloor()),
                        String.valueOf(it.effectiveFailureThresholdPercent())))
                .reduce("", (a, b) -> a + ";" + b)
                + "|" + grantFingerprint(request.emergencyGrant());
        return idempotency.execute(request.requestId(), "release.batch.start", fingerprint, () -> {
            // 冻结整体预校验：任一型号命中且例外不全即抛 422，事务回滚、全部不写入
            java.util.List<Long> hitFreezeIds = freezeService.guardBatchReleaseStart(
                    items.stream().map(com.example.starter.firmware.api.BatchReleaseStartRequest.Item::model)
                            .toList(),
                    request.emergencyGrant(), request.requestId());
            java.util.List<ReleaseView> views = new java.util.ArrayList<>();
            for (var item : items) {
                long id;
                try {
                    id = releaseRepository.insert(item.model(), item.fromVersion(), item.toVersion(),
                            item.ratio(), item.effectiveSampleFloor(), item.effectiveFailureThresholdPercent());
                } catch (DuplicateKeyException e) {
                    throw ApiException.conflict("ACTIVE_RELEASE_EXISTS",
                            "型号已存在未终结发布单: " + item.model());
                }
                views.add(ReleaseView.of(findOrder(id)));
            }
            return new com.example.starter.firmware.api.BatchReleaseStartResponse(views, hitFreezeIds);
        }, com.example.starter.firmware.api.BatchReleaseStartResponse.class);
    }

    public ReleaseView expand(long releaseId, ExpandReleaseRequest request) {        String fingerprint = String.join("|", "release.expand", String.valueOf(releaseId),
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
            // 统一锁序：先锁 ACTIVE 冻结令并扫荡，冻结先提交则命中任务已 RELEASE_FROZEN，取消只改 PENDING
            freezeService.lockAndSweep();
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
     * freezeKey 指纹须含紧急例外事件号与两名确认人：同键异参（含例外凭据不同）返回 409。
     */
    private static String grantFingerprint(com.example.starter.firmware.api.EmergencyGrant grant) {
        if (grant == null) {
            return "";
        }
        return String.join("~", grant.eventNo(), grant.confirmer1(), grant.confirmer2());
    }
}
