package com.example.starter.api.dto;

import java.util.List;

/**
 * 应急走廊创建/撤销结果。
 *
 * @param corridorId       走廊标识
 * @param status           操作后状态：ACTIVE / REVOKED
 * @param priority         走廊优先级
 * @param airspaceVersion  走廊版本：操作后生效的全局空域版本
 * @param startUtc         生效起点 epoch 毫秒（UTC，含）；撤销响应仍原样返回
 * @param endUtc           生效终点 epoch 毫秒（UTC，不含）
 * @param durationMillis   要求时长（毫秒）= endUtc - startUtc
 * @param yieldedCount     实际让渡占用数（撤销响应为 0）
 * @param yields           本次让渡明细（实际值）；撤销响应为空列表
 */
public record CorridorResult(String corridorId, String status, int priority,
                             long airspaceVersion, long startUtc, long endUtc,
                             long durationMillis, int yieldedCount,
                             List<YieldOutcomeDto> yields) {
}
