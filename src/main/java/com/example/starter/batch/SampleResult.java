package com.example.starter.batch;

/**
 * 逐件样本登记结果及加权缺陷分。
 * QUALIFIED 为合格；CRITICAL 致命缺陷记 3、MAJOR 主要缺陷记 1、MINOR 轻微缺陷记 0，单位均为加权分。
 */
public enum SampleResult {
    QUALIFIED(0),
    CRITICAL(3),
    MAJOR(1),
    MINOR(0);

    private final int weight;

    SampleResult(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
