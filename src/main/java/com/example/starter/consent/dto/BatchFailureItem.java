package com.example.starter.consent.dto;

/**
 * 批量查询失败项：仅包含失败项在输入中的索引与稳定错误码，不含任何记录内容。
 *
 * @param index 失败项在请求 items 中的下标（从 0 开始）
 * @param code  稳定错误码，如 GRANT_NOT_FOUND / RECORD_NOT_FOUND / CONSENT_REVOKED / EPOCH_SUPERSEDED / EPOCH_AHEAD
 */
public record BatchFailureItem(int index, String code) {
}
