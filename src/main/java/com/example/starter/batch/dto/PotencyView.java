package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次效价模型视图（当前最新版本）。未登记效价模型的批次该对象为 null。
 *
 * @param version           模型版本号，从 1 开始
 * @param quantity          批次数量（无量纲）
 * @param initialPotency    该版本初始效价
 * @param anchorAt          衰减锚点 UTC instant（根批为生产时间，拆分/合批新批为操作固化时刻）
 * @param dailyDecayRate    线性日衰减率（效价单位/UTC 日）
 * @param minReleasePotency 最低放行效价阈值
 */
public record PotencyView(
        int version,
        BigDecimal quantity,
        BigDecimal initialPotency,
        Instant anchorAt,
        BigDecimal dailyDecayRate,
        BigDecimal minReleasePotency
) {
}
