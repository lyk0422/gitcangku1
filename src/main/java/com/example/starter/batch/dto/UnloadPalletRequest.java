package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 解除装载请求；batchKeys 为规范排序的待释放批次集合，仅未装运托盘可解除，整批成功或整批不写入。
 */
public record UnloadPalletRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotEmpty(message = "batchKeys 不能为空")
        @Size(max = 50, message = "batchKeys 最多 50 个批次")
        List<@NotBlank(message = "batchKey 不能为空") String> batchKeys
) {
}
