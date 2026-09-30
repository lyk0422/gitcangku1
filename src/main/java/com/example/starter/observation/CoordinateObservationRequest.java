package com.example.starter.observation;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 坐标观测写入请求：观测携带原基准、原坐标与观测时刻（UTC）。
 *
 * <p>服务端按观测时刻选择生效区间左闭右开的唯一规则；同一 requestId 重放返回首次结果，
 * 修改原基准或坐标（以及观测时刻）一律 409。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param observationId 坐标观测业务唯一标识
 * @param sourceFrame   原坐标基准标识
 * @param originalX     原坐标 X（原基准长度单位）
 * @param originalY     原坐标 Y（原基准长度单位）
 * @param observedAtUtc 观测时刻（UTC）
 */
public record CoordinateObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotBlank @Size(max = 64) String sourceFrame,
        @NotNull @Digits(integer = 9, fraction = 9) BigDecimal originalX,
        @NotNull @Digits(integer = 9, fraction = 9) BigDecimal originalY,
        @NotNull Instant observedAtUtc) {
}
