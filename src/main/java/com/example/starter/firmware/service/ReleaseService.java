package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CanaryLevelRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ExpandReleaseRequest;
import com.example.starter.firmware.api.ReleaseView;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.CanaryLevelRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 发布单生命周期：创建（版本从1开始，可声明金丝雀分级）、扩量（版本校验+只增不减）、
 * 取消（未终结任务转 CANCELLED）。分级发布单的比例由推进门禁管理，不允许手工扩量。
 */
@Service
public class ReleaseService {

    private final ReleaseRepository releaseRepository;
    private final TaskRepository taskRepository;
    private final CanaryLevelRepository canaryLevelRepository;
    private final IdempotencyService idempotency;

    public ReleaseService(ReleaseRepository releaseRepository, TaskRepository taskRepository,
                          CanaryLevelRepository canaryLevelRepository, IdempotencyService idempotency) {
        this.releaseRepository = releaseRepository;
        this.taskRepository = taskRepository;
        this.canaryLevelRepository = canaryLevelRepository;
        this.idempotency = idempotency;
    }

    public ReleaseView create(CreateReleaseRequest request) {
        if (request.fromVersion().equals(request.toVersion())) {
            throw ApiException.badRequest("SAME_VERSION", "目标版本必须与来源版本不同");
        }
        List<CanaryLevelRequest> levels = request.levels();
        int effectiveRatio = resolveEffectiveRatio(request, levels);
        StringBuilder fingerprint = new StringBuilder(String.join("|", "release.create", request.model(),
                request.fromVersion(), request.toVersion(), String.valueOf(effectiveRatio)));
        if (levels != null) {
            levels.forEach(level -> fingerprint.append('|').append(level.ratio()).append(',')
                    .append(level.minSamples()).append(',').append(level.maxFailureRate()));
        }
        return idempotency.execute(request.requestId(), "release.create", fingerprint.toString(), () -> {
            long id;
            try {
                id = releaseRepository.insert(request.model(), request.fromVersion(), request.toVersion(),
                        effectiveRatio);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ACTIVE_RELEASE_EXISTS", "型号已存在 ACTIVE 发布单: " + request.model());
            }
            if (levels != null) {
                for (int i = 0; i < levels.size(); i++) {
                    CanaryLevelRequest level = levels.get(i);
                    canaryLevelRepository.insert(id, i + 1, level.ratio(), level.minSamples(),
                            level.maxFailureRate());
                }
            }
            return ReleaseView.of(findOrder(id));
        }, ReleaseView.class);
    }

    /**
     * 计算创建时的生效比例：分级发布单取第 1 级比例，普通发布单取 ratio（必填）。
     */
    private int resolveEffectiveRatio(CreateReleaseRequest request, List<CanaryLevelRequest> levels) {
        if (levels == null || levels.isEmpty()) {
            if (request.ratio() == null) {
                throw ApiException.badRequest("RATIO_REQUIRED", "未声明金丝雀级别时 ratio 必填");
            }
            return request.ratio();
        }
        for (int i = 1; i < levels.size(); i++) {
            if (levels.get(i).ratio() <= levels.get(i - 1).ratio()) {
                throw ApiException.badRequest("LEVELS_NOT_INCREASING",
                        "金丝雀级别比例必须严格递增: 第" + i + "级 " + levels.get(i - 1).ratio()
                                + "，第" + (i + 1) + "级 " + levels.get(i).ratio());
            }
        }
        int firstRatio = levels.get(0).ratio();
        if (request.ratio() != null && request.ratio() != firstRatio) {
            throw ApiException.badRequest("RATIO_LEVEL_MISMATCH",
                    "分级发布单生效比例取第 1 级比例 " + firstRatio + "，与请求 ratio " + request.ratio() + " 不一致");
        }
        return firstRatio;
    }

    public ReleaseView expand(long releaseId, ExpandReleaseRequest request) {
        String fingerprint = String.join("|", "release.expand", String.valueOf(releaseId),
                String.valueOf(request.expectedVersion()), String.valueOf(request.ratio()));
        return idempotency.execute(request.requestId(), "release.expand", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() != ReleaseStatus.ACTIVE) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE", "发布单当前状态不能扩量: " + order.status());
            }
            if (!canaryLevelRepository.findByRelease(releaseId).isEmpty()) {
                throw ApiException.conflict("CANARY_MANAGED", "分级发布单的比例由推进门禁管理，不能手工扩量");
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

    public ReleaseOrder findOrder(long releaseId) {
        return releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
    }
}
