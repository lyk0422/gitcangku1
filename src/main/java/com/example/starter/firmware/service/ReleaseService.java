package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ExpandReleaseRequest;
import com.example.starter.firmware.api.ReleaseView;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.CanaryRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 发布单生命周期：创建（版本从1开始，可声明金丝雀级别）、扩量（版本校验+只增不减）、
 * 取消（未终结任务转 CANCELLED）。金丝雀发布单比例由级别推进驱动，不能手工扩量。
 */
@Service
public class ReleaseService {

    private final ReleaseRepository releaseRepository;
    private final TaskRepository taskRepository;
    private final CanaryRepository canaryRepository;
    private final IdempotencyService idempotency;

    public ReleaseService(ReleaseRepository releaseRepository, TaskRepository taskRepository,
                          CanaryRepository canaryRepository, IdempotencyService idempotency) {
        this.releaseRepository = releaseRepository;
        this.taskRepository = taskRepository;
        this.canaryRepository = canaryRepository;
        this.idempotency = idempotency;
    }

    public ReleaseView create(CreateReleaseRequest request) {
        if (request.fromVersion().equals(request.toVersion())) {
            throw ApiException.badRequest("SAME_VERSION", "目标版本必须与来源版本不同");
        }
        boolean canary = request.levels() != null;
        if (canary) {
            for (int i = 1; i < request.levels().size(); i++) {
                if (request.levels().get(i).ratio() <= request.levels().get(i - 1).ratio()) {
                    throw ApiException.badRequest("INVALID_CANARY_LEVELS",
                            "金丝雀级别比例必须严格递增: 第" + i + "级 " + request.levels().get(i - 1).ratio()
                                    + "，第" + (i + 1) + "级 " + request.levels().get(i).ratio());
                }
            }
        }
        int initialRatio = canary ? request.levels().get(0).ratio() : request.ratio();
        int levelCount = canary ? request.levels().size() : 0;
        String fingerprint = String.join("|", "release.create", request.model(), request.fromVersion(),
                request.toVersion(), String.valueOf(request.ratio()),
                canary ? request.levels().toString() : "-");
        return idempotency.execute(request.requestId(), "release.create", fingerprint, () -> {
            long id;
            try {
                id = releaseRepository.insert(request.model(), request.fromVersion(), request.toVersion(),
                        initialRatio, levelCount);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ACTIVE_RELEASE_EXISTS", "型号已存在 ACTIVE 发布单: " + request.model());
            }
            if (canary) {
                List<CanaryRepository.LevelSpec> specs = request.levels().stream()
                        .map(level -> new CanaryRepository.LevelSpec(level.ratio(), level.minSamples(),
                                level.maxFailureRate()))
                        .toList();
                canaryRepository.insertLevels(id, specs);
            }
            return ReleaseView.of(findOrder(id));
        }, ReleaseView.class);
    }

    public ReleaseView expand(long releaseId, ExpandReleaseRequest request) {
        String fingerprint = String.join("|", "release.expand", String.valueOf(releaseId),
                String.valueOf(request.expectedVersion()), String.valueOf(request.ratio()));
        return idempotency.execute(request.requestId(), "release.expand", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() != ReleaseStatus.ACTIVE) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE", "发布单已取消或完成，不能扩量");
            }
            if (order.isCanary()) {
                throw ApiException.conflict("CANARY_RELEASE_NO_EXPAND",
                        "金丝雀发布单按验证级别推进，不能手工扩量");
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
            if (order.status() == ReleaseStatus.ACTIVE) {
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
