package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 放行效价不合格（422）诊断体：返回实际值、要求值与差额（actual - required，负数为缺口）。
 * 该结论在命令事务内计算，失败时整事务回滚、不占 commandKey、不留固化记录。
 */
public record PotencyRejectionResponse(
        String code,
        String message,
        String batchKey,
        int modelVersion,
        Instant calculatedAt,
        BigDecimal actualPotency,
        BigDecimal requiredPotency,
        BigDecimal margin
) {
}
