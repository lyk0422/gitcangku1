package com.example.starter.certificate.dto;

import java.time.Instant;

/**
 * 批次合格证绑定响应。matchedPrefix 为绑定时匹配批号的前缀；
 * 拆分继承且子批号不匹配任何前缀时为 null。
 */
public record BindingResponse(
        String batchKey,
        String declarationKey,
        int declarationVersion,
        String matchedPrefix,
        String boundBy,
        Instant boundAt
) {
}