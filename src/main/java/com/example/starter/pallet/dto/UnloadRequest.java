package com.example.starter.pallet.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 解除装载请求：一次解除 1～20 个在托批次；仅允许托盘尚未装运；释放分隔位但保留历史。
 */
public record UnloadRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "batchKeys 不能为空")
        @Size(min = 1, max = 20, message = "batchKeys 必须包含 1～20 个批次")
        List<@NotBlank(message = "batchKey 不能为空") String> batchKeys
) {
}
