package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 批量原子改签请求：整批任一计划校验非法则全部不改变时隙或编组。
 * requestKey 为整批幂等键；operator 为操作者标识；items 顺序即结果返回顺序。
 */
public record BatchRescheduleRequest(
        @NotBlank String requestKey,
        @NotBlank String operator,
        @NotEmpty @Valid List<@NotNull BatchRescheduleItem> items) {
}
