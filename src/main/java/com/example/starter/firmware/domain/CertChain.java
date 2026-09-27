package com.example.starter.firmware.domain;

/**
 * 证书链记录。有效区间为闭开区间 [validFrom, validTo)：validFrom 时刻起可用，validTo 时刻起不可用。
 *
 * @param id             证书链ID
 * @param model          固件适用设备型号
 * @param firmwareVersion 所属固件版本
 * @param chainVersion   链版本号，同一固件版本内从1递增
 * @param fingerprint    证书链指纹（合成业务标识）
 * @param validFromUtc   生效时刻，UTC，ISO-8601
 * @param validToUtc     失效时刻，UTC，ISO-8601；null 表示开放区间
 * @param status         ACTIVE / REVOKED
 * @param revokedAtUtc   撤销时刻UTC；未撤销为 null
 * @param revokeReason   撤销原因；未撤销为 null
 * @param createdBy      登记或轮换操作者
 */
public record CertChain(long id, String model, String firmwareVersion, int chainVersion, String fingerprint,
                        String validFromUtc, String validToUtc, CertChainStatus status,
                        String revokedAtUtc, String revokeReason, String createdBy) {
}
