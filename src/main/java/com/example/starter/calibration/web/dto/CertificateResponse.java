package com.example.starter.calibration.web.dto;

import com.example.starter.calibration.domain.Certificate;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 证书响应。时间均为 UTC ISO-8601。
 */
public record CertificateResponse(
        long id,
        String instrumentId,
        Instant validFrom,
        Instant validTo,
        BigDecimal a,
        BigDecimal b,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {

    public static CertificateResponse from(Certificate certificate) {
        return new CertificateResponse(
                certificate.id(),
                certificate.instrumentId(),
                certificate.validFrom(),
                certificate.validTo(),
                certificate.coefficientA(),
                certificate.offsetB(),
                certificate.revoked(),
                certificate.revokedAt(),
                certificate.createdAt());
    }
}
