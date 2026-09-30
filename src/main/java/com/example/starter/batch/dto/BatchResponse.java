package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt 为 UTC instant。
 * allergens 为规范化（去重、稳定排序）后的过敏原集合，无过敏原为空列表；
 * cleaningGeneration 为批次当前生产线清洁代次（合批子批为合批时固化的代次）。
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        BatchStatus status,
        List<String> requiredTests,
        Instant createdAt,
        List<String> allergens,
        Integer cleaningGeneration
) {
}
