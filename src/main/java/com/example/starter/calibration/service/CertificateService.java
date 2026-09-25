package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.CreateCertificateRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.repo.CertificateRepository;

/**
 * 校准证书（标准器证书）服务：创建（区间不重叠，并发安全）、撤销、查询、时间线。
 */
@Service
public class CertificateService {

    /** 证书版本缺省值。 */
    static final String DEFAULT_CERT_VERSION = "1";
    /** 不确定度版本缺省值。 */
    static final String DEFAULT_UNCERTAINTY_VERSION = "1";

    private final CertificateRepository certificates;

    public CertificateService(CertificateRepository certificates) {
        this.certificates = certificates;
    }

    /**
     * 创建证书。同一仪器未撤销证书区间不得重叠（相邻合法）；
     * 通过仪器级行锁串行化，并发创建重叠证书时最多一张成功。
     * certVersion、compensationCoeff、uncertaintyVersion、singleBatchOnly 可缺省。
     */
    @Transactional
    public CertificateResponse create(CreateCertificateRequest request) {
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant validFrom = Inputs.requireInstant(request.validFrom(), "validFrom");
        Instant validTo = Inputs.requireInstant(request.validTo(), "validTo");
        BigDecimal a = Inputs.requireDecimal(request.a(), "a");
        BigDecimal b = Inputs.requireDecimal(request.b(), "b");
        String certVersion = request.certVersion() == null
                ? DEFAULT_CERT_VERSION : Inputs.requireText(request.certVersion(), "certVersion");
        BigDecimal compensationCoeff = request.compensationCoeff() == null
                ? BigDecimal.ZERO : Inputs.requireDecimal(request.compensationCoeff(), "compensationCoeff");
        String uncertaintyVersion = request.uncertaintyVersion() == null
                ? DEFAULT_UNCERTAINTY_VERSION
                : Inputs.requireText(request.uncertaintyVersion(), "uncertaintyVersion");
        boolean singleBatchOnly = Boolean.TRUE.equals(request.singleBatchOnly());
        if (!validFrom.isBefore(validTo)) {
            throw ApiException.badRequest("validFrom 必须早于 validTo");
        }

        certificates.lockInstrument(instrumentId);
        if (certificates.countActiveOverlap(instrumentId, validFrom, validTo) > 0) {
            throw ApiException.conflict("CERTIFICATE_OVERLAP", "同一仪器存在区间重叠的未撤销证书");
        }
        long id = certificates.insert(instrumentId, validFrom, validTo, a, b,
                certVersion, compensationCoeff, uncertaintyVersion, singleBatchOnly, Instant.now());
        return DtoMapper.toResponse(certificates.findById(id).orElseThrow());
    }

    /**
     * 撤销证书。不存在返回 404；重复撤销返回 409。
     * 撤销只阻断后续测量与放行，不删除历史测量或已放行快照；
     * 与批量放行并发时按事务提交顺序生效：撤销先提交则放行整批拒绝。
     */
    @Transactional
    public CertificateResponse revoke(long id) {
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

    /**
     * 证书时间线：某仪器全部证书（含已撤销），按有效期起点升序。
     */
    @Transactional(readOnly = true)
    public List<CertificateResponse> timeline(String instrumentId) {
        String instrument = Inputs.requireText(instrumentId, "instrumentId");
        return certificates.findTimeline(instrument).stream()
                .map(DtoMapper::toResponse)
                .toList();
    }
}
