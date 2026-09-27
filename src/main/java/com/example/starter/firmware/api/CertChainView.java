package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.FirmwareCertChain;

import java.util.List;

/**
 * 证书链视图。validFromUtc/validToUtc 为 UTC 半开区间端点，validToUtc 为 null 表示开放区间。
 * deviceIds 为链授权设备集合（空列表表示全开放）。
 */
public record CertChainView(long chainId, String model, String firmwareVersion, int chainVersion,
                            String subjectDn, String issuerDn, String fingerprintSha256,
                            String validFromUtc, String validToUtc, String status,
                            String revokedAtUtc, List<String> deviceIds) {

    public static CertChainView of(FirmwareCertChain chain, String model, String firmwareVersion,
                                   List<String> deviceIds) {
        return new CertChainView(chain.id(), model, firmwareVersion, chain.chainVersion(),
                chain.subjectDn(), chain.issuerDn(), chain.fingerprintSha256(),
                chain.validFrom().toString(),
                chain.validTo() == null ? null : chain.validTo().toString(),
                chain.status().name(),
                chain.revokedAt() == null ? null : chain.revokedAt().toString(),
                List.copyOf(deviceIds));
    }
}
