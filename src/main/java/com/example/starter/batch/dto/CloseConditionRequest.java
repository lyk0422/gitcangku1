package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 核销条件子项请求：逐条提交 conditionKey 内子项标识与证明说明；
 * 核销批准角色必须与创建条件放行时的角色不同。
 */
public record CloseConditionRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "itemKey 不能为空") String itemKey,
        @NotBlank(message = "evidence 证明说明不能为空")
        @Size(max = 512, message = "evidence 最长 512 字符")
        String evidence
) {
}
