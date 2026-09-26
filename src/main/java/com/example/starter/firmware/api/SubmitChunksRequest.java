package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量接收分片请求。complete 为 true 时按完整最终集合立即核验：
 * 集合不完整（缺失）即判定 INTEGRITY_FAILED；缺省 false 时仅登记接收，
 * 累计达到清单要求数量时自动触发核验。
 */
public record SubmitChunksRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotEmpty @Size(max = 1024) List<@Valid ChunkDigestEntry> chunks,
        Boolean complete) {

    public boolean effectiveComplete() {
        return complete != null && complete;
    }
}
