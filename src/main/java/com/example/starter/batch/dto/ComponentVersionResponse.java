package com.example.starter.batch.dto;

import com.example.starter.batch.SegregationLevel;

import java.time.Instant;
import java.util.List;

/**
 * 不可变成分版本视图。allergenCodes 始终为规范化（去重、字典序升序）后的列表；
 * 空集合返回空列表而非 null。
 */
public record ComponentVersionResponse(
        String batchKey,
        int version,
        List<String> allergenCodes,
        SegregationLevel segregationLevel,
        boolean current,
        Instant createdAt
) {
}
