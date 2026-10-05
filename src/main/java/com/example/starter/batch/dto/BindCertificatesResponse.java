package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量绑定响应。bindings 按 batchKey 升序（稳定排序）；boundCount 为实际绑定数量。
 */
public record BindCertificatesResponse(
        int boundCount,
        List<BindingResult> bindings
) {

    /**
     * 单条绑定结果：固化声明版本、匹配前缀与绑定时点。
     */
    public record BindingResult(
            String batchKey,
            String declarationKey,
            int version,
            String materialCode,
            String matchedPrefix,
            Instant boundAt
    ) {
    }
}
