package com.example.starter.batch;

import java.util.Optional;

/**
 * 过敏原隔离级别，序号越大隔离要求越高。
 * 召回任一来源后，其后代批次的成分版本不得再降低隔离级别（序号不得变小）。
 */
public enum SegregationLevel {
    NONE(0),
    SEGREGATED(1),
    ISOLATED(2);

    private final int rank;

    SegregationLevel(int rank) {
        this.rank = rank;
    }

    /**
     * 隔离要求序号：0 无隔离，1 分隔，2 隔离。
     */
    public int rank() {
        return rank;
    }

    /**
     * 解析隔离级别；空白或未知值返回空（由调用方映射为 422）。
     */
    public static Optional<SegregationLevel> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(SegregationLevel.valueOf(value.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
