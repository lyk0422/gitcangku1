package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量分片接收请求：本批先校验完整最终集合，任一失败不写入部分证据。
 */
public record ReceiveChunksRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty @Valid List<ChunkDigestInput> chunks) {
}
