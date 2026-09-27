package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 设备提交分片接收摘要请求（支持单分片或批量）。
 * 服务端先按"完整最终集合"校验：同批内重复序号、摘要与登记不符、全量到齐但聚合不符
 * 都会令本次尝试进入 INTEGRITY_FAILED，且不留下任何部分可安装状态。
 */
public record FragmentReceiptRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty @Valid @Size(max = 1024) List<ReceivedFragment> fragments) {

    public record ReceivedFragment(
            @NotNull Integer seqNo,
            @NotBlank
            @Pattern(regexp = "[0-9a-f]{64}", message = "必须为64位小写十六进制SHA-256摘要")
            String digest) {
    }
}
