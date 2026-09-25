package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CanaryStatusView;
import com.example.starter.firmware.api.PromoteRequest;
import com.example.starter.firmware.domain.CanaryLevel;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.CanaryRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import org.springframework.stereotype.Service;

/**
 * 金丝雀分级验证门禁：推进（同事务重新核对样本数与失败率）与只读状态查询。
 * 推进、拉取、回执都先锁发布单行，按事务提交顺序裁决。
 */
@Service
public class CanaryService {

    private final ReleaseRepository releaseRepository;
    private final CanaryRepository canaryRepository;
    private final IdempotencyService idempotency;

    public CanaryService(ReleaseRepository releaseRepository, CanaryRepository canaryRepository,
                         IdempotencyService idempotency) {
        this.releaseRepository = releaseRepository;
        this.canaryRepository = canaryRepository;
        this.idempotency = idempotency;
    }

    /**
     * 推进到下一级别：必须从当前已解锁最高级别推进到紧邻下一级别，不能跳级；
     * 目标级别等于级别总数+1 时发布单进入 COMPLETED 终态。已解锁级别的样本与历史不被重置。
     */
    public CanaryStatusView promote(long releaseId, PromoteRequest request) {
        String fingerprint = String.join("|", "release.promote", String.valueOf(releaseId),
                String.valueOf(request.targetLevel()));
        return idempotency.execute(request.promoteKey(), "release.promote", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (!order.isCanary()) {
                throw ApiException.conflict("RELEASE_NOT_CANARY", "发布单未声明金丝雀级别: " + releaseId);
            }
            if (order.status() == ReleaseStatus.CANCELLED) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE", "发布单已取消，不能推进");
            }
            if (order.status() == ReleaseStatus.COMPLETED) {
                throw ApiException.conflict("RELEASE_COMPLETED", "发布单已完成，不能推进");
            }
            int current = order.unlockedLevel();
            if (request.targetLevel() != current + 1) {
                throw ApiException.unprocessable("PROMOTE_SKIP_LEVEL",
                        "不能跳级：当前已解锁最高级别为 " + current + "，只能推进到级别 " + (current + 1)
                                + "，请求目标级别 " + request.targetLevel());
            }
            // 同一事务内在发布单行锁保护下重新核对当前级别样本数与失败率
            CanaryLevel level = canaryRepository.findLevel(releaseId, current)
                    .orElseThrow(() -> new IllegalStateException("金丝雀级别缺失: " + current));
            if (level.sampleCount() < level.minSamples()) {
                throw ApiException.unprocessable("SAMPLE_INSUFFICIENT",
                        "级别 " + current + " 样本不足：当前 " + level.sampleCount()
                                + "，最少需 " + level.minSamples()
                                + "，还差 " + (level.minSamples() - level.sampleCount()));
            }
            if (level.failureRateExceeded()) {
                throw ApiException.unprocessable("FAILURE_RATE_EXCEEDED",
                        "级别 " + current + " 失败率超限：当前 " + level.failCount() + "/" + level.sampleCount()
                                + " (" + (level.failCount() * 100 / level.sampleCount()) + "%)，上限 "
                                + level.maxFailureRate() + "%，须先经失败处理，不能推进跳过");
            }
            if (request.targetLevel() > order.levelCount()) {
                releaseRepository.complete(releaseId);
            } else {
                CanaryLevel next = canaryRepository.findLevel(releaseId, request.targetLevel())
                        .orElseThrow(() -> new IllegalStateException("金丝雀级别缺失: " + request.targetLevel()));
                canaryRepository.unlock(releaseId, request.targetLevel());
                releaseRepository.unlockLevel(releaseId, request.targetLevel(), next.ratio());
            }
            canaryRepository.insertPromotion(releaseId, current, request.targetLevel(), request.promoteKey(),
                    level.sampleCount(), level.failCount());
            return buildView(releaseId);
        }, CanaryStatusView.class);
    }

    /**
     * 当前解锁级别、各级别样本与失败率统计及推进历史。只读，不触发状态变化。
     */
    public CanaryStatusView status(long releaseId) {
        ReleaseOrder order = releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
        if (!order.isCanary()) {
            throw ApiException.notFound("CANARY_NOT_CONFIGURED", "发布单未声明金丝雀级别: " + releaseId);
        }
        return buildView(releaseId);
    }

    private CanaryStatusView buildView(long releaseId) {
        ReleaseOrder order = releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
        return CanaryStatusView.of(order, canaryRepository.findLevels(releaseId),
                canaryRepository.findPromotions(releaseId));
    }
}
