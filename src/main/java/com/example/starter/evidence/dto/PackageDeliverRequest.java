package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 交付披露包请求。仅案件最新版本处于未交付（GENERATED）状态时可交付；已交付为终态。
 *
 * @param commandKey 幂等命令键
 * @param reason     交付备注，可空
 */
public record PackageDeliverRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @Size(max = 512) String reason) {
}
