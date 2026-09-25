package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 命名空间许可证策略修改请求，携带期望策略版本做乐观并发控制。
 *
 * @param expectedVersion 期望的当前策略版本：0 表示要求命名空间尚无策略；
 *                        与实际版本不一致时返回 409
 * @param allowedLicenses 允许的许可证标识集合（去重保存），空集合表示不允许任何已登记许可证
 * @param rejectUnknown   是否拒绝未登记许可证（UNKNOWN）的制品版本
 */
public record PolicyRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @Size(max = 50) List<@NotBlank @Size(max = 64) String> allowedLicenses,
        boolean rejectUnknown) {

    public PolicyRequest {
        if (allowedLicenses == null) {
            allowedLicenses = List.of();
        } else {
            allowedLicenses = List.copyOf(allowedLicenses);
        }
    }
}
