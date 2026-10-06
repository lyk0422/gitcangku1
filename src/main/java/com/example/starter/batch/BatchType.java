package com.example.starter.batch;

/**
 * 批次类型。
 * STANDARD 普通批：不跟踪数量，不可绑定破坏性检验消耗；
 * QUANTIFIED 整数数量批：创建时设定不可变初始数量与最大取样量，
 * 破坏性检验按正整数消耗实物，剩余量 = 初始数量 - 累计消耗量。
 */
public enum BatchType {
    STANDARD,
    QUANTIFIED
}
