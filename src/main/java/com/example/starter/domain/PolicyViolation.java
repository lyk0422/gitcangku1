package com.example.starter.domain;

/**
 * 一条来源策略违规诊断。
 *
 * @param code       可区分原因：MISSING_ATTESTATION / ATTESTATION_REVOKED /
 *                   REPO_NOT_ALLOWED / DIGEST_MISMATCH / LEVEL_INSUFFICIENT
 * @param coordinate 违规坐标，如 lib:2
 * @param path       从根制品到违规坐标的完整依赖路径，如 app:1&gt;lib:2&gt;util:1
 * @param message    人类可读的违规说明
 */
public record PolicyViolation(
        String code,
        String coordinate,
        String path,
        String message) {
}
