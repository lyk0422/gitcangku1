package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.CertChain;

/**
 * 签名证书链视图。validToUtc 为 null 表示开放区间（最新链）。
 */
public record CertChainView(long chainId, String model, String firmwareVersion, int chainVersion,
                            String certFingerprint, String validFromUtc, String validToUtc,
                            String status, String revokedAtUtc, String revokeReason, String createdBy,
                            boolean current) {

    public static CertChainView of(CertChain chain, boolean current) {
        return new CertChainView(chain.id(), chain.model(), chain.firmwareVersion(), chain.chainVersion(),
                chain.fingerprint(), chain.validFromUtc(), chain.validToUtc(), chain.status().name(),
                chain.revokedAtUtc(), chain.revokeReason(), chain.createdBy(), current);
    }
}
