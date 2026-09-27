package com.example.starter.firmware.domain;

/**
 * 回执证书复核状态：
 * CERTIFICATE_REVOKED_REVIEW 任务固化的证书链在回执时已撤销，待人工复核，不自动更新设备版本；
 * CONFIRMED 人工确认，SUCCESS 回执据此完成设备版本更新；
 * REJECTED 人工拒绝，永不更新设备版本。
 * 未纳管证书或链未撤销的任务该字段为 null。
 */
public enum CertReviewStatus {
    CERTIFICATE_REVOKED_REVIEW,
    CONFIRMED,
    REJECTED
}
