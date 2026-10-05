package com.example.starter.consent.dto;

/**
 * 结构化记录写入响应：只返回记录标识与版本元数据，不携带字段内容。
 *
 * @param recordKey          记录键（记录标识）
 * @param epoch              记录所属授权代次
 * @param restrictionVersion 写入时当前字段限制清单版本，0 表示尚无限制清单
 */
public record StructuredRecordWriteResponse(String recordKey, int epoch, int restrictionVersion) {
}
