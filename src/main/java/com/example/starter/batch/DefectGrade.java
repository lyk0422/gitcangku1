package com.example.starter.batch;

/**
 * 样本缺陷等级及其加权缺陷数：CRITICAL 记 3、MAJOR 记 1、MINOR 记 0；合格件不携带等级。
 */
public enum DefectGrade {
    CRITICAL(3),
    MAJOR(1),
    MINOR(0);

    private final int weight;

    DefectGrade(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
