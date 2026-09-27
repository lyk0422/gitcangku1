package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 分片摘要登记项：序号从0开始连续，摘要为64位小写十六进制SHA-256（业务校验，非法返回422）。
 */
public record ShardInput(
        @NotNull @Min(0) Integer shardIndex,
        @NotBlank @Size(max = 64) String shardDigest) {
}
