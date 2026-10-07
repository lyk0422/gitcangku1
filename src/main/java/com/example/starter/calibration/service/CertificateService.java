package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.CreateCertificateRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CertificateMode;
import com.example.starter.calibration.repo.CertificateRepository;

/**
 * 校准证书服务：创建（区间不重叠，并发安全）、撤销、查询。
 * 支持线性（y=ax+b）与二次（y=ax²+bx+c，输入闭区间 [L,U]，导数区间严格同号）两种模式。
 */
@Service
public class CertificateService {

    private final CertificateRepository certificates;

    public CertificateService(CertificateRepository certificates) {
        this.certificates = certificates;
    }

    /**
     * 创建证书。同一仪器未撤销证书区间不得重叠（相邻合法）；
     * 通过仪器级行锁串行化，并发创建重叠证书时最多一张成功。
     * 二次模式：a 非零、[L,U] 闭区间合法、导数端点严格同号（非单调 400）。
     */
    @Transactional
    public CertificateResponse create(CreateCertificateRequest request) {
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant validFrom = Inputs.requireInstant(request.validFrom(), "validFrom");
        Instant validTo = Inputs.requireInstant(request.validTo(), "validTo");
        if (!validFrom.isBefore(validTo)) {
            throw ApiException.badRequest("validFrom 必须早于 validTo");
        }
        CertificateMode mode = parseMode(request.mode());
        BigDecimal a = Inputs.requireDecimal(request.a(), "a");
        BigDecimal b = Inputs.requireDecimal(request.b(), "b");
        BigDecimal c = null;
        BigDecimal domainLo = null;
        BigDecimal domainHi = null;
        if (mode == CertificateMode.QUADRATIC) {
            if (a.signum() == 0) {
                throw ApiException.badRequest("二次证书系数 a 必须非零");
            }
            c = Inputs.requireDecimal(request.c(), "c");
            domainLo = Inputs.requireDecimal(request.domainLo(), "domainLo");
            domainHi = Inputs.requireDecimal(request.domainHi(), "domainHi");
            if (domainLo.compareTo(domainHi) >= 0) {
                throw ApiException.badRequest("domainLo 必须小于 domainHi");
            }
            Quadratic.requireMonotonic(a, b, domainLo, domainHi);
        } else if (request.c() != null || request.domainLo() != null || request.domainHi() != null) {
            throw ApiException.badRequest("线性证书不接受 c/domainLo/domainHi");
        }

        certificates.lockInstrument(instrumentId);
        if (certificates.countActiveOverlap(instrumentId, validFrom, validTo) > 0) {
            throw ApiException.conflict("CERTIFICATE_OVERLAP", "同一仪器存在区间重叠的未撤销证书");
        }
        long id = certificates.insert(instrumentId, validFrom, validTo, mode, a, b, c,
                domainLo, domainHi, Instant.now());
        return DtoMapper.toResponse(certificates.findById(id).orElseThrow());
    }

    /**
     * 撤销证书。不存在返回 404；重复撤销返回 409。
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

    private static CertificateMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return CertificateMode.LINEAR;
        }
        try {
            return CertificateMode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("mode 必须为 LINEAR 或 QUADRATIC");
        }
    }
}
