package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 分片摘要条目（登记清单或设备提交接收摘要共用）。
 * 业务规则（序号重复、缺口、聚合不匹配、格式）在服务端校验并返回 422。
 */
public record ChunkDigestInput(
        @NotNull Integer chunkIndex,
        @NotBlank @Size(max = 64) String digest) {
}
