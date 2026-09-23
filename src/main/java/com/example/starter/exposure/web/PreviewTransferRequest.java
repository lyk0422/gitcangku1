package com.example.starter.exposure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 预算转移预览请求。2～50 条明细，只计算完整后态，不写数据。
 *
 * @param lines 转移明细，按源目标规范化求和后生效
 */
public record PreviewTransferRequest(
        @NotNull @Size(min = 2, max = 50) List<@Valid TransferLineRequest> lines
) {
}
