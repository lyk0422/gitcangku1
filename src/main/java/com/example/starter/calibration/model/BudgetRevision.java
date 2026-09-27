package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 不确定度预算修订历史。每次批量修改分量追加一条，旧修订不改写。
 *
 * @param id                  预算修订 ID（自增）
 * @param measurementId       测量记录 ID
 * @param measurementVersion  修订后的测量版本
 * @param combinedUncertainty 修订后合成不确定度（4 位小数）
 * @param thresholdVersion    修订时仪器阈值版本
 * @param componentsSnapshot  修订后分量快照（JSON 数组，按分量名称排序）
 * @param changedBy           修改人
 * @param requestId           幂等请求 ID
 * @param createdAt           修订时间（UTC）
 */
public record BudgetRevision(
        long id,
        long measurementId,
        int measurementVersion,
        BigDecimal combinedUncertainty,
        int thresholdVersion,
        String componentsSnapshot,
        String changedBy,
        String requestId,
        Instant createdAt) {
}
