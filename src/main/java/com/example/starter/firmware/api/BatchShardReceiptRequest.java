package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量分片接收请求：服务端按“已有证据 ∪ 本批”的完整最终集合一次性校验，
 * 任一失败则任务转 INTEGRITY_FAILED，不留下部分可安装状态。
 */
public record BatchShardReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty @Valid List<ShardReceiptItem> shards) {

    public record ShardReceiptItem(
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Integer shardIndex,
            @NotBlank @Size(max = 256) String shardDigest) {
    }
}
