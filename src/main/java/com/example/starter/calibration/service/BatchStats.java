package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.example.starter.calibration.model.BatchReading;

/**
 * 批次统计计算：基于未排除读数的未舍入校准计算值。
 * 均值按 HALF_UP 保留 12 位小数；无计入读数时 mean/min/max 为 null。
 */
final class BatchStats {

    private BatchStats() {
    }

    /** 统计结果。 */
    record Values(int includedCount, int excludedCount,
                  BigDecimal mean, BigDecimal min, BigDecimal max) {
    }

    /**
     * 计算指定读数集合的统计值；excluded=TRUE 的读数不计入。
     */
    static Values compute(List<BatchReading> readings) {
        List<BigDecimal> included = readings.stream()
                .filter(r -> !r.excluded())
                .map(BatchReading::computedValue)
                .toList();
        int excludedCount = readings.size() - included.size();
        if (included.isEmpty()) {
            return new Values(0, excludedCount, null, null, null);
        }
        BigDecimal sum = included.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mean = sum.divide(BigDecimal.valueOf(included.size()), 12, RoundingMode.HALF_UP);
        BigDecimal min = included.stream().min(BigDecimal::compareTo).orElseThrow();
        BigDecimal max = included.stream().max(BigDecimal::compareTo).orElseThrow();
        return new Values(included.size(), excludedCount, mean, min, max);
    }
}
