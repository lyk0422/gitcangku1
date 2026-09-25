package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 供应商门槛配置视图。threshold 为 null 表示未设置门槛（不受准入限制）；
 * updatedAt 为最近一次设置时间，未设置时为 null。
 */
public record ThresholdResponse(String supplierId, Integer threshold, Instant updatedAt) {
}
