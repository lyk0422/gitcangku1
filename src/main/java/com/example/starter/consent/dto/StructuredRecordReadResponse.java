package com.example.starter.consent.dto;

import java.util.Map;

import com.example.starter.consent.Purpose;

/**
 * 结构化记录读取响应：返回去除禁止字段后的投影与所依据的限制清单版本。
 * 全部被禁止时 fields 为空对象而不是旧载荷。
 *
 * @param subjectKey         主体标识（合成字符串）
 * @param purpose            用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch              记录所属授权代次
 * @param recordKey          记录键
 * @param restrictionVersion 本次投影实际依据的限制清单版本，未设置任何限制为 0
 * @param fields             允许读取的字段投影，禁止字段不出现
 */
public record StructuredRecordReadResponse(
        String subjectKey, Purpose purpose, int epoch, String recordKey,
        int restrictionVersion, Map<String, String> fields) {
}
