package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 登记发布版本分片清单请求：规范排序的分片摘要与完整包聚合摘要。
 * 聚合摘要为按序号升序拼接分片摘要后的SHA-256（64位小写十六进制）。
 */
public record RegisterManifestRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty @Size(max = 4096) List<@Valid ChunkDigestBody> chunks,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}", message = "聚合摘要必须为64位小写十六进制")
        String aggregateDigest) {
}
