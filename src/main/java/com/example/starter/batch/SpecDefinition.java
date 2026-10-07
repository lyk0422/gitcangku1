package com.example.starter.batch;

import java.math.BigDecimal;
import java.util.List;

/**
 * 特性规范定义：一版规范固定 2～5 个无量纲合成特性及 1～8 条线性不等式约束。
 * 版本只追加，定义加载后不可修改。
 *
 * @param features    特性名称，顺序即测量向量下标顺序
 * @param constraints 线性不等式约束列表
 */
public record SpecDefinition(String specKey, int version, List<String> features,
                             List<LinearConstraint> constraints) {

    /**
     * 单条线性不等式：整数系数乘特性之和不大于精确十进制上界（等号合格）。
     *
     * @param coefficients 整数系数，范围 -100～100，与特性一一对应，不得全零
     * @param upperBound   精确十进制上界
     */
    public record LinearConstraint(List<Integer> coefficients, BigDecimal upperBound) {
    }
}
