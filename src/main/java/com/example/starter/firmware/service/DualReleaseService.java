package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateDualReleaseRequest;
import com.example.starter.firmware.api.DualReleaseView;
import com.example.starter.firmware.api.PauseDualReleaseRequest;
import com.example.starter.firmware.domain.DualRelease;
import com.example.starter.firmware.domain.DualReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DualReleaseRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 双分区发布单生命周期：创建（同型号至多一张未终结单）、暂停（禁止新试启动，
 * 但不撤销已有 TRIAL 的健康确认）。查询前惰性结算到期 TRIAL 任务，不依赖后台线程。
 */
@Service
public class DualReleaseService {

    private final DualReleaseRepository dualReleaseRepository;
    private final DualTaskService dualTaskService;
    private final IdempotencyService idempotency;

    public DualReleaseService(DualReleaseRepository dualReleaseRepository,
                              DualTaskService dualTaskService, IdempotencyService idempotency) {
        this.dualReleaseRepository = dualReleaseRepository;
        this.dualTaskService = dualTaskService;
        this.idempotency = idempotency;
    }

    public DualReleaseView create(CreateDualReleaseRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", request.model());
        params.put("targetVersion", request.targetVersion());
        String fingerprint = Fingerprints.of("dual.release.create", request.operator(),
                "dual-release-model:" + request.model(), params);
        return idempotency.execute(request.requestId(), "dual.release.create", fingerprint, () -> {
            long id;
            try {
                id = dualReleaseRepository.insert(request.model(), request.targetVersion());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ACTIVE_RELEASE_EXISTS",
                        "型号已存在未终结双分区发布单: " + request.model());
            }
            return DualReleaseView.of(findRelease(id));
        }, DualReleaseView.class);
    }

    /**
     * 暂停：仅 ACTIVE 可暂停，expectedVersion 必须匹配当前版本；暂停后版本加一。
     */
    public DualReleaseView pause(long releaseId, PauseDualReleaseRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("expectedVersion", request.expectedVersion());
        String fingerprint = Fingerprints.of("dual.release.pause", request.operator(),
                "dual-release:" + releaseId, params);
        return idempotency.execute(request.requestId(), "dual.release.pause", fingerprint, () -> {
            DualRelease release = dualReleaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "双分区发布单不存在: " + releaseId));
            if (release.status() != DualReleaseStatus.ACTIVE) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE",
                        "双分区发布单状态为 " + release.status() + "，不能暂停");
            }
            if (release.version() != request.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + release.version());
            }
            dualReleaseRepository.pauseIfActive(releaseId, request.expectedVersion());
            return DualReleaseView.of(findRelease(releaseId));
        }, DualReleaseView.class);
    }

    /**
     * 查询发布单：先惰性结算到期 TRIAL 任务，再返回当前视图。
     */
    public DualReleaseView get(long releaseId) {
        dualTaskService.settleExpiredForRelease(releaseId);
        return DualReleaseView.of(findRelease(releaseId));
    }

    public DualRelease findRelease(long releaseId) {
        return dualReleaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "双分区发布单不存在: " + releaseId));
    }
}
