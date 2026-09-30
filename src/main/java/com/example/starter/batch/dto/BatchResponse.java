package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt 为 UTC instant。
 * allergens 为规范化（去重、字典序稳定排序）后的过敏原集合，空集合表示无登记过敏原；
 * cleanGeneration 为批次当前生产线清洁代次（推进后随之变化）；
 * mergeBasis 仅合批子批非空，为该子批固化的合批依据，事后不随清洁推进改写。
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        BatchStatus status,
        List<String> requiredTests,
        List<String> allergens,
        int cleanGeneration,
        MergeBasisResponse mergeBasis,
        Instant createdAt
) {
}
