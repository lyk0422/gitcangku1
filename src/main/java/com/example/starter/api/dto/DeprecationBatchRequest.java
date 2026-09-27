package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量设置弃用窗口请求：整批先校验完整最终替代图，任一失败整次不写入。
 */
public record DeprecationBatchRequest(
        @NotEmpty @Size(max = 20) @Valid List<@Valid DeprecationSpec> windows) {

    public DeprecationBatchRequest {
        windows = windows == null ? List.of() : List.copyOf(windows);
    }
}
