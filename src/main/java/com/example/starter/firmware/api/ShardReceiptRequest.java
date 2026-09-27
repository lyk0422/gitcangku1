package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 单片接收请求：设备在拉取任务后逐分片提交接收摘要。
 */
public record ShardReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(0) Integer shardIndex,
        @NotBlank @Size(max = 256) String shardDigest) {
}
