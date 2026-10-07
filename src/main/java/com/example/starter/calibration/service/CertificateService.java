package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.CreateCertificateRequest;
import com.example.starter.calibration.api.dto.RevokeRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.repo.CertificateRepository;

/**
 * 校准证书服务：创建（区间不重叠，并发安全）、撤销、查询。
 */
@Service
public class CertificateService {

    private final CertificateRepository certificates;
    private final RequestDedupService dedup;

    public CertificateService(CertificateRepository certificates, RequestDedupService dedup) {
        this.certificates = certificates;
        this.dedup = dedup;
    }

    /**
     * 创建证书。同一仪器未撤销证书区间不得重叠（相邻合法）；
     * 通过仪器级行锁串行化，并发创建重叠证书时最多一张成功。
     */
    @Transactional
    public CertificateResponse create(CreateCertificateRequest request) {
        return dedup.execute(request.requestId(), "CREATE_CERTIFICATE", "certificate",
                request.operator(), request.expectedVersion(),
                List.of(IdentityHash.text(request.instrumentId()),
                        IdentityHash.text(request.validFrom()),
                        IdentityHash.text(request.validTo()),
                        IdentityHash.decimal(request.a()),
                        IdentityHash.decimal(request.b())),
                CertificateResponse.class,
                () -> doCreate(request));
    }

    private CertificateResponse doCreate(CreateCertificateRequest request) {
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant validFrom = Inputs.requireInstant(request.validFrom(), "validFrom");
        Instant validTo = Inputs.requireInstant(request.validTo(), "validTo");
        BigDecimal a = Inputs.requireDecimal(request.a(), "a");
        BigDecimal b = Inputs.requireDecimal(request.b(), "b");
        if (!validFrom.isBefore(validTo)) {
            throw ApiException.badRequest("validFrom 必须早于 validTo");
        }

        certificates.lockInstrument(instrumentId);
        if (certificates.countActiveOverlap(instrumentId, validFrom, validTo) > 0) {
            throw ApiException.conflict("CERTIFICATE_OVERLAP", "同一仪器存在区间重叠的未撤销证书");
        }
        long id = certificates.insert(instrumentId, validFrom, validTo, a, b, Instant.now());
        return DtoMapper.toResponse(certificates.findById(id).orElseThrow());
    }

    /**
     * 撤销证书。不存在返回 404；重复撤销返回 409。
     * 与批量放行并发时按事务提交顺序生效：撤销先提交则放行整批拒绝。
     */
    @Transactional
    public CertificateResponse revoke(long id, RevokeRequest request) {
        String operator = request == null ? null : request.operator();
        String requestId = request == null ? null : request.requestId();
        String expectedVersion = request == null ? null : request.expectedVersion();
        return dedup.execute(requestId, "REVOKE_CERTIFICATE", "certificate:" + id,
                operator, expectedVersion, List.of(Long.toString(id)),
                CertificateResponse.class,
                () -> doRevoke(id));
    }

    private CertificateResponse doRevoke(long id) {
        Certificate cert = certificates.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + id));
        if (cert.revoked()) {
            throw ApiException.conflict("ALREADY_REVOKED", "证书已撤销: " + id);
        }
        certificates.markRevoked(id, Instant.now());
        return DtoMapper.toResponse(certificates.findById(id).orElseThrow());
    }

    /**
     * 按 ID 查询证书，不存在返回 404。
     */
    @Transactional(readOnly = true)
    public CertificateResponse get(long id) {
        return DtoMapper.toResponse(certificates.findById(id)
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + id)));
    }
}
