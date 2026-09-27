package com.example.starter.firmware.domain;

/**
 * 回执复核标记：CERTIFICATE_REVOKED_REVIEW 证书撤销后收到的回执，待人工复核，
 * 不自动更新设备版本；REVIEW_CONFIRMED 人工已确认，版本更新完成。
 */
public enum ReviewStatus {
    CERTIFICATE_REVOKED_REVIEW,
    REVIEW_CONFIRMED
}
