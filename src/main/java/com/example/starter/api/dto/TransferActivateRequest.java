package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 容量转配激活请求。2~20 个转配项，项顺序不影响语义（按完整后态统一计算）。
 *
 * @param transferKey 转配单唯一标识（全局唯一，激活成功落库）
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 * @param items       转配项集合（允许跨航线形成 A→B→C→A 闭环）
 */
public record TransferActivateRequest(
        @NotBlank @Size(max = 64) String transferKey,
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Valid @Size(min = 2, max = 20) List<TransferItemRequest> items) {
}
