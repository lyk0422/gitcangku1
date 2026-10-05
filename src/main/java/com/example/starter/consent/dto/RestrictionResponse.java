package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 字段级限制替换响应：返回新生效的限制清单版本与禁止字段集合。
 *
 * @param subjectKey         主体标识（合成字符串）
 * @param purpose            用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch              限制所属授权代次
 * @param restrictionVersion 新生效的限制清单版本，从 1 开始递增
 * @param forbiddenFields    禁止字段名集合（按字段名排序去重），可为空列表
 */
public record RestrictionResponse(
        String subjectKey, Purpose purpose, int epoch,
        int restrictionVersion, List<String> forbiddenFields) {
}
