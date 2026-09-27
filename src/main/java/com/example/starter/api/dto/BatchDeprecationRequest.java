package com.example.starter.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量设置弃用窗口请求：先对完整替代图统一校验，任一失败整批不写入。
 */
public record BatchDeprecationRequest(
        @Valid @NotEmpty @Size(max = 50) List<@Valid DeprecationRequest> items) {

    public BatchDeprecationRequest {
        if (items != null) {
            items = List.copyOf(items);
        }
    }
}
