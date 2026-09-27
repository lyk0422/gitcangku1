package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 补登分片清单请求。仅允许在发布单尚未登记清单且无任何任务拉取前登记，登记后不可修改。
 */
public record RegisterManifestRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Valid List<ChunkDigestInput> chunks,
        @NotBlank @Size(max = 64) String packageDigest) {
}
