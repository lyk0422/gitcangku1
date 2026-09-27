package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销披露包请求。仅案件最新版本处于未交付（GENERATED）状态时可撤销，原因必须非空；
 * 已交付包不可撤销，发现问题须创建更高修订包。
 *
 * @param commandKey 幂等命令键
 * @param reason    撤销原因，非空
 */
public record PackageRevokeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String reason) {
}
