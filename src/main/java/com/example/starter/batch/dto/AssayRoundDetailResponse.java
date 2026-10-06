package com.example.starter.batch.dto;

import java.util.List;

/**
 * 轮次及其全部读数明细（查询用）：轮次概要 + 按序号升序的原始读数。
 */
public record AssayRoundDetailResponse(
        AssayRoundResponse round,
        List<AssayReadingEntry> readings
) {
}
