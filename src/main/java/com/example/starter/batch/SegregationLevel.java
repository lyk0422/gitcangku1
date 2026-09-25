package com.example.starter.batch;

/**
 * 过敏原隔离级别，顺序即严格程度：NONE（无隔离要求）＜ LOW ＜ MEDIUM ＜ HIGH。
 * 召回祖先的后代批次做成分修订时，级别只可保持或上调，不得降低。
 */
public enum SegregationLevel {
    NONE,
    LOW,
    MEDIUM,
    HIGH;

    /**
     * 级别是否严格高于另一级别。
     */
    public boolean stricterThan(SegregationLevel other) {
        return this.ordinal() > other.ordinal();
    }
}
