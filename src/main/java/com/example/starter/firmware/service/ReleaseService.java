package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ExpandReleaseRequest;
import com.example.starter.firmware.api.ReleaseView;
import com.example.starter.firmware.api.UpdateRegionLimitRequest;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 发布单生命周期：创建（版本从1开始）、扩量（版本校验+只增不减）、取消（未终结任务转 CANCELLED）、
 * 区域上限修改（版本校验，只影响后续拉取判定，不影响已下发任务）。
 */
@Service
public class ReleaseService {

    private final ReleaseRepository releaseRepository;
    private final TaskRepository taskRepository;
    private final IdempotencyService idempotency;

    public ReleaseService(ReleaseRepository releaseRepository, TaskRepository taskRepository,
                          IdempotencyService idempotency) {
        this.releaseRepository = releaseRepository;
        this.taskRepository = taskRepository;
        this.idempotency = idempotency;
    }

    public ReleaseView create(CreateReleaseRequest request) {
        if (request.fromVersion().equals(request.toVersion())) {
            throw ApiException.badRequest("SAME_VERSION", "目标版本必须与来源版本不同");
        }
        String fingerprint = String.join("|", "release.create", request.model(), request.fromVersion(),
                request.toVersion(), String.valueOf(request.ratio()), String.valueOf(request.regionLimit()));
        return idempotency.execute(request.requestId(), "release.create", fingerprint, () -> {
            long id;
            try {
                id = releaseRepository.insert(request.model(), request.fromVersion(), request.toVersion(),
                        request.ratio(), request.regionLimit());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ACTIVE_RELEASE_EXISTS", "型号已存在 ACTIVE 发布单: " + request.model());
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
                throw ApiException.conflict("RELEASE_NOT_ACTIVE", "发布单已取消，不能扩量");
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
     * 修改区域上限：携带 expectedVersion 乐观校验，成功版本加一；只影响后续拉取判定，不影响已下发任务。
     */
    public ReleaseView updateRegionLimit(long releaseId, UpdateRegionLimitRequest request) {
        String fingerprint = String.join("|", "release.regionLimit", String.valueOf(releaseId),
                String.valueOf(request.expectedVersion()), String.valueOf(request.regionLimit()));
        return idempotency.execute(request.requestId(), "release.regionLimit", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() != ReleaseStatus.ACTIVE) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE", "发布单已取消，不能修改区域上限");
            }
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + order.version());
            }
            releaseRepository.updateRegionLimit(releaseId, request.expectedVersion(), request.regionLimit());
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
