package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 拆分批次请求：将 RELEASED 父批一次拆成 2～5 个全新子批。
 * commandKey 为幂等键：同键同参重放返回首次结果，同键改参返回 409，失败不占键。
 */
public record SplitBatchRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "children 不能为空")
        @Size(min = 2, max = 5, message = "children 必须包含 2～5 个子批")
        List<@Valid SplitChildRequest> children
) {
}
