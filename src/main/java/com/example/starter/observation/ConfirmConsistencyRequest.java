package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 一致性确认请求：引用两条不同观测点的当前版本，按方向相关误差（坐标差与协方差矩阵和）
 * 校验位置差异是否在容许范围内。确认只保存确认关系，不改动源观测点。
 *
 * @param requestId             全局唯一请求标识（幂等去重键）
 * @param firstObservationId    第一条观测点唯一标识
 * @param firstExpectedVersion  第一条观测点期望的当前版本号，不匹配返回 409
 * @param secondObservationId   第二条观测点唯一标识（必须与第一条不同）
 * @param secondExpectedVersion 第二条观测点期望的当前版本号，不匹配返回 409
 * @param operator              操作者标识（非空）
 */
public record ConfirmConsistencyRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String firstObservationId,
        @NotNull @Min(1) Integer firstExpectedVersion,
        @NotBlank @Size(max = 64) String secondObservationId,
        @NotNull @Min(1) Integer secondExpectedVersion,
        @NotBlank @Size(max = 128) String operator) {
}
