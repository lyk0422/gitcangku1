package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 分片摘要项：序号从0连续，摘要为64位小写十六进制SHA-256。
 */
public record ChunkDigestBody(
        @Min(0) int index,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}", message = "摘要必须为64位小写十六进制") String digest) {
}
