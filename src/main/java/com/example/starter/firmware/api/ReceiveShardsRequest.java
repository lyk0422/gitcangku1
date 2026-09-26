package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Digests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 设备批量提交分片接收摘要请求。逐分片提交接收摘要，
 * 服务端校验当前代次的完整最终集合后在一个事务内裁决。
 */
public record ReceiveShardsRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty List<@Valid ReceivedShard> shards) {

    /**
     * 单个分片接收项：序号与设备实际接收摘要。
     */
    public record ReceivedShard(
            @NotNull @Min(0) Integer shardNo,
            @NotBlank @Pattern(regexp = Digests.DIGEST_PATTERN) String digest) {
    }
}
