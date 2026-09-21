package com.example.starter.calibration.service;

import com.example.starter.calibration.domain.Certificate;
import com.example.starter.calibration.error.ApiException;
import com.example.starter.calibration.repository.CertificateRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 校准证书服务：创建（区间不重叠）、撤销、查询。
 */
@Service
public class CertificateService {

    private final CertificateRepository certificateRepository;
    private final Clock clock;

    public CertificateService(CertificateRepository certificateRepository, Clock clock) {
        this.certificateRepository = certificateRepository;
        this.clock = clock;
    }

    /**
     * 创建证书。区间必须 validFrom &lt; validTo；与同仪器未撤销证书区间重叠时返回 409。
     * 并发创建重叠证书时由仓库层行锁保证最多一张成功。
     */
    @Transactional
    public Certificate create(
            String instrumentId,
            Instant validFrom,
            Instant validTo,
            BigDecimal coefficientA,
            BigDecimal offsetB) {
        InputValidation.requireNonBlank("instrumentId", instrumentId);
        if (!validFrom.isBefore(validTo)) {
            throw ApiException.badRequest(
                    "INVALID_INTERVAL", "validFrom 必须早于 validTo（区间左闭右开）");
        }
        Certificate certificate = new Certificate(
                0L, instrumentId, validFrom, validTo, coefficientA, offsetB, false, null, Instant.now(clock));
        return certificateRepository.insertIfNoOverlap(certificate);
    }

    /**
     * 撤销证书。不存在返回 404；已撤销返回 409。
     * 撤销与批量放行通过对证书行加锁按事务提交顺序串行化。
     */
    @Transactional
    public Certificate revoke(long id) {
        Certificate certificate = certificateRepository.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("CERTIFICATE_NOT_FOUND", "证书不存在: " + id));
        if (certificate.revoked()) {
            throw ApiException.conflict("CERTIFICATE_ALREADY_REVOKED", "证书已撤销: " + id);
        }
        Instant revokedAt = Instant.now(clock);
        certificateRepository.revoke(id, revokedAt);
        return new Certificate(
                certificate.id(),
                certificate.instrumentId(),
                certificate.validFrom(),
                certificate.validTo(),
                certificate.coefficientA(),
                certificate.offsetB(),
                true,
                revokedAt,
                certificate.createdAt());
    }

    /**
     * 按 ID 查询证书，不存在返回 404。
     */
    @Transactional(readOnly = true)
    public Certificate get(long id) {
        return certificateRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("CERTIFICATE_NOT_FOUND", "证书不存在: " + id));
    }

    /**
     * 查询某仪器的全部证书。
     */
    @Transactional(readOnly = true)
    public List<Certificate> listByInstrument(String instrumentId) {
        InputValidation.requireNonBlank("instrumentId", instrumentId);
        return certificateRepository.findByInstrument(instrumentId);
    }
}
