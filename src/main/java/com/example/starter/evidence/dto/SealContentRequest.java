package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 封存登记请求：对证物<b>当前已登记内容</b>计算 SHA-256 并生成递增封存版本。
 * 重新封存产生新版本，旧版本见证不会自动转移。当前内容须先通过内容登记接口写入。
 *
 * @param commandKey 幂等命令键
 */
public record SealContentRequest(
        @NotBlank @Size(max = 64) String commandKey) {
}
