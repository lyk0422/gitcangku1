package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CertificateResponse;
import com.example.starter.calibration.api.dto.CreateCertificateRequest;
import com.example.starter.calibration.api.dto.CreateQuadraticCertificateRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CertificateType;
import com.example.starter.calibration.repo.CertificateRepository;

/**
 * 校准证书服务：创建（区间不重叠，并发安全）、撤销、查询。
 * 二次证书额外声明输入闭区间 [L, U] 的单调分支，创建时由端点精确验证导数严格同号。
 */
@Service
public class CertificateService {

    private final CertificateRepository certificates;
    private final IdempotencyService idempotency;

    public CertificateService(CertificateRepository certificates, IdempotencyService idempotency) {
        this.certificates = certificates;
        this.idempotency = idempotency;
    }

    /**
     * 创建线性证书。同一仪器未撤销证书区间不得重叠（相邻合法）；
     * 通过仪器级行锁串行化，并发创建重叠证书时最多一张成功。
     */
    @Transactional
    public CertificateResponse create(CreateCertificateRequest request) {
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
        long id = certificates.insert(instrumentId, validFrom, validTo, CertificateType.LINEAR,
                a, b, null, null, null, Instant.now());
        return DtoMapper.toResponse(certificates.findById(id).orElseThrow());
    }

    /**
     * 创建二次证书（幂等）：y = a·x² + b·x + c，输入闭区间 [L, U]。
     * a 非零；导数 2a·x+b 须在整个区间严格同号（由端点精确验证），非单调返回 400。
     * 区间重叠门禁与线性证书一致；requestId 全局去重，重放返回首次完整响应，改参 409。
     */
    public IdemOutcome createQuadratic(CreateQuadraticCertificateRequest request) {
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        String operator = Inputs.requireText(request.operator(), "operator");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operator", request.operator());
        params.put("instrumentId", request.instrumentId());
        params.put("validFrom", request.validFrom());
        params.put("validTo", request.validTo());
        params.put("a", request.a() == null ? null : tryDecimal(request.a()));
        params.put("b", request.b() == null ? null : tryDecimal(request.b()));
        params.put("c", request.c() == null ? null : tryDecimal(request.c()));
        params.put("domainLo", request.domainLo() == null ? null : tryDecimal(request.domainLo()));
        params.put("domainHi", request.domainHi() == null ? null : tryDecimal(request.domainHi()));
        params.put("expectedVersion", request.expectedVersion());
        return idempotency.execute(requestId, "CERTIFICATE_CREATE_QUADRATIC", operator,
                request.instrumentId() == null ? "" : request.instrumentId(), params,
                () -> new IdempotencyService.IdemResponse(201, doCreateQuadratic(request)));
    }

    /**
     * 二次证书创建的业务主体（在幂等事务内执行，失败整体回滚不占键）。
     */
    @Transactional
    public CertificateResponse doCreateQuadratic(CreateQuadraticCertificateRequest request) {
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant validFrom = Inputs.requireInstant(request.validFrom(), "validFrom");
        Instant validTo = Inputs.requireInstant(request.validTo(), "validTo");
        BigDecimal a = Inputs.requireDecimal(request.a(), "a");
        BigDecimal b = Inputs.requireDecimal(request.b(), "b");
        BigDecimal c = Inputs.requireDecimal(request.c(), "c");
        BigDecimal domainLo = Inputs.requireDecimal(request.domainLo(), "domainLo");
        BigDecimal domainHi = Inputs.requireDecimal(request.domainHi(), "domainHi");
        if (!validFrom.isBefore(validTo)) {
            throw ApiException.badRequest("validFrom 必须早于 validTo");
        }
        if (a.compareTo(BigDecimal.ZERO) == 0) {
            throw ApiException.badRequest("二次证书系数 a 必须非零");
        }
        if (domainLo.compareTo(domainHi) >= 0) {
            throw ApiException.badRequest("domainLo 必须小于 domainHi");
        }
        if (!Quadratics.strictlyMonotone(a, b, domainLo, domainHi)) {
            throw ApiException.badRequest("NON_MONOTONE_CERTIFICATE",
                    "导数在输入区间 [domainLo, domainHi] 内不是严格同号，证书非单调");
        }

        certificates.lockInstrument(instrumentId);
        if (certificates.countActiveOverlap(instrumentId, validFrom, validTo) > 0) {
            throw ApiException.conflict("CERTIFICATE_OVERLAP", "同一仪器存在区间重叠的未撤销证书");
        }
        long id = certificates.insert(instrumentId, validFrom, validTo, CertificateType.QUADRATIC,
                a, b, c, domainLo, domainHi, Instant.now());
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

    /** 幂等指纹用：可解析的十进制按值归一，无法解析时按原文本（后续校验会拒绝）。 */
    private static Object tryDecimal(String raw) {
        String trimmed = raw.trim();
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException ex) {
            return raw;
        }
    }
}
