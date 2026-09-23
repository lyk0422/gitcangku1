package com.example.starter.consent.catalog.dto;

import java.util.List;

/**
 * 迁移证据响应：只读，明细稳定按 ordinal 排序。
 *
 * @param migrationKey      迁移键
 * @param catalogGeneration 迁移发布的目录代次
 * @param sourcePurpose     被拆分旧用途代码
 * @param requestId         激活请求标识
 * @param activatedAt       激活提交时间（UTC）
 * @param items             稳定排序的证据明细
 */
public record MigrationEvidenceResponse(String migrationKey, int catalogGeneration, String sourcePurpose,
                                        String requestId, java.time.Instant activatedAt,
                                        List<EvidenceItem> items) {

    /**
     * 证据明细。
     *
     * @param itemType       GRANT_ACTIVE / GRANT_REVOKED / RECORD
     * @param subjectKey     主体标识
     * @param oldPurpose     迁移前用途
     * @param oldEpoch       迁移前代次
     * @param newPurpose     迁移后用途；UNMAPPED/RETAINED 与撤回授权为空
     * @param newEpoch       迁移后新用途授权代次；未新建为空
     * @param recordKey      记录键；授权明细为空
     * @param attributeValue 记录属性取值；授权明细为空
     * @param mappingResult  MAPPED / UNMAPPED / RETAINED；授权明细为 MAPPED
     */
    public record EvidenceItem(String itemType, String subjectKey, String oldPurpose, int oldEpoch,
                               String newPurpose, Integer newEpoch, String recordKey,
                               String attributeValue, MappingResult mappingResult) {
    }
}
