package com.example.starter.coral;

import java.time.Instant;
import java.util.List;

/**
 * 移植记录查询视图：来源苗圃、当前样方、当前状态、登记UTC时刻、复核链与所属样方封存摘要。
 */
public record TransplantView(
        String plantCode,
        String nursery,
        String plotCode,
        TransplantStatus status,
        Instant transplantedAt,
        List<ReviewView> reviews,
        SealSummary seal) {
}