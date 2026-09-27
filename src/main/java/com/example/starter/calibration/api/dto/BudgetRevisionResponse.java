package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 预算修订历史响应：每次分量批量修改固化一条。
 *
 * @param version             修订后的测量版本号
 * @param combinedUncertainty 该版本合成不确定度（RSS，HALF_UP 4 位小数）
 * @param formula             计算口径标识
 * @param changedBy           修改人
 * @param requestId           本次修改的幂等请求 ID
 * @param createdAt           修订时间（UTC）
 * @param components          该版本分量快照（按名称升序）
 */
public record BudgetRevisionResponse(int version, String combinedUncertainty, String formula,
                                     String changedBy, String requestId, Instant createdAt,
                                     List<ComponentView> components) {
}
