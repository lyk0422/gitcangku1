package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单线区段换向配置创建/更新请求。
 * requestId 为幂等键；operator 为操作者标识，参与幂等身份；
 * 配置不存在时创建（版本 1，expectedVersion 必须为 null），
 * 已存在时更新（expectedVersion 必须等于当前版本，成功版本加一）。
 */
public record SectionConfigRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        Integer expectedVersion,
        @NotNull @Min(1) @Max(600) Integer clearanceSeconds) {
}
