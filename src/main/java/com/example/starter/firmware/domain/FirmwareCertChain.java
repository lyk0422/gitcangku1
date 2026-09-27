package com.example.starter.firmware.domain;

import java.time.Instant;

/**
 * 固件签名证书链。所有时刻均为 UTC，有效期为半开区间 [validFrom, validTo)：
 * 请求时刻 t 满足 validFrom &lt;= t &lt; validTo（validTo 为 null 视为正无穷）时链在有效期内。
 *
 * @param id                证书链ID
 * @param firmwareId        所属固件版本ID
 * @param chainVersion      链版本号，同一固件内从1开始递增
 * @param subjectDn         签名证书主题（合成元数据）
 * @param issuerDn          签发者主题（合成元数据）
 * @param fingerprintSha256 签名证书 SHA-256 指纹（十六进制合成值）
 * @param validFrom         生效时刻（含）
 * @param validTo           到期时刻（不含）；null 表示开放区间，撤销或被轮换截断时写入
 * @param status            ACTIVE / REVOKED
 * @param revokedAt         撤销时刻；未撤销为 null
 */
public record FirmwareCertChain(long id, long firmwareId, int chainVersion, String subjectDn,
                                String issuerDn, String fingerprintSha256,
                                Instant validFrom, Instant validTo, CertChainStatus status,
                                Instant revokedAt) {

    /**
     * 链在给定 UTC 时刻是否处于有效半开区间内（与状态无关；已撤销链即便时刻落在区间内也无效）。
     */
    public boolean covers(Instant at) {
        return !at.isBefore(validFrom) && (validTo == null || at.isBefore(validTo));
    }
}
