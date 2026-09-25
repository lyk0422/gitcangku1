package com.example.starter.observation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 观测质量标记业务服务。
 *
 * <p>标记创建在单事务内完成，复用 request_log 幂等机制（同键同参重放首次结果、异参 409、
 * 失败不占键），并通过 observation_current 行锁与观测合并/删除按事务提交顺序串行裁决。
 *
 * <p>复核由 {@link ReviewTransactionRunner} 承担事务边界：事务内若发现标记绑定版本已不是
 * 观测当前版本则整体回滚（不占幂等键），本服务捕获后在独立事务把标记条件化转 STALE，再返回 410。
 *
 * <p>置信度规则：初始 100；同版本每个不同类别的首个有效 CONFIRMED 扣减 20，最低 0；
 * DISMISSED 不影响；同版本同类别重复 CONFIRMED 不重复扣减，需合并产生新版本后该类别才能再次扣减；
 * 置信度随版本固化在各历史快照中，一旦确定不再被后续版本改写。
 */
@Service
public class QualityFlagService {

    private final ObservationRepository observationRepository;
    private final QualityFlagRepository qualityFlagRepository;
    private final ReviewTransactionRunner reviewTransactionRunner;
    private final FlagIdempotency idempotency;

    public QualityFlagService(ObservationRepository observationRepository,
                              QualityFlagRepository qualityFlagRepository,
                              ReviewTransactionRunner reviewTransactionRunner,
                              FlagIdempotency idempotency) {
        this.observationRepository = observationRepository;
        this.qualityFlagRepository = qualityFlagRepository;
        this.reviewTransactionRunner = reviewTransactionRunner;
        this.idempotency = idempotency;
    }

    /**
     * 写操作结果：HTTP 状态码与响应体。
     */
    public record FlagWriteOutcome(int status, Object body) {
    }

    /**
     * 创建待复核质量标记：附加于观测当前版本，不改变观测内容与版本。
     * flagKey 重复或同观测同类别已存在待复核标记返回 409；观测不存在 404；观测已删除 410。
     */
    @Transactional
    public FlagWriteOutcome createFlag(String observationId, CreateQualityFlagRequest request) {
        String fingerprint = idempotency.fingerprint("FLAG_CREATE", observationId, request.flagKey(),
                request.category().name(), request.description(), request.submittedBy());
        FlagIdempotency.Outcome replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return new FlagWriteOutcome(replayed.status(), replayed.body());
        }
        FlagIdempotency.Outcome concurrent = idempotency.insertPlaceholder(
                request.requestId(), fingerprint, "FLAG_CREATE");
        if (concurrent != null) {
            return new FlagWriteOutcome(concurrent.status(), concurrent.body());
        }

        // 锁观测当前行：与合并/删除/同观测其他标记写操作按事务提交顺序串行裁决
        ObservationSnapshot current = observationRepository.findCurrentForUpdate(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        if (qualityFlagRepository.findByFlagKey(request.flagKey()).isPresent()) {
            throw ApiException.conflict("quality flag already exists: " + request.flagKey(), current.version());
        }
        if (qualityFlagRepository.existsPending(
                observationId, current.version(), request.category())) {
            throw ApiException.conflict(
                    "pending quality flag already exists for category: " + request.category(),
                    current.version());
        }

        QualityFlag flag = new QualityFlag(request.flagKey(), observationId, current.version(),
                request.category(), request.description(), request.submittedBy(), FlagStatus.PENDING,
                null, null, null, null, null);
        try {
            qualityFlagRepository.insert(flag);
        } catch (DuplicateKeyException e) {
            // 唯一约束兜底：并发下同 flagKey 或同观测同类别 PENDING 竞争，后到者 409
            throw ApiException.conflict(
                    "duplicate quality flag key or pending flag of same category: " + request.flagKey(),
                    current.version());
        }
        QualityFlag stored = qualityFlagRepository.findByFlagKey(request.flagKey()).orElseThrow();
        FlagIdempotency.Outcome done = idempotency.complete(request.requestId(),
                HttpStatus.CREATED.value(), QualityFlagResponse.of(stored));
        return new FlagWriteOutcome(done.status(), done.body());
    }

    /**
     * 复核质量标记（非事务编排入口）。版本不一致时主事务回滚，再独立转 STALE 并返回 410。
     */
    public FlagWriteOutcome reviewFlag(String flagKey, ReviewQualityFlagRequest request) {
        try {
            FlagIdempotency.Outcome outcome = reviewTransactionRunner.reviewInTransaction(flagKey, request);
            return new FlagWriteOutcome(outcome.status(), outcome.body());
        } catch (StaleFlagVersionException e) {
            // 主事务已回滚、不占幂等键：独立事务条件化转 STALE，再返回 410
            reviewTransactionRunner.markStaleInNewTransaction(flagKey);
            throw ApiException.gone(
                    "flag is no longer bound to the current observation version: " + flagKey,
                    e.currentVersion());
        }
    }

    /**
     * 查询某观测的全部质量标记（PENDING/CONFIRMED/DISMISSED/STALE），按创建顺序返回。
     */
    @Transactional(readOnly = true)
    public List<QualityFlagResponse> getFlagHistory(String observationId) {
        requireObservationExists(observationId);
        return qualityFlagRepository.findFlagsByObservation(observationId).stream()
                .map(QualityFlagResponse::of)
                .toList();
    }

    /**
     * 查询某观测当前待复核标记清单（仅绑定当前版本、状态 PENDING 的标记）。
     */
    @Transactional(readOnly = true)
    public List<QualityFlagResponse> getPendingFlags(String observationId) {
        requireObservationExists(observationId);
        return qualityFlagRepository.findPendingByObservation(observationId).stream()
                .map(QualityFlagResponse::of)
                .toList();
    }

    /**
     * 查询某观测的置信度轨迹。
     *
     * <p>按版本分段全序归并：版本 1 为 INITIAL(100)；其后每个版本先有 VERSION 点（继承上一版本
     * 最终置信度），版本段内按复核记录写入顺序追加 CONFIRMED/DISMISSED 点。不依赖时间戳相等性，
     * 同事务内连续事件也能确定排序。
     */
    @Transactional(readOnly = true)
    public List<ConfidencePoint> getConfidenceTrail(String observationId) {
        List<VersionStamp> versions = observationRepository.findVersionStamps(observationId);
        if (versions.isEmpty()) {
            throw ApiException.notFound("observation not found: " + observationId);
        }
        List<QualityFlagReview> reviews = qualityFlagRepository.findReviewsByObservation(observationId);

        List<ConfidencePoint> points = new ArrayList<>();
        VersionStamp first = versions.get(0);
        points.add(new ConfidencePoint(first.version(), 100, ConfidenceEventType.INITIAL,
                null, null, null, first.createdAt()));

        int runningConfidence = 100;
        for (int i = 0; i < versions.size(); i++) {
            int version = versions.get(i).version();
            if (i > 0) {
                // 新版本生成时刻继承上一版本最终置信度并固化
                points.add(new ConfidencePoint(version, runningConfidence, ConfidenceEventType.VERSION,
                        null, null, null, versions.get(i).createdAt()));
            }
            // findReviewsByObservation 按自增 id 升序，天然给出同版本段内的复核先后顺序
            for (QualityFlagReview review : reviews) {
                if (review.flagVersion() != version) {
                    continue;
                }
                ConfidenceEventType type = review.conclusion() == ReviewConclusion.CONFIRMED
                        ? ConfidenceEventType.CONFIRMED : ConfidenceEventType.DISMISSED;
                points.add(new ConfidencePoint(version, review.confidenceAfter(), type,
                        review.flagKey(), review.category(), review.confidenceDelta(), review.createdAt()));
                runningConfidence = review.confidenceAfter();
            }
        }
        return List.copyOf(points);
    }

    private void requireObservationExists(String observationId) {
        if (observationRepository.findCurrent(observationId).isEmpty()) {
            throw ApiException.notFound("observation not found: " + observationId);
        }
    }
}
