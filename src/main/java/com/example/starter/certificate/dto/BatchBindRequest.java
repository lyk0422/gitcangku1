package com.example.starter.certificate.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量绑定请求体：一次为多个批次绑定声明版本；任一失效或冲突整次回滚，
 * 查询不到半成品绑定。操作者由 X-Actor-Id 请求头给出。
 */
public record BatchBindRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "items 不能为空")
        @Size(min = 1, max = 10, message = "items 必须包含 1～10 个绑定项")
        List<@Valid Item> items
) {

    /**
     * 单个绑定项：批次业务键 + 目标声明版本。
     */
    public record Item(
            @NotBlank(message = "batchKey 不能为空") String batchKey,
            @NotBlank(message = "declarationKey 不能为空") String declarationKey,
            @Min(value = 1, message = "version 必须不小于 1") int version
    ) {
    }
}