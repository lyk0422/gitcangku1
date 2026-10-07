package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 声明版本引用：declarationKey + version 唯一定位一个声明版本。
 */
public record DeclarationRef(
        @NotBlank(message = "declarationKey 不能为空") String declarationKey,
        @NotNull(message = "version 不能为空") Integer version
) {
}
