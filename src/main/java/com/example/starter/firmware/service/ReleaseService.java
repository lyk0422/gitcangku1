package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ExpandReleaseRequest;
import com.example.starter.firmware.api.FragmentView;
import com.example.starter.firmware.api.MonitorView;
import com.example.starter.firmware.api.PauseRecordView;
import com.example.starter.firmware.api.ReleaseHistoryResponse;
import com.example.starter.firmware.api.ReleaseView;
import com.example.starter.firmware.api.ResumeRecordView;
import com.example.starter.firmware.api.ResumeReleaseRequest;
import com.example.starter.firmware.domain.ReleaseFragment;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.FragmentRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.ResumeRecordRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 发布单生命周期：创建（版本从1开始，同时登记规范排序的分片摘要与完整包摘要）、
 * 扩量（版本校验+只增不减）、失败率自动暂停、人工恢复（版本加一并开启新监控轮次）、
 * 撤回（未安装任务转 CANCELLED，安装终态与历史证据保留）。
 * 分片摘要一经创建不可修改；发布单被任务拉取后，扩量/恢复只改投放与轮次字段，绝不触碰摘要。
 */
@Service
public class ReleaseService {

    private final ReleaseRepository releaseRepository;
    private final FragmentRepository fragmentRepository;
    private final TaskRepository taskRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final ResumeRecordRepository resumeRecordRepository;
    private final com.example.starter.firmware.repo.IntegrityEventRepository integrityEventRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ReleaseService(ReleaseRepository releaseRepository, FragmentRepository fragmentRepository,
                          TaskRepository taskRepository, PauseRecordRepository pauseRecordRepository,
                          ResumeRecordRepository resumeRecordRepository,
                          com.example.starter.firmware.repo.IntegrityEventRepository integrityEventRepository,
                          IdempotencyService idempotency, Clock clock) {
        this.releaseRepository = releaseRepository;
        this.fragmentRepository = fragmentRepository;
        this.taskRepository = taskRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.resumeRecordRepository = resumeRecordRepository;
        this.integrityEventRepository = integrityEventRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public ReleaseView create(CreateReleaseRequest request) {
        if (request.fromVersion().equals(request.toVersion())) {
            throw ApiException.badRequest("SAME_VERSION", "目标版本必须与来源版本不同");
        }
        int sampleFloor = request.effectiveSampleFloor();
        int threshold = request.effectiveFailureThresholdPercent();
        // 先在事务外完成分片规范校验（重复序号/缺口/聚合不匹配 -> 422），失败不占 requestId。
        List<ReleaseFragment> normalized = DigestSupport.normalizeInputs(request.fragments());
        DigestSupport.verifyPackageDigest(normalized, request.packageDigest());
        String fragmentDigests = normalized.stream().map(ReleaseFragment::digest)
                .reduce((a, b) -> a + "|" + b).orElseThrow();
        String fingerprint = String.join("|", "release.create", request.model(), request.fromVersion(),
                request.toVersion(), String.valueOf(request.ratio()), String.valueOf(sampleFloor),
                String.valueOf(threshold), String.valueOf(normalized.size()), request.packageDigest(),
                fragmentDigests);
        return idempotency.execute(request.requestId(), "release.create", fingerprint, () -> {
            long id;
            try {
                id = releaseRepository.insert(request.model(), request.fromVersion(), request.toVersion(),
                        request.ratio(), sampleFloor, threshold,
                        request.packageDigest(), normalized.size());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ACTIVE_RELEASE_EXISTS", "型号已存在未终结发布单: " + request.model());
            }
            fragmentRepository.insertBatch(id, normalized.stream()
                    .map(fragment -> new ReleaseFragment(id, fragment.seqNo(), fragment.digest()))
                    .toList());
            return buildView(findOrder(id));
        }, ReleaseView.class);
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
            return buildView(findOrder(releaseId));
        }, ReleaseView.class);
    }

    /**
     * 人工恢复：仅 PAUSED 可恢复为 ACTIVE；版本加一、开启新监控轮次、本轮统计清零。
     * 与撤回并发时按行锁提交顺序决定唯一结果：撤回先提交则恢复 409。
     */
    public ReleaseView resume(long releaseId, ResumeReleaseRequest request) {
        String fingerprint = String.join("|", "release.resume", String.valueOf(releaseId),
                String.valueOf(request.expectedVersion()), request.reason());
        return idempotency.execute(request.requestId(), "release.resume", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() == ReleaseStatus.CANCELLED) {
                throw ApiException.conflict("RELEASE_CANCELLED", "发布单已撤回，不能恢复");
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
                    UtcTimes.now(clock));
            return buildView(resumed);
        }, ReleaseView.class);
    }

    /**
     * 撤回发布单：未安装任务（PENDING/INSTALLABLE/INTEGRITY_FAILED）全部转 CANCELLED；
     * 安装终态 SUCCESS/FAILED、分片证据与历史记录保留。重复撤回幂等返回当前状态。
     */
    public ReleaseView cancel(long releaseId, String requestId) {
        String fingerprint = String.join("|", "release.cancel", String.valueOf(releaseId));
        return idempotency.execute(requestId, "release.cancel", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() == ReleaseStatus.ACTIVE || order.status() == ReleaseStatus.PAUSED) {
                releaseRepository.cancel(releaseId);
                taskRepository.cancelUnfinishedByRelease(releaseId);
            }
            return buildView(findOrder(releaseId));
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
     * 发布单完整性事件诊断（只读）：时间区间 UTC、左闭右开 [fromUtc, toUtc)，任一侧可空表示不限。
     * 查询不改变任何状态。
     */
    public com.example.starter.firmware.api.IntegrityEventsResponse integrityEvents(
            long releaseId, String fromUtc, String toUtc) {
        findOrder(releaseId);
        java.time.Instant from = parseInstant(fromUtc, "fromUtc");
        java.time.Instant to = parseInstant(toUtc, "toUtc");
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.badRequest("INVALID_RANGE", "时间区间必须满足 fromUtc < toUtc（左闭右开）");
        }
        String fromNormalized = from == null ? null : from.toString();
        String toNormalized = to == null ? null : to.toString();
        var events = integrityEventRepository.findByReleaseAndRange(releaseId, fromNormalized, toNormalized)
                .stream().map(com.example.starter.firmware.api.TaskDiagnosticsResponse.EventView::of).toList();
        return new com.example.starter.firmware.api.IntegrityEventsResponse(releaseId, fromNormalized,
                toNormalized, events.size(), events);
    }

    private static java.time.Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_TIME", field + " 必须为UTC ISO-8601时刻，如2026-09-28T07:00:00Z");
        }
    }

    /**
     * 组装含登记分片（按序号升序）的发布单视图。
     */
    public ReleaseView buildView(ReleaseOrder order) {
        List<FragmentView> fragments = fragmentRepository.findByRelease(order.id()).stream()
                .map(FragmentView::of).toList();
        return ReleaseView.of(order, fragments);
    }
}
