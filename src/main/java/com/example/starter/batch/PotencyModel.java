package com.example.starter.batch;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批次效价模型的一个不可变版本。
 *
 * <p>线性模型：{@code current(t) = initialPotency - dailyDecayRate * elapsedUtcDays(anchor, t)}，
 * 时间区间左闭右开（锚点当天经过 0 天）；修订只追加新版本，历史固化结论绑定 version 不改写。
 *
 * @param version           模型版本号，从 1 开始
 * @param initialPotency    初始效价（无量纲效价单位），非负
 * @param anchor            衰减锚点 UTC instant：根批为生产时间，拆分/合批新批为操作固化时刻
 * @param dailyDecayRate    线性日衰减率（效价单位/UTC 日），非负，可为 0
 * @param minReleasePotency 最低放行效价阈值，非负
 * @param commandKey        产生该版本的命令幂等键（登记或模型修订）
 * @param createdAt         版本固化时间
 */
public record PotencyModel(int version,
                           BigDecimal initialPotency,
                           Instant anchor,
                           BigDecimal dailyDecayRate,
                           BigDecimal minReleasePotency,
                           String commandKey,
                           Instant createdAt) {
}
