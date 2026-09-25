package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 成分修订请求体。expectedVersion 为调用方认为的当前成分版本号，不一致返回 409；
 * allergenCodes 换序视为同参（服务端规范化：去空白、大写、去重、排序）；
 * segregationLevel 为空或未知值返回 422（不用 400），故不做 Bean Validation 非空约束。
 */
public record ReviseCompositionRequest(
        @NotBlank(message = "allergenKey 不能为空") String allergenKey,
        @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
        @NotNull(message = "allergenCodes 不能为空") List<String> allergenCodes,
        String segregationLevel
) {
}
