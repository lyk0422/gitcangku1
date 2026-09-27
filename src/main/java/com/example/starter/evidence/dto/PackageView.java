package com.example.starter.evidence.dto;

import com.example.starter.evidence.PackageStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 披露包版本视图：包元数据、冻结摘要与逐件固化内容；交付/撤销记录为空时对应字段为 null。
 *
 * @param caseKey        所属案件键
 * @param packageVersion 包版本号
 * @param asOfUtc        截至 UTC 时刻
 * @param status         版本状态：GENERATED / DELIVERED / REVOKED
 * @param packageKey     包指纹
 * @param actorId        生成操作人
 * @param itemCount      证物件数
 * @param frozenSummary  冻结摘要
 * @param items          逐件固化内容，按规范件次排序
 * @param deliveredBy    交付操作人；null 表示未交付
 * @param deliveredAt    交付时间（Asia/Shanghai）；null 表示未交付
 * @param deliverReason  交付备注；null 表示未交付
 * @param revokedBy      撤销操作人；null 表示未撤销
 * @param revokedAt      撤销时间（Asia/Shanghai）；null 表示未撤销
 * @param revokeReason   撤销原因；null 表示未撤销
 * @param createdAt      版本生成时间（Asia/Shanghai）
 */
public record PackageView(
        String caseKey,
        int packageVersion,
        LocalDateTime asOfUtc,
        PackageStatus status,
        String packageKey,
        String actorId,
        int itemCount,
        FrozenSummaryView frozenSummary,
        List<PackageItemView> items,
        String deliveredBy,
        LocalDateTime deliveredAt,
        String deliverReason,
        String revokedBy,
        LocalDateTime revokedAt,
        String revokeReason,
        LocalDateTime createdAt) {
}
