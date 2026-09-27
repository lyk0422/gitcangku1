package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.CertChain;

/**
 * 证书链视图。revokedAtUtc 为 null 表示未撤销。
 */
public record CertChainView(long chainId, String firmwareVersion, String chainVersion,
                            String validFromUtc, String validToUtc, String operator,
                            String revokedAtUtc) {

    public static CertChainView of(CertChain chain) {
        return new CertChainView(chain.id(), chain.firmwareVersion(), chain.chainVersion(),
                chain.validFromUtc(), chain.validToUtc(), chain.operator(), chain.revokedAtUtc());
    }
}
