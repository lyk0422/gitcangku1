package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 解除装载请求。仅托盘未装运（OPEN）时允许；解除释放分隔位与批次占用，装载历史保留。
 */
public record UnloadRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "batchKeys 不能为空")
        @Size(min = 1, max = 8, message = "batchKeys 必须包含 1～8 个批次")
        List<@NotBlank(message = "batchKey 不能为空") String> batchKeys
) {
}
