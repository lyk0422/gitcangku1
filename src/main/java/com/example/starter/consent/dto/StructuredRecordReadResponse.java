package com.example.starter.consent.dto;

import java.util.Map;

import com.example.starter.consent.Purpose;

/**
 * 结构化记录读取响应：返回去除禁止字段后的投影及实际应用的限制清单版本。
 *
 * @param subjectKey         主体标识（合成字符串）
 * @param purpose            用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch              记录所属授权代次
 * @param recordKey          记录键
 * @param restrictionVersion 实际投影应用的限制清单版本，0 表示尚无限制清单
 * @param fields             允许读取的字段投影；全部被禁止时为空对象而不是旧载荷
 */
public record StructuredRecordReadResponse(String subjectKey, Purpose purpose, int epoch,
                                           String recordKey, int restrictionVersion,
                                           Map<String, String> fields) {
}
