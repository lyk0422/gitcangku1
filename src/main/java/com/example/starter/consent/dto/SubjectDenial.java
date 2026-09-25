package com.example.starter.consent.dto;

/**
 * 批次阻断中单个主体的原因。
 *
 * @param subjectKey 主体标识
 * @param code       稳定业务码，如 DELEGATE_NOT_FOUND / DELEGATE_EXPIRED / DELEGATE_EPOCH_STALE
 * @param message    可读描述
 */
public record SubjectDenial(
        String subjectKey,
        String code,
        String message) {
}
