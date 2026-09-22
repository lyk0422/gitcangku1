package com.example.starter.firmware.service;

import com.example.starter.firmware.dto.CancelRolloutRequest;
import com.example.starter.firmware.dto.CreateRolloutRequest;
import com.example.starter.firmware.dto.ExpandRolloutRequest;
import com.example.starter.firmware.dto.RolloutResponse;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repository.RolloutRepository;
import com.example.starter.firmware.repository.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 灰度发布单：创建、扩量（比例只增不减、版本号乐观并发控制）、取消。
 */
@Service
public class RolloutService {

    private final RolloutRepository rolloutRepository;
    private final TaskRepository taskRepository;
    private final IdempotencyService idempotencyService;

    public RolloutService(RolloutRepository rolloutRepository,
                          TaskRepository taskRepository,
                          IdempotencyService idempotencyService) {
        this.rolloutRepository = rolloutRepository;
        this.taskRepository = taskRepository;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 创建发布单：版本从 1 开始；目标与来源版本必须不同；同型号至多一张 ACTIVE。
     */
    public ApiResult create(CreateRolloutRequest request) {
        if (request.fromVersion().equals(request.toVersion())) {
            throw ApiException.badRequest("SAME_VERSION", "目标版本必须与来源版本不同");
        }
        String fingerprint = String.join("|", request.model(), request.fromVersion(),
                request.toVersion(), String.valueOf(request.ratio()));
        return idempotencyService.execute(request.requestId(), "ROLLOUT_CREATE", fingerprint,
                () -> doCreate(request));
    }

    private ApiResult doCreate(CreateRolloutRequest request) {
        long id;
        try {
            id = rolloutRepository.insert(request.model(), request.fromVersion(),
                    request.toVersion(), request.ratio());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("ROLLOUT_ACTIVE_EXISTS",
                    "型号已存在 ACTIVE 发布单: " + request.model());
        }
        return ApiResult.created(new RolloutResponse(id, request.model(), request.fromVersion(),
                request.toVersion(), request.ratio(), "ACTIVE", 1));
    }

    /**
     * 扩量：比例只增不减；expectedVersion 与当前版本不一致返回 409；成功版本加一。
     */
    public ApiResult expand(long rolloutId, ExpandRolloutRequest request) {
        String fingerprint = rolloutId + "|" + request.expectedVersion() + "|" + request.ratio();
        return idempotencyService.execute(request.requestId(), "ROLLOUT_EXPAND", fingerprint,
                () -> doExpand(rolloutId, request));
    }

    private ApiResult doExpand(long rolloutId, ExpandRolloutRequest request) {
        RolloutResponse rollout = rolloutRepository.findByIdForUpdate(rolloutId)
                .orElseThrow(() -> ApiException.notFound("ROLLOUT_NOT_FOUND", "发布单不存在: " + rolloutId));
        if (!"ACTIVE".equals(rollout.status())) {
            throw ApiException.conflict("ROLLOUT_NOT_ACTIVE", "发布单已取消，不能扩量: " + rolloutId);
        }
        if (rollout.version() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "期望版本 " + request.expectedVersion() + " 与当前版本 " + rollout.version() + " 不一致");
        }
        if (request.ratio() < rollout.ratio()) {
            throw ApiException.conflict("RATIO_DECREASED",
                    "投放比例只增不减: 当前 " + rollout.ratio() + "，请求 " + request.ratio());
        }
        int newVersion = rollout.version() + 1;
        rolloutRepository.updateRatio(rolloutId, request.ratio(), newVersion);
        return ApiResult.ok(new RolloutResponse(rollout.id(), rollout.model(), rollout.fromVersion(),
                rollout.toVersion(), request.ratio(), rollout.status(), newVersion));
    }

    /**
     * 取消发布单：未终结任务置为 CANCELLED；与拉取/回执通过行锁形成一致提交顺序。
     */
    public ApiResult cancel(long rolloutId, CancelRolloutRequest request) {
        return idempotencyService.execute(request.requestId(), "ROLLOUT_CANCEL", String.valueOf(rolloutId),
                () -> doCancel(rolloutId));
    }

    private ApiResult doCancel(long rolloutId) {
        RolloutResponse rollout = rolloutRepository.findByIdForUpdate(rolloutId)
                .orElseThrow(() -> ApiException.notFound("ROLLOUT_NOT_FOUND", "发布单不存在: " + rolloutId));
        if (!"ACTIVE".equals(rollout.status())) {
            throw ApiException.conflict("ROLLOUT_NOT_ACTIVE", "发布单已取消: " + rolloutId);
        }
        int newVersion = rollout.version() + 1;
        rolloutRepository.cancel(rolloutId, newVersion);
        taskRepository.cancelPendingByRollout(rolloutId);
        return ApiResult.ok(new RolloutResponse(rollout.id(), rollout.model(), rollout.fromVersion(),
                rollout.toVersion(), rollout.ratio(), "CANCELLED", newVersion));
    }
}
