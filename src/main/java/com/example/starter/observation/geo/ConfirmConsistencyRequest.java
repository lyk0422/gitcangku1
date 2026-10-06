package com.example.starter.observation.geo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 一致性确认请求：引用两条不同二维坐标观测的当前版本，按方向相关误差判定位置差异是否可确认。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param observationIdA   第一条观测记录标识
 * @param expectedVersionA 第一条观测的期望当前版本号，不匹配返回 409
 * @param observationIdB   第二条观测记录标识，必须与第一条不同
 * @param expectedVersionB 第二条观测的期望当前版本号，不匹配返回 409
 * @param operator         操作者标识，非空
 */
public record ConfirmConsistencyRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationIdA,
        @NotNull @Min(1) Integer expectedVersionA,
        @NotBlank @Size(max = 64) String observationIdB,
        @NotNull @Min(1) Integer expectedVersionB,
        @NotBlank @Size(max = 128) String operator) {
}
