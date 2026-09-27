package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.CertChain;

import java.time.Instant;
import java.util.List;

/**
 * 证书链视图。current 表示该链在响应时刻为当前唯一有效链（未撤销且区间覆盖）。
 */
public record CertChainView(String firmwareVersion, String chainVersion, String validFromUtc,
                            String validToUtc, List<String> deviceSet, String operator,
                            boolean revoked, String revokedAtUtc, boolean current) {

    public static CertChainView of(CertChain chain, Instant now) {
        return new CertChainView(chain.firmwareVersion(), chain.chainVersion(),
                chain.validFromUtc().toString(),
                chain.validToUtc() == null ? null : chain.validToUtc().toString(),
                chain.deviceSet(), chain.operator(), chain.revoked(),
                chain.revokedAtUtc() == null ? null : chain.revokedAtUtc().toString(),
                chain.validAt(now));
    }
}
