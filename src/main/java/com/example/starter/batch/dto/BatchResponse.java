package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt 为 UTC instant。
 * allergens 为规范化（去重、字典序稳定排序）后的过敏原集合，空列表表示不含登记过敏原；
 * cleaningGeneration 为生产线清洁代次（正整数）。
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        BatchStatus status,
        List<String> requiredTests,
        List<String> allergens,
        int cleaningGeneration,
        Instant createdAt
) {
}
