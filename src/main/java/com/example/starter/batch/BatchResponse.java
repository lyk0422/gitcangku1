package com.example.starter.batch;

import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。
 *
 * @param batchKey      批次业务键
 * @param productCode   产品编码
 * @param lotNumber     批号
 * @param producedAt    生产时间（UTC）
 * @param status        当前批次状态
 * @param requiredItems 必做检验项
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String lotNumber,
        Instant producedAt,
        BatchStatus status,
        List<String> requiredItems) {
}
