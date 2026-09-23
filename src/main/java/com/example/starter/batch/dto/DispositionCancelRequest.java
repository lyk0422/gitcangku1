package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 召回处置单取消请求体。确认或拒绝前由提交人本人（QUALITY）取消；取消不改任何批次。
 * requestId 为取消命令幂等键。
 */
public record DispositionCancelRequest(
        @NotBlank(message = "requestId 不能为空") String requestId
) {
}
