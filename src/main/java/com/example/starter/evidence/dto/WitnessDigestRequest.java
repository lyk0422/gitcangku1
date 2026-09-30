package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 数字摘要见证请求。两名互不相同的保管人分别确认同一封存版本。
 *
 * @param commandKey 幂等命令键
 * @param versionNo  被见证的封存版本号；版本失效（已重封存）后见证旧版本不构成可导出具件
 */
public record WitnessDigestRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotNull @Positive Integer versionNo) {
}
