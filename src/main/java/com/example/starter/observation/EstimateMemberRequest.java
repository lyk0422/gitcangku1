package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 分层密度估计请求中的成员：引用一条观测记录的当前版本。
 *
 * @param observationId 观测记录唯一标识
 * @param version       期望的观测当前版本号；与当前版本不一致返回 409
 */
public record EstimateMemberRequest(
        @NotBlank @Size(max = 64) String observationId,
        @NotNull @Min(1) Integer version) {
}
