package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 激活迁移单请求。requestId 幂等去重（设备项换序视为同参），migrationKey 全局唯一。
 */
public record ActivateMigrationRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String migrationKey,
        @NotNull @Size(min = 2, max = 500) List<@Valid MigrationItemRequest> items) {
}
