package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建耦合公差规范首版（version=1）请求。规范只追加新版本，已发布版本不可修改。
 */
public record CreateSpecRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotBlank(message = "specKey 不能为空") String specKey,
        @NotNull(message = "features 不能为空")
        @Size(min = 2, max = 5, message = "features 必须包含 2～5 个特性")
        List<@NotBlank(message = "特性名不能为空") String> features,
        @NotNull(message = "constraints 不能为空")
        @Size(min = 1, max = 8, message = "constraints 必须包含 1～8 条约束")
        List<@Valid SpecConstraintInput> constraints
) {
}
