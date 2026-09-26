package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Digests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;

import java.util.List;

/**
 * 登记发布版本分片清单请求。分片序号从0开始连续，摘要为小写十六进制SHA-256（64字符），
 * fullDigest 为按序号升序拼接各分片摘要后的聚合SHA-256。
 */
public record RegisterShardsRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Pattern(regexp = Digests.DIGEST_PATTERN) String fullDigest,
        @NotEmpty List<@Valid ShardDigestInput> shards) {

    /**
     * 单个分片登记项。
     */
    public record ShardDigestInput(
            @NotNull @Min(0) Integer shardNo,
            @NotBlank @Pattern(regexp = Digests.DIGEST_PATTERN) String digest) {
    }
}
