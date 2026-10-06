package com.example.starter.consent.dto;

import com.example.starter.consent.RecordKind;

/**
 * 数值记录读取响应：重新鉴权（含派生完整祖先闭包核验）后返回记录值。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param epoch      记录所属研究授权代次
 * @param recordKey  记录键
 * @param kind       记录类型：VALUE 数值 / DERIVED 派生求和
 * @param value      数值（0 至 9223372036854775807），派生记录为精确求和结果
 */
public record NumericRecordResponse(String subjectKey, int epoch, String recordKey,
                                    RecordKind kind, long value) {
}
