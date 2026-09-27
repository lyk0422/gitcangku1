package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 驻留迁移响应：成功时要求数量与实际复制数量相等；失败（422）时不产生任何副本。
 *
 * @param subjectKey     主体标识（合成字符串）
 * @param purpose        用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param sourceEpoch    源授权代次（已撤回）
 * @param targetEpoch    新授权代次
 * @param sourceRegion   源驻留区域代码
 * @param targetRegion   目标驻留区域代码
 * @param requestedCount 要求迁移的对象数量
 * @param copiedCount    实际复制到新代次的对象数量
 */
public record MigrateResponse(String subjectKey, Purpose purpose, int sourceEpoch, int targetEpoch,
                              String sourceRegion, String targetRegion,
                              int requestedCount, int copiedCount) {
}
