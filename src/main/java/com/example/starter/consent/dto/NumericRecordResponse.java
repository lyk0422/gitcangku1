package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import com.example.starter.consent.RecordType;

/**
 * 数值记录写入响应：仅回元数据，不包含写入的数值本身。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      记录所属授权代次
 * @param recordKey  记录键
 * @param recordType 记录类型，固定为 NUMBER
 */
public record NumericRecordResponse(
        String subjectKey, Purpose purpose, int epoch, String recordKey, RecordType recordType) {
}
