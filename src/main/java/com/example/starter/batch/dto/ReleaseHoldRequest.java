package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 温控冻结解除请求：由不同于运输录入人的质量角色提交调查说明；
 * 仅当所有 EXCURSION 段均已逐段处置时才在同一事务内解除门禁。
 */
public record ReleaseHoldRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "investigationNote 不能为空") String investigationNote
) {
}
