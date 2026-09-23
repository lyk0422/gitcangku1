package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 召回处置二审拒绝请求体（生产负责人）；拒绝不改任何批次。
 */
public record DispositionRejectRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "reason 不能为空") String reason
) {
}
