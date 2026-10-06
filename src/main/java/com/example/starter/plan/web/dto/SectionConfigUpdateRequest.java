package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新单线区段换向配置请求，expectedVersion 必须与当前配置版本一致。
 * requestId 为幂等键；operator 为操作者，与全部参数及期望版本共同构成幂等身份。
 */
public record SectionConfigUpdateRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotBlank @Size(max = 32) String directionForward,
        @NotBlank @Size(max = 32) String directionBackward,
        @NotNull @Min(1) @Max(600) Integer clearanceSeconds) {
}
