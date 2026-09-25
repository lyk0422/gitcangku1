package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.repo.CertificateRepository;

/**
 * 标准器证书引用解析：把一次测量的引用（显式标准器+证书版本，或按仪器自动匹配）
 * 解析为测量时刻确定有效的证书，并对“不存在 / 已撤销 / 已到期 / 未生效”返回可区分错误码。
 */
@Component
public class CertificateResolver {

    private final CertificateRepository certificates;

    public CertificateResolver(CertificateRepository certificates) {
        this.certificates = certificates;
    }

    /**
     * 解析测量在 measuredAt 时刻应引用的证书。
     *
     * @param instrumentId       仪器/被测对象 ID（自动匹配时作为标准器 ID）
     * @param explicitStandardId 显式标准器 ID；null 表示自动匹配
     * @param explicitVersion    显式证书版本；与 explicitStandardId 同时出现
     * @param measuredAt         测量时刻（UTC）
     */
    public Certificate resolve(String instrumentId, String explicitStandardId, String explicitVersion,
                               Instant measuredAt) {
        boolean explicit = explicitStandardId != null || explicitVersion != null;
        if (explicit) {
            if (explicitStandardId == null || explicitVersion == null) {
                throw ApiException.badRequest("standardId 与 certificateVersion 必须同时提供");
            }
            Certificate cert = certificates
                    .findByStandardAndVersion(explicitStandardId, explicitVersion)
                    .orElseThrow(() -> ApiException.unprocessable("CERTIFICATE_NOT_FOUND",
                            "标准器证书版本不存在: " + explicitStandardId + "@" + explicitVersion));
            return requireValidAt(cert, measuredAt);
        }

        // 自动匹配：以仪器 ID 作为标准器 ID，按测量时刻匹配有效证书；
        // 多张有效（换版重叠）时判为歧义，要求显式提供 standardId+certificateVersion。
        List<Certificate> matches = certificates.findAllMatching(instrumentId, measuredAt);
        if (matches.isEmpty()) {
            throw ApiException.unprocessable("NO_MATCHING_CERTIFICATE",
                    "测量时刻无匹配的有效证书: instrument=" + instrumentId);
        }
        if (matches.size() > 1) {
            throw ApiException.unprocessable("CERTIFICATE_AMBIGUOUS",
                    "测量时刻存在多张有效标准器证书，必须显式指定 certificateVersion: instrument="
                            + instrumentId);
        }
        return matches.get(0);
    }

    /**
     * 校验证书在测量时刻有效（左闭右开，端点到期即无效），并返回可区分原因。
     */
    public Certificate requireValidAt(Certificate cert, Instant measuredAt) {
        if (cert.revoked()) {
            throw ApiException.unprocessable("CERTIFICATE_REVOKED",
                    "标准器证书已撤销: " + cert.standardId() + "@" + cert.version());
        }
        if (measuredAt.isBefore(cert.validFrom())) {
            throw ApiException.unprocessable("CERTIFICATE_NOT_YET_VALID",
                    "标准器证书在测量时刻尚未生效: " + cert.standardId() + "@" + cert.version());
        }
        if (!measuredAt.isBefore(cert.validTo())) {
            throw ApiException.unprocessable("CERTIFICATE_EXPIRED",
                    "标准器证书在测量时刻已到期（端点到期即无效）: "
                            + cert.standardId() + "@" + cert.version());
        }
        return cert;
    }
}
