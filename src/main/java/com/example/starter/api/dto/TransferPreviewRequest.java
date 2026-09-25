package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 容量转配预览请求（只读）。项顺序不影响语义。
 *
 * @param items 转配项，2~20 项，每项一条航线，航线不得重复
 */
public record TransferPreviewRequest(
        @NotNull @Valid @Size(min = 2, max = 20) List<TransferItemDto> items) {
}
