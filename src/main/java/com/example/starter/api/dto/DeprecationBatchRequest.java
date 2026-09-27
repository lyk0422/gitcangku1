package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量登记弃用窗口请求：先校验完整替代图，任一失败整批不写入。
 */
public record DeprecationBatchRequest(
        @NotEmpty @Size(max = 20) List<@Valid DeprecationWindowRequest> windows) {

    public DeprecationBatchRequest {
        if (windows != null) {
            windows = List.copyOf(windows);
        }
    }
}
