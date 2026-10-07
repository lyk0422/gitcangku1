package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CreateDriftArchiveRequest;
import com.example.starter.calibration.api.dto.DriftArchiveResponse;
import com.example.starter.calibration.api.dto.RevokeRequest;
import com.example.starter.calibration.model.DriftArchive;
import com.example.starter.calibration.repo.DriftArchiveRepository;

/**
 * 参考漂移档案服务：创建（不可修改）、撤销、查询。
 * 档案为同仪器 UTC 毫秒时刻的参考偏移，最多 6 位小数。
 */
@Service
public class DriftArchiveService {

    private final DriftArchiveRepository archives;
    private final RequestDedupService dedup;

    public DriftArchiveService(DriftArchiveRepository archives, RequestDedupService dedup) {
        this.archives = archives;
        this.dedup = dedup;
    }

    /**
     * 创建漂移档案。referencedAt 须为毫秒精度；offset 最多 6 位小数，超精度 400。
     */
    @Transactional
    public DriftArchiveResponse create(CreateDriftArchiveRequest request) {
        return dedup.execute(request.requestId(), "CREATE_DRIFT_ARCHIVE", "drift-archive",
                request.operator(), request.expectedVersion(),
                List.of(IdentityHash.text(request.instrumentId()),
                        IdentityHash.text(request.referencedAt()),
                        IdentityHash.decimal(request.offset())),
                DriftArchiveResponse.class,
                () -> doCreate(request));
    }

    private DriftArchiveResponse doCreate(CreateDriftArchiveRequest request) {
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant referencedAt = Inputs.requireInstant(request.referencedAt(), "referencedAt");
        Inputs.requireMillisPrecision(referencedAt, "referencedAt");
        BigDecimal offset = Inputs.requireDecimal(request.offset(), "offset");
        long id = archives.insert(instrumentId, referencedAt, offset, Instant.now());
        return DtoMapper.toResponse(archives.findById(id).orElseThrow());
    }

    /**
     * 撤销档案。不存在返回 404；重复撤销返回 409。
     * 与批量放行并发时按事务提交顺序生效：撤销先提交则放行整批拒绝。
     */
    @Transactional
    public DriftArchiveResponse revoke(long id, RevokeRequest request) {
        String operator = request == null ? null : request.operator();
        String requestId = request == null ? null : request.requestId();
        String expectedVersion = request == null ? null : request.expectedVersion();
        return dedup.execute(requestId, "REVOKE_DRIFT_ARCHIVE", "drift-archive:" + id,
                operator, expectedVersion, List.of(Long.toString(id)),
                DriftArchiveResponse.class,
                () -> doRevoke(id));
    }

    private DriftArchiveResponse doRevoke(long id) {
        DriftArchive archive = archives.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("漂移档案不存在: " + id));
        if (archive.revoked()) {
            throw ApiException.conflict("ALREADY_REVOKED", "漂移档案已撤销: " + id);
        }
        archives.markRevoked(id, Instant.now());
        return DtoMapper.toResponse(archives.findById(id).orElseThrow());
    }

    /**
     * 按 ID 查询档案，不存在返回 404。
     */
    @Transactional(readOnly = true)
    public DriftArchiveResponse get(long id) {
        return DtoMapper.toResponse(archives.findById(id)
                .orElseThrow(() -> ApiException.notFound("漂移档案不存在: " + id)));
    }
}
