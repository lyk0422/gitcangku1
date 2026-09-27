package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量接收分片请求：先与已接收证据合并校验完整最终集合，任一失败不留下部分可安装状态。
 */
public record ChunkBatchRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty @Size(max = 4096) List<@Valid ChunkDigestBody> chunks) {
}
