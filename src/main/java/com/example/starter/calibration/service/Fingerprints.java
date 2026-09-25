package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * referenceKey 指纹：测量版本、证书版本、测量时刻与输入摘要的 SHA-256。
 * 同键重放时指纹一致才视为同一请求；指纹不同则为 409 冲突。
 */
final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * 计算提交指纹。
     *
     * @param measurementVersion 测量版本（提交时恒为 1）
     * @param certVersion        匹配到的证书版本
     * @param measuredAt         测量时刻（UTC）
     * @param instrumentId       仪器 ID
     * @param reading            原始读数
     * @param lowerLimit         合格下限
     * @param upperLimit         合格上限
     * @param submittedBy        提交人
     * @param certificateId      匹配到的证书 ID
     */
    static String of(int measurementVersion, String certVersion, Instant measuredAt,
                     String instrumentId, BigDecimal reading, BigDecimal lowerLimit,
                     BigDecimal upperLimit, String submittedBy, long certificateId) {
        String inputDigest = sha256(String.join("|",
                instrumentId,
                reading.stripTrailingZeros().toPlainString(),
                lowerLimit.stripTrailingZeros().toPlainString(),
                upperLimit.stripTrailingZeros().toPlainString(),
                submittedBy,
                String.valueOf(certificateId)));
        return sha256(measurementVersion + "|" + certVersion + "|" + measuredAt + "|" + inputDigest);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
