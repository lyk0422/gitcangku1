package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 不确定度预算修订历史：每次分量批量修改固化一条，含当时分量快照、合成不确定度与计算口径。
 *
 * @param id                  修订 ID（自增）
 * @param measurementId       测量记录 ID
 * @param version             修订后的测量版本号
 * @param combinedUncertainty 该版本合成不确定度（RSS，HALF_UP 4 位小数）
 * @param formula             计算口径标识
 * @param changedBy           修改人（X-Actor-Id）
 * @param requestId           本次修改的幂等请求 ID
 * @param createdAt           修订时间（UTC）
 * @param components          该版本的分量快照（按名称排序）
 */
public record BudgetRevision(
        long id,
        long measurementId,
        int version,
        BigDecimal combinedUncertainty,
        String formula,
        String changedBy,
        String requestId,
        Instant createdAt,
        List<UncertaintyComponent> components) {
}
