package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤回合格证声明请求；操作者通过 X-Actor-Id 请求头提供。
 * 撤回只影响撤回之后的放行匹配，不改写版本内容与历史放行快照。
 */
public record WithdrawDeclarationRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "reason 不能为空") String reason
) {
}
