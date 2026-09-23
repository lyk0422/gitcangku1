package com.example.starter.domain;

import java.time.Instant;
import java.util.List;

/**
 * 签名信任策略的不可变快照。
 *
 * @param policyVersion 策略版本号，单调递增正数
 * @param keyIds        可信钥匙标识清单（1～10 个，发布时规范化为升序去重）
 * @param threshold     可信签名阈值 m，1 <= m <= keyIds 数量
 * @param effectiveAt   生效时刻（UTC），仅 effectiveAt <= 解析时刻的策略可被选中
 */
public record SigningPolicy(
        long policyVersion,
        List<String> keyIds,
        int threshold,
        Instant effectiveAt) {
}
