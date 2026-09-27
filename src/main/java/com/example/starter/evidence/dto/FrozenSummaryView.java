package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 包级冻结摘要：建包时固化，之后迁移、复核或新增链记录均不得改写。
 *
 * @param caseKey       所属案件键
 * @param packageVersion 包版本号
 * @param asOfUtc       截至 UTC 时刻
 * @param actorId       生成操作人
 * @param itemCount     证物件数
 * @param generatedAt   生成时间（Asia/Shanghai）
 * @param items         逐件冻结摘要，按规范件次排序
 */
public record FrozenSummaryView(
        String caseKey,
        int packageVersion,
        LocalDateTime asOfUtc,
        String actorId,
        int itemCount,
        LocalDateTime generatedAt,
        List<FrozenItemDigest> items) {
}
