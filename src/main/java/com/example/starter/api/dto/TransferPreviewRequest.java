package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 容量转配预览请求（只读）。参与航线版本数须为 2~20。
 *
 * @param items 转配项列表，项顺序不影响语义
 */
public record TransferPreviewRequest(
        @NotNull @Valid @Size(min = 2, max = 100) List<TransferItemDto> items) {
}
