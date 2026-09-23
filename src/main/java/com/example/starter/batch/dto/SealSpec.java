package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 单批封签：发运时由源厂登记、接收时由目标厂逐批提交核对。
 */
public record SealSpec(
        @NotBlank(message = "批次 batchKey 不能为空") String batchKey,
        @NotBlank(message = "封签号 sealNo 不能为空") String sealNo
) {
}
