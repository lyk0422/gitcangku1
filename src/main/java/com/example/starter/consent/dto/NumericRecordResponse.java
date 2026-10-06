package com.example.starter.consent.dto;

import com.example.starter.consent.RecordKind;

/**
 * 研究记录读取响应：读取时重新鉴权（目标授权及完整祖先闭包均须有效）后返回值。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param epoch      记录所属授权代次
 * @param recordKey  记录键
 * @param kind       类型：NUMERIC 数值 / DERIVED 派生
 * @param value      数值或派生求和结果，0 至 long 最大值
 */
public record NumericRecordResponse(String subjectKey, int epoch, String recordKey, RecordKind kind, long value) {
}
