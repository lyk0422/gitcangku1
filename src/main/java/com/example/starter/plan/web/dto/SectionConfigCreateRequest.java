package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建单线区段换向配置请求。requestId 为幂等键；operator 为操作者，
 * 二者与全部参数共同构成幂等身份。清空间隔整秒，范围 1 至 600。
 */
public record SectionConfigCreateRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank String sectionId,
        @NotBlank @Size(max = 32) String directionForward,
        @NotBlank @Size(max = 32) String directionBackward,
        @NotNull @Min(1) @Max(600) Integer clearanceSeconds) {
}
