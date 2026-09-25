package com.example.starter.observation;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 质量标记复核的事务边界组件（独立 Bean 以确保 Spring 代理与 REQUIRES_NEW 生效）。
 *
 * <p>复核主事务内任何业务失败（含标记绑定版本已不是当前版本）都会整体回滚，
 * request_log 占位随之回滚，做到失败不占键；版本变化由外层在独立事务中转 STALE。
 */
@Component
public class ReviewTransactionRunner {

    private static final int CONFIDENCE_DEDUCTION = 20;

    private final ObservationRepository observationRepository;
    private final QualityFlagRepository qualityFlagRepository;
    private final FlagIdempotency idempotency;

    public ReviewTransactionRunner(ObservationRepository observationRepository,
                                   QualityFlagRepository qualityFlagRepository,
                                   FlagIdempotency idempotency) {
        this.observationRepository = observationRepository;
        this.qualityFlagRepository = qualityFlagRepository;
        this.idempotency = idempotency;
    }

    /**
     * 复核主事务：幂等占位 → 按“观测行 → 标记行”统一顺序加锁 → 状态与角色校验并核对版本一致性
     * → 置信度扣减裁决 → 标记终态、不可变复核记录、置信度固化同事务提交。
     */
    @Transactional
    public FlagIdempotency.Outcome reviewInTransaction(String flagKey, ReviewQualityFlagRequest request) {
        String fingerprint = idempotency.fingerprint("FLAG_REVIEW", flagKey,
                request.conclusion().name(), request.reason(), request.reviewedBy());
        FlagIdempotency.Outcome replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        FlagIdempotency.Outcome concurrent = idempotency.insertPlaceholder(
                request.requestId(), fingerprint, "FLAG_REVIEW");
        if (concurrent != null) {
            return concurrent;
        }

        // 先无锁定位标记（拿不到直接 404），随后按“观测行 → 标记行”的统一顺序加锁，
        // 与合并/删除/建标记的加锁顺序保持一致，避免交叉持锁导致死锁。
        QualityFlag located = qualityFlagRepository.findByFlagKey(flagKey)
                .orElseThrow(() -> ApiException.notFound("quality flag not found: " + flagKey));
        ObservationSnapshot current = observationRepository.findCurrentForUpdate(located.observationId())
                .orElseThrow(() -> ApiException.notFound(
                        "observation not found: " + located.observationId()));
        QualityFlag flag = qualityFlagRepository.findByFlagKeyForUpdate(flagKey)
                .orElseThrow(() -> ApiException.notFound("quality flag not found: " + flagKey));
        switch (flag.status()) {
            case CONFIRMED, DISMISSED -> throw ApiException.conflict(
                    "quality flag already reviewed: " + flagKey, flag.version());
            case STALE -> throw ApiException.gone("quality flag is stale: " + flagKey, current.version());
            default -> {
                // PENDING：继续校验
            }
        }
        if (request.reviewedBy().equals(flag.submittedBy())) {
            throw ApiException.forbidden(
                    "reviewer must differ from the submitting role: " + request.reviewedBy());
        }

        // 防御性版本核对：正常路径下产生新版本的合并/删除已同事务把旧 PENDING 标记转 STALE；
        // 若仍观测到不一致（含删除墓碑），不得对已变化版本生效 CONFIRMED，回滚后由外层转 STALE。
        if (current.deleted() || current.version() != flag.version()) {
            throw new StaleFlagVersionException(flagKey, current.version());
        }

        // 同版本同类别多次 CONFIRMED 只扣减一次；DISMISSED 不影响置信度
        boolean deduct = request.conclusion() == ReviewConclusion.CONFIRMED
                && !qualityFlagRepository.existsEffectiveDeduction(
                        flag.observationId(), flag.version(), flag.category());
        int confidenceAfter = current.confidence();
        int delta = 0;
        if (deduct) {
            delta = -CONFIDENCE_DEDUCTION;
            confidenceAfter = Math.max(0, current.confidence() + delta);
        }

        FlagStatus finalStatus = request.conclusion() == ReviewConclusion.CONFIRMED
                ? FlagStatus.CONFIRMED : FlagStatus.DISMISSED;
        qualityFlagRepository.completeReview(flagKey, finalStatus, request.reviewedBy(),
                request.reason(), flag.version());
        qualityFlagRepository.insertReview(new QualityFlagReview(
                null, flagKey, flag.observationId(), flag.version(), flag.version(), flag.category(),
                request.conclusion(), request.reason(), request.reviewedBy(),
                confidenceAfter, delta, null));
        if (deduct) {
            // 只改当前版本：更新当前状态并固化当前版本对应快照；更早历史快照永不被改写
            observationRepository.updateConfidence(flag.observationId(), confidenceAfter);
            observationRepository.updateVersionConfidence(
                    flag.observationId(), flag.version(), confidenceAfter);
        }

        QualityFlag stored = qualityFlagRepository.findByFlagKey(flagKey).orElseThrow();
        QualityFlagReviewResponse body = new QualityFlagReviewResponse(
                flagKey, flag.observationId(), flag.version(), flag.version(),
                request.conclusion(), request.reason(), request.reviewedBy(),
                confidenceAfter, deduct, stored.reviewedAt());
        return idempotency.complete(request.requestId(), HttpStatus.OK.value(), body);
    }

    /**
     * 在全新独立事务中条件化地把仍为 PENDING 的标记转 STALE；
     * 主事务回滚不影响该转换，并发/重入调用幂等。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStaleInNewTransaction(String flagKey) {
        qualityFlagRepository.markStaleIfPending(flagKey);
    }
}
