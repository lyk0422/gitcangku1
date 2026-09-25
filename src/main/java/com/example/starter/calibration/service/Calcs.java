package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;

/**
 * 领域计算与指纹工具：补偿计算、合格判定、扩展不确定度、referenceKey 与批量指纹。
 * 全部为确定性纯函数，不依赖时钟，便于并发与幂等裁决。
 */
final class Calcs {

    /** 扩展不确定度覆盖因子。 */
    static final BigDecimal COVERAGE_FACTOR = new BigDecimal("2");

    private Calcs() {
    }

    /**
     * 未舍入补偿计算值：a × 读数 + b。
     */
    static BigDecimal computedValue(BigDecimal a, BigDecimal b, BigDecimal reading) {
        return a.multiply(reading).add(b);
    }

    /**
     * 扩展不确定度（k=2）：2 × 标准器标准不确定度。
     */
    static BigDecimal expandedUncertainty(BigDecimal standardUncertainty) {
        return standardUncertainty.multiply(COVERAGE_FACTOR);
    }

    /**
     * 合格判定：未舍入计算值落在 [lower, upper]（含端点）。
     */
    static boolean isPassed(BigDecimal computed, BigDecimal lower, BigDecimal upper) {
        return computed.compareTo(lower) >= 0 && computed.compareTo(upper) <= 0;
    }

    /**
     * referenceKey 指纹（64 位十六进制 SHA-256）。
     *
     * <p>规范内容包含：测量版本号、标准器与证书版本、测量时刻（UTC）、被测仪器、
     * 原始读数、上下限、提交人。任一要素变化即产生不同键；同要素重放得到同键。
     */
    static String referenceKey(int versionNo, String standardId, String certificateVersion,
                               Instant measuredAt, String instrumentId, BigDecimal reading,
                               BigDecimal lower, BigDecimal upper, String submittedBy) {
        String canonical = String.join("|",
                "v=" + versionNo,
                "std=" + standardId,
                "cert=" + certificateVersion,
                "at=" + measuredAt,
                "ins=" + instrumentId,
                "read=" + reading.stripTrailingZeros().toPlainString(),
                "lo=" + lower.stripTrailingZeros().toPlainString(),
                "hi=" + upper.stripTrailingZeros().toPlainString(),
                "by=" + submittedBy);
        return sha256(canonical);
    }

    /**
     * 批量提交指纹：对按顺序排列的“最终引用 + 输入”规范串整体取 SHA-256，
     * 用于同 batchId 重放时识别载荷是否一致。
     */
    static String batchFingerprint(List<String> canonicalItems) {
        return sha256(String.join("\n", canonicalItems));
    }

    /**
     * 单条批量提交项的规范串：测量键、标准器/证书版本、时刻与全部输入，
     * 项内字段相同即视为同一最终引用与输入。
     */
    static String batchItemCanonical(String measurementKey, String standardId, String certificateVersion,
                                     Instant measuredAt, String instrumentId, BigDecimal reading,
                                     BigDecimal lower, BigDecimal upper, String submittedBy) {
        return String.join("|",
                "key=" + measurementKey,
                "std=" + standardId,
                "cert=" + certificateVersion,
                "at=" + measuredAt,
                "ins=" + instrumentId,
                "read=" + reading.stripTrailingZeros().toPlainString(),
                "lo=" + lower.stripTrailingZeros().toPlainString(),
                "hi=" + upper.stripTrailingZeros().toPlainString(),
                "by=" + submittedBy);
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
