package com.example.starter.consent.dto;

/**
 * 批量查询失败项：仅包含输入索引与稳定错误码，不含任何记录内容。
 *
 * @param index 失败项在请求 items 中的下标（从 0 开始）
 * @param code  稳定业务错误码
 */
public record BatchItemFailure(int index, String code) {
}
