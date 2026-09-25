package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 命名空间许可证策略配置请求（整体替换允许集合）。
 *
 * @param namespace       命名空间名称（等于制品名称）
 * @param expectedVersion 期望的当前策略版本：无策略时必须为 0，首次创建；其后为当前版本
 * @param rejectUnknown   是否拒绝未登记许可证（UNKNOWN）
 * @param allowedLicenses 允许的许可证标识集合，重复标识会被去重
 */
public record SetPolicyRequest(
        @NotBlank String namespace,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull Boolean rejectUnknown,
        @Size(max = 50) List<@NotBlank @Size(max = 64) String> allowedLicenses) {

    public SetPolicyRequest {
        if (allowedLicenses == null) {
            allowedLicenses = List.of();
        } else {
            allowedLicenses = List.copyOf(allowedLicenses);
        }
    }
}
