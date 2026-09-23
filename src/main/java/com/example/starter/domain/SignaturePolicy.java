package com.example.starter.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一个签名信任策略版本的不可变视图。
 *
 * @param policyVersion 策略版本号（只增）
 * @param keyIds        可信 keyId 集合，按字典序升序，1～10 个
 * @param thresholdM    阈值 m，1≤m≤key 数
 * @param effectiveAt   生效时刻（UTC）
 * @param createdAt     发布时刻（UTC）
 */
public record SignaturePolicy(
        long policyVersion,
        List<String> keyIds,
        int thresholdM,
        Instant effectiveAt,
        Instant createdAt) {
}
