package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 替换发布单分片清单请求。仅在尚无任务拉取（摘要未锁定）时允许，锁定后返回409。
 */
public record UpdateManifestRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String packageDigest,
        @NotEmpty @Valid List<ShardInput> shards) {
}
