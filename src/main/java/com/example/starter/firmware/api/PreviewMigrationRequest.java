package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 迁移预览请求：按完整后态计算队列规模与配额，不写数据。
 */
public record PreviewMigrationRequest(
        @NotNull @Size(min = 2, max = 500) List<@Valid MigrationItemRequest> items) {
}
