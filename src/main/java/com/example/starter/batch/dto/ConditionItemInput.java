package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 条件放行子项输入：conditionKey 内的子项标识与条件说明。
 */
public record ConditionItemInput(
        @NotBlank(message = "条件子项 itemKey 不能为空")
        @Size(max = 64, message = "条件子项 itemKey 最长 64 字符")
        String itemKey,
        @NotBlank(message = "条件说明不能为空")
        @Size(max = 512, message = "条件说明最长 512 字符")
        String description
) {
}
