package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 召回处置单拒绝请求体。拒绝人通过 X-Actor-Id（OPERATIONS，必须与提交人不同）提供。
 * 拒绝不改任何批次；requestId 为拒绝命令幂等键。
 */
public record DispositionRejectRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "reason 不能为空") String reason
) {
}
