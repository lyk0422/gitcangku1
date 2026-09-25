package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 容量转配激活请求：在一个事务内校验并应用全部转配项，任一失败整体回滚。
 * 项顺序不影响语义（换序视为同参）。
 *
 * @param transferKey 转配单业务唯一标识
 * @param items       转配项，2~20 项，每项一条航线，航线不得重复
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record TransferRequest(
        @NotBlank @Size(max = 64) String transferKey,
        @NotNull @Valid @Size(min = 2, max = 20) List<TransferItemDto> items,
        @NotBlank @Size(max = 64) String requestId) {
}
