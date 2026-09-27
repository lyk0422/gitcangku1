package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 单笔曝光确认回执请求；预占单编号来自路径。
 *
 * @param requestId    写操作全局唯一幂等键
 * @param operator     操作者标识，参与幂等指纹
 * @param receiptAtUtc 回执上报时刻，epoch 毫秒，UTC；不得早于预占时刻
 */
public record ReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull Long receiptAtUtc
) {
}
