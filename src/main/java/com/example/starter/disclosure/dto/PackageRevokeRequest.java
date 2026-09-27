package com.example.starter.disclosure.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销披露包请求。仅 GENERATED（未交付）包可撤销并必须写明原因；
 * 已交付（DELIVERED）包不可撤销，发现问题须创建更高修订包。
 *
 * @param commandKey 幂等命令键
 * @param reason     撤销原因，非空
 */
public record PackageRevokeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String reason) {
}
