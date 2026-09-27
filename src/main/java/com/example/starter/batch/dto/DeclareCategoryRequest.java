package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 批次隔离类别声明请求；类别一经声明不可更改，装载托盘前必须完成声明。
 */
public record DeclareCategoryRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "segregationCategory 不能为空")
        @Size(max = 64, message = "segregationCategory 长度不能超过 64")
        @Pattern(regexp = "[A-Za-z0-9_\\-]+", message = "segregationCategory 只能包含字母、数字、下划线或连字符")
        String segregationCategory
) {
}
