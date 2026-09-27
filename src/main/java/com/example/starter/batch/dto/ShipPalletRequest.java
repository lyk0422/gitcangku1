package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 装运放行请求；装运时固化托盘、批次、类别、分隔位与隔板版本快照，之后托盘冻结不可解除或改隔板。
 */
public record ShipPalletRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}
