package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 批次效价模型登记/修订请求。
 *
 * <p>initialPotency 为登记时刻初始效价；minPotency 为最低放行效价阈值；
 * decayRate 为按 UTC 日连续计时的线性衰减率（效价/日）。三者均不允许为负，
 * 负值由业务层返回 422（PRECONDITION_FAILED），以区别于请求体格式错误。
 * 生效（计算基准）时刻取服务端可注入时钟的当前 UTC 时刻，调用方不可指定。
 */
public record SetPotencyModelRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "initialPotency 不能为空") BigDecimal initialPotency,
        @NotNull(message = "minPotency 不能为空") BigDecimal minPotency,
        @NotNull(message = "decayRate 不能为空") BigDecimal decayRate
) {
}
