package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt 为 UTC instant。
 * version 为批次版本号，每次储运偏差登记递增，参与 excursionKey 指纹；
 * minStorageTempC/maxStorageTempC 为储运温度规格（摄氏度，含边界）。
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        BatchStatus status,
        List<String> requiredTests,
        Instant createdAt,
        long version,
        Double minStorageTempC,
        Double maxStorageTempC
) {
}
