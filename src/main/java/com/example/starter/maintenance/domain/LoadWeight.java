package com.example.starter.maintenance.domain;

/**
 * 负荷权重档位：相邻累计读数增量按本次读数声明的档位加权计入等效寿命。
 * NORMAL 权重 1，HEAVY 权重 2；历史旧读数一律按 NORMAL。
 */
public enum LoadWeight {

    NORMAL(1),
    HEAVY(2);

    private final int factor;

    LoadWeight(int factor) {
        this.factor = factor;
    }

    public int factor() {
        return factor;
    }

    /** 档位权重系数；空值或未知档位按 NORMAL（兼容历史旧读数）。 */
    public static int factorOf(String name) {
        if (name == null) {
            return NORMAL.factor;
        }
        for (LoadWeight weight : values()) {
            if (weight.name().equals(name)) {
                return weight.factor;
            }
        }
        return NORMAL.factor;
    }
}
