package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 保留记录计数响应：指定代次当前被保留（未物理清除）的记录数。
 *
 * @param subjectKey          主体标识（合成字符串）
 * @param purpose             用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch               授权代次
 * @param retainedRecordCount 该代次现存记录数，已物理清除后为 0
 */
public record RetainedCountResponse(
        String subjectKey,
        Purpose purpose,
        int epoch,
        long retainedRecordCount) {
}
