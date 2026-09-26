package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 分片摘要项：序号 + 64 位小写十六进制 SHA-256 摘要。清单登记与分片接收共用。
 */
public record ChunkDigestEntry(
        @NotNull Integer index,
        @NotBlank @Size(max = 64) String digest) {
}
