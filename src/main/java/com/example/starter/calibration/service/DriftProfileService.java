package com.example.starter.calibration.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CreateDriftProfileRequest;
import com.example.starter.calibration.api.dto.DriftProfileResponse;
import com.example.starter.calibration.api.dto.RevokeDriftProfileRequest;
import com.example.starter.calibration.model.DriftProfile;
import com.example.starter.calibration.repo.DriftProfileRepository;

/**
 * 参考漂移档案服务：创建（幂等）、撤销（幂等）、查询。
 * 所有写操作使用全局 requestId 去重，业务与去重记录同事务原子提交。
 */
@Service
public class DriftProfileService {

    private final DriftProfileRepository profiles;
    private final IdempotencyExecutor idempotency;

    public DriftProfileService(DriftProfileRepository profiles, IdempotencyExecutor idempotency) {
        this.profiles = profiles;
        this.idempotency = idempotency;
    }

    /**
     * 创建漂移档案。档案为同仪器 UTC 毫秒时刻及参考偏移（最多 6 位小数），创建后不可改。
     * 同 requestId 同参返回首次完整响应，异参 409；失败可复用键。
     */
    @Transactional
    public DriftProfileResponse create(CreateDriftProfileRequest request) {
        String operator = Inputs.requireText(request.operator(), "operator");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant profiledAt = Inputs.requireMillisInstant(request.profiledAt(), "profiledAt");
        java.math.BigDecimal offset = Inputs.requireDecimal(request.referenceOffset(), "referenceOffset");

        String fingerprint = Fingerprints.of("CREATE_DRIFT_PROFILE", instrumentId, operator,
                        request.expectedVersion())
                .instant("profiledAt", profiledAt)
                .decimal("referenceOffset", offset)
                .build();
        return idempotency.execute(request.requestId(), "CREATE_DRIFT_PROFILE", fingerprint,
                DriftProfileResponse.class, () -> {
                    long id = profiles.insert(instrumentId, profiledAt, offset, Instant.now());
                    return DtoMapper.toResponse(profiles.findById(id).orElseThrow());
                });
    }

    /**
     * 撤销漂移档案。不存在 404；重复撤销 409。
     * 与测量提交、批量放行并发时按事务提交顺序生效（行锁互斥）。
     */
    @Transactional
    public DriftProfileResponse revoke(long id, RevokeDriftProfileRequest request) {
        String operator = Inputs.requireText(request.operator(), "operator");
        String fingerprint = Fingerprints.of("REVOKE_DRIFT_PROFILE", "drift-profile/" + id, operator,
                        request.expectedVersion())
                .build();
        return idempotency.execute(request.requestId(), "REVOKE_DRIFT_PROFILE", fingerprint,
                DriftProfileResponse.class, () -> {
                    DriftProfile profile = profiles.findByIdForUpdate(id)
                            .orElseThrow(() -> ApiException.notFound("漂移档案不存在: " + id));
                    if (profile.revoked()) {
                        throw ApiException.conflict("ALREADY_REVOKED", "漂移档案已撤销: " + id);
                    }
                    profiles.markRevoked(id, Instant.now());
                    return DtoMapper.toResponse(profiles.findById(id).orElseThrow());
                });
    }

    /**
     * 按 ID 查询漂移档案，不存在返回 404。
     */
    @Transactional(readOnly = true)
    public DriftProfileResponse get(long id) {
        return DtoMapper.toResponse(profiles.findById(id)
                .orElseThrow(() -> ApiException.notFound("漂移档案不存在: " + id)));
    }
}
