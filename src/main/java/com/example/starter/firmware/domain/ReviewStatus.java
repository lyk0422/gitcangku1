package com.example.starter.firmware.domain;

/**
 * 任务复核状态：NONE 无需复核；CERTIFICATE_REVOKED_REVIEW 固化证书链已撤销，回执待人工复核，
 * 不自动更新设备版本；CONFIRMED 已人工确认并完成版本更新。
 */
public enum ReviewStatus {
    NONE,
    CERTIFICATE_REVOKED_REVIEW,
    CONFIRMED
}
