package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 告知文本版本视图，regions 为规范化（大写、升序）目标地区集合。
 */
public record NoticeTextResponse(
        String textKey,
        int version,
        String content,
        List<String> regions,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
