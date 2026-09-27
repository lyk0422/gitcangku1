package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量登记弃用窗口请求：先校验完整替代图，任一失败整次不写入。
 */
public record BatchDeprecationRequest(
        @NotNull @Size(min = 1, max = 20) List<@Valid DeprecationSpec> windows) {

    public BatchDeprecationRequest {
        if (windows != null) {
            windows = List.copyOf(windows);
        }
    }
}
