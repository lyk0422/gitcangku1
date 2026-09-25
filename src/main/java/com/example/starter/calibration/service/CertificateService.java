package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Clock;
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
 * 校准标准器证书服务：创建（版本唯一、区间不重叠，并发安全）、撤销、查询、时间线。
 */
@Service
public class CertificateService {

    /** 默认证书版本。 */
    static final String DEFAULT_VERSION = "v1";
    /** 默认不确定度版本。 */
    static final String DEFAULT_UNCERTAINTY_VERSION = "v1";

    private final CertificateRepository certificates;
    private final Clock clock;

    public CertificateService(CertificateRepository certificates, Clock clock) {
        this.certificates = certificates;
        this.clock = clock;
    }

    /**
     * 创建证书。同一标准器未撤销证书的版本号必须唯一、区间不得重叠（相邻合法）；
     * 通过标准器级行锁串行化，并发创建时满足约束者最多一张成功。
     */
    @Transactional
    public CertificateResponse create(CreateCertificateRequest request) {
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        String standardId = Inputs.optionalText(request.standardId());
        if (standardId == null) {
            standardId = instrumentId;
        }
        Instant validFrom = Inputs.requireInstant(request.validFrom(), "validFrom");
        Instant validTo = Inputs.requireInstant(request.validTo(), "validTo");
        BigDecimal a = Inputs.requireDecimal(request.a(), "a");
        BigDecimal b = Inputs.requireDecimal(request.b(), "b");
        BigDecimal uncertainty = Inputs.optionalNonNegative(
                request.uncertainty(), "uncertainty", BigDecimal.ZERO);
        String uncertaintyVersion = Inputs.optionalVersion(
                request.uncertaintyVersion(), DEFAULT_UNCERTAINTY_VERSION);
        boolean singleBatchOnly = Boolean.TRUE.equals(request.singleBatchOnly());
        if (!validFrom.isBefore(validTo)) {
            throw ApiException.badRequest("validFrom 必须早于 validTo");
        }

        certificates.lockInstrument(standardId);
        // 版本号：显式提供则校验该标准器下未撤销证书版本唯一；缺省则按该标准器证书总数自动递增
        String explicitVersion = Inputs.optionalText(request.version());
        String version;
        boolean autoVersioned = explicitVersion == null;
        if (autoVersioned) {
            version = "v" + (certificates.countAllForStandard(standardId) + 1);
        } else {
            version = Inputs.optionalVersion(explicitVersion, DEFAULT_VERSION);
            if (certificates.countActiveVersion(standardId, version) > 0) {
                throw ApiException.conflict("CERTIFICATE_VERSION_EXISTS",
                        "标准器已存在该版本的未撤销证书: " + standardId + "@" + version);
            }
        }
        // 区间不重叠仅约束“自动匹配池”（未显式版本）证书，保证按时刻自动匹配唯一；
        // 显式版本证书允许时间窗重叠（换版过渡），由 (standardId, version) 显式引用消歧。
        if (autoVersioned && certificates.countActiveOverlap(standardId, validFrom, validTo) > 0) {
            throw ApiException.conflict("CERTIFICATE_OVERLAP", "同一标准器存在区间重叠的未撤销证书");
        }
        long id = certificates.insert(standardId, instrumentId, version, validFrom, validTo,
                a, b, uncertainty, uncertaintyVersion, singleBatchOnly, clock.instant());
        return DtoMapper.toResponse(certificates.findById(id).orElseThrow());
    }

    /**
     * 撤销证书。不存在返回 404；重复撤销返回 409。
     * 与批量放行并发时按事务提交顺序生效：撤销先提交则放行整批拒绝。
     * 撤销只阻断后续测量与放行，不删除历史测量或已放行快照。
     */
    @Transactional
    public CertificateResponse revoke(long id) {
        Certificate cert = certificates.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + id));
        if (cert.revoked()) {
            throw ApiException.conflict("ALREADY_REVOKED", "证书已撤销: " + id);
        }
        certificates.markRevoked(id, clock.instant());
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
     * 查询某标准器的证书时间线（含已撤销），按有效期起点升序。
     */
    @Transactional(readOnly = true)
    public List<CertificateResponse> timeline(String standardId) {
        String id = Inputs.requireText(standardId, "standardId");
        return certificates.findTimeline(id).stream().map(DtoMapper::toResponse).toList();
    }
}
