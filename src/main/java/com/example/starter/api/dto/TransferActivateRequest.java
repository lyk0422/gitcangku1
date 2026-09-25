package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 容量转配激活请求。transferKey 全局唯一；requestId 用于幂等重放，
 * 转配项换序视为同参。
 *
 * @param transferKey 转配单唯一业务标识
 * @param items       转配项列表，项顺序不影响语义
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record TransferActivateRequest(
        @NotBlank @Size(max = 64) String transferKey,
        @NotNull @Valid @Size(min = 2, max = 100) List<TransferItemDto> items,
        @NotBlank @Size(max = 64) String requestId) {
}
