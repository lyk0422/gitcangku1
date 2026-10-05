package com.example.starter.maintenance.domain;

/**
 * 读数负荷类型：决定相邻区间累计工时增量计入等效寿命的权重。
 * NORMAL 权重 1，HEAVY 权重 2；历史读数（未声明权重）按 NORMAL 处理。
 */
public enum LoadType {

    NORMAL(1),
    HEAVY(2);

    private final int weight;

    LoadType(int weight) {
        this.weight = weight;
    }

    /** 等效分钟权重：区间增量（分钟）× 权重 = 该区间等效贡献（等效分钟）。 */
    public int weight() {
        return weight;
    }

    /** 按存储权重还原负荷类型；未知权重按 NORMAL 处理（兼容历史数据）。 */
    public static LoadType fromWeight(int weight) {
        for (LoadType type : values()) {
            if (type.weight == weight) {
                return type;
            }
        }
        return NORMAL;
    }
}
