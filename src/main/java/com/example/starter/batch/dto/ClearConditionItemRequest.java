package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 核销条件子项请求。itemKey 为条件子项标识（条件说明在创建时的序号，从 1 开始的字符串）；
 * evidence 为证明说明。核销角色通过 X-Approval-Role 请求头提供，必须与创建角色不同。
 */
public record ClearConditionItemRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "itemKey 不能为空") String itemKey,
        @NotBlank(message = "evidence 不能为空") String evidence
) {
}
