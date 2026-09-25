package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CanaryStatusView;
import com.example.starter.firmware.api.PromoteRequest;
import com.example.starter.firmware.api.PromoteView;
import com.example.starter.firmware.domain.CanaryLevel;
import com.example.starter.firmware.domain.CanaryPromotion;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.CanaryLevelRepository;
import com.example.starter.firmware.repo.CanaryPromotionRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 金丝雀分级门禁：级别按顺序解锁，推进在同一事务内重新核对样本数与失败率；
 * 推进与拉取、回执通过发布单行锁串行，按事务提交顺序裁决。
 */
@Service
public class CanaryService {

    private final ReleaseRepository releaseRepository;
    private final CanaryLevelRepository canaryLevelRepository;
    private final CanaryPromotionRepository canaryPromotionRepository;
    private final IdempotencyService idempotency;

    public CanaryService(ReleaseRepository releaseRepository, CanaryLevelRepository canaryLevelRepository,
                         CanaryPromotionRepository canaryPromotionRepository, IdempotencyService idempotency) {
        this.releaseRepository = releaseRepository;
        this.canaryLevelRepository = canaryLevelRepository;
        this.canaryPromotionRepository = canaryPromotionRepository;
        this.idempotency = idempotency;
    }

    /**
     * 推进到紧邻下一级别；当前为最高级别时进入 COMPLETED 终态。门禁不满足或跳级返回 422。
     */
    public PromoteView promote(long releaseId, PromoteRequest request) {
        String fingerprint = String.join("|", "release.promote", String.valueOf(releaseId),
                String.valueOf(request.targetLevel()));
        return idempotency.execute(request.promoteKey(), "release.promote", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            List<CanaryLevel> levels = canaryLevelRepository.findByRelease(releaseId);
            if (levels.isEmpty()) {
                throw ApiException.unprocessable("NO_CANARY_LEVELS", "发布单未配置金丝雀分级: " + releaseId);
            }
            if (order.status() == ReleaseStatus.PAUSED) {
                throw ApiException.unprocessable("RELEASE_PAUSED",
                        "发布单失败率超限已自动暂停，必须先处理暂停，不能推进");
            }
            if (order.status() != ReleaseStatus.ACTIVE) {
                throw ApiException.unprocessable("RELEASE_NOT_PROMOTABLE",
                        "发布单当前状态不能推进: " + order.status());
            }
            int currentLevel = order.currentLevel();
            int maxLevel = levels.get(levels.size() - 1).levelNo();
            int target = request.targetLevel() != null ? request.targetLevel() : currentLevel + 1;
            if (target > currentLevel + 1) {
                throw ApiException.unprocessable("LEVEL_SKIP",
                        "不能跳级：当前解锁第 " + currentLevel + " 级，只能推进到第 " + (currentLevel + 1)
                                + " 级，请求第 " + target + " 级");
            }
            if (target <= currentLevel) {
                throw ApiException.unprocessable("LEVEL_INVALID",
                        "目标级别必须大于当前解锁级别 " + currentLevel + "，请求第 " + target + " 级");
            }
            CanaryLevel current = levels.get(currentLevel - 1);
            checkGate(current);
            if (currentLevel < maxLevel) {
                CanaryLevel next = levels.get(currentLevel);
                releaseRepository.unlockLevel(releaseId, next.levelNo(), next.ratio());
                canaryPromotionRepository.insert(releaseId, currentLevel, next.levelNo(), "UNLOCK",
                        current.sampleCount(), current.failedCount(), request.promoteKey());
                ReleaseOrder updated = releaseRepository.findById(releaseId).orElseThrow();
                return new PromoteView(releaseId, "UNLOCKED", updated.currentLevel(), updated.ratio(),
                        updated.status().name());
            }
            releaseRepository.complete(releaseId);
            canaryPromotionRepository.insert(releaseId, currentLevel, null, "COMPLETE",
                    current.sampleCount(), current.failedCount(), request.promoteKey());
            ReleaseOrder updated = releaseRepository.findById(releaseId).orElseThrow();
            return new PromoteView(releaseId, "COMPLETED", updated.currentLevel(), updated.ratio(),
                    updated.status().name());
        }, PromoteView.class);
    }

    /**
     * 门禁核对：样本数达到最小样本数且失败率不超过上限，任一不满足返回 422 并说明差距。
     */
    private void checkGate(CanaryLevel current) {
        if (current.sampleCount() < current.minSamples()) {
            throw ApiException.unprocessable("GATE_SAMPLES_INSUFFICIENT",
                    "第 " + current.levelNo() + " 级样本数不足：当前 " + current.sampleCount()
                            + "，要求至少 " + current.minSamples() + "，还差 "
                            + (current.minSamples() - current.sampleCount()));
        }
        if (current.failureRateExceeded()) {
            throw ApiException.unprocessable("GATE_FAILURE_RATE_EXCEEDED",
                    "第 " + current.levelNo() + " 级失败率超限：当前 " + current.failureRatePercent()
                            + "%（" + current.failedCount() + "/" + current.sampleCount() + "），上限 "
                            + current.maxFailureRate() + "%");
        }
    }

    /**
     * 只读查询：当前解锁级别、各级别样本与失败率统计及推进历史，不触发状态变化。
     */
    public CanaryStatusView getStatus(long releaseId) {
        ReleaseOrder order = releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
        List<CanaryLevel> levels = canaryLevelRepository.findByRelease(releaseId);
        List<CanaryPromotion> promotions = canaryPromotionRepository.findByRelease(releaseId);
        return CanaryStatusView.of(order, levels, promotions);
    }
}
