package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 分片登记项。序号由 0 开始连续，摘要为 64 位小写十六进制 SHA-256。
 */
public record FragmentInput(
        @NotNull @Min(0) Integer seqNo,
        @NotBlank
        @Pattern(regexp = "[0-9a-f]{64}", message = "必须为64位小写十六进制SHA-256摘要")
        String digest) {
}
