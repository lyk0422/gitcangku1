package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 字段级限制响应：返回限制清单所属主体、用途、代次、版本与禁止字段集合。
 *
 * @param subjectKey         主体标识（合成字符串）
 * @param purpose            用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch              限制所属授权代次
 * @param restrictionVersion 限制清单版本，从 1 开始递增
 * @param restrictedFields   禁止字段名集合（升序去重），空表示不禁止任何字段
 */
public record FieldRestrictionResponse(String subjectKey, Purpose purpose, int epoch,
                                       int restrictionVersion, List<String> restrictedFields) {
}
