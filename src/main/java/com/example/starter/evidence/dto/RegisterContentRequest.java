package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 证物当前文件内容登记请求。登记/替换证物当前内容字节，不产生封存版本；
 * 用于采集入册，以及反映保管期间文件被改动或恢复（导出时重算摘要即可发现与封存版本不符）。
 *
 * @param commandKey    幂等命令键
 * @param contentBase64 证物当前内容字节的 Base64 编码
 */
public record RegisterContentRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 16777216) String contentBase64) {
}
