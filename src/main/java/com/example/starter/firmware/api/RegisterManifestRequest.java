package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 登记发布版本分片清单请求。分片序号须从 0 连续无缺口、无重复，
 * packageDigest 须等于按序号拼接全部分片摘要后的 SHA-256，违反任一规则返回 422。
 */
public record RegisterManifestRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String packageDigest,
        @NotEmpty @Size(max = 1024) List<@Valid ChunkDigestEntry> chunks) {
}
