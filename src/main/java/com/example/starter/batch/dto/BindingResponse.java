package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 批次与声明版本的绑定明细。matchedPrefix 为按批次批号匹配到的最高优先级前缀；
 * 拆分继承的绑定按子批批号重算，无匹配时为 null（放行时视为无效绑定）。
 */
public record BindingResponse(
        String batchKey,
        String declarationKey,
        int version,
        String materialCode,
        String matchedPrefix,
        String boundBy,
        Instant boundAt
) {
}