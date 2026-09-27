package com.example.starter.pallet.dto;

import java.time.Instant;
import java.util.List;

/**
 * 装载响应：按 batchKey 规范排序的装载项与装载时点隔板版本。
 */
public record LoadResponse(
        String palletKey,
        int dividerVersion,
        List<Item> items,
        Instant loadedAt
) {
    /**
     * 单个装载结果：批次、隔离类别、分隔位与规范序号。
     */
    public record Item(String batchKey, String category, int compartment, int seq) {
    }
}
