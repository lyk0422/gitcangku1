package com.example.starter.blind.service;

import com.example.starter.blind.repo.AllocationRepository.BalanceRow;

import java.util.List;

/**
 * 协变量最小化分配（纯函数，不接触数据库）：
 * 对每个还有席位的处理模拟分配新参与者，计算四个边际格
 * （特征一取0、特征一取1、特征二取0、特征二取1）的 A 人数减 B 人数的绝对值总和，
 * 选择分数最小的处理，同分选 A。已退组分配仍计入历史平衡（由调用方传入）。
 */
public final class MinimizationAllocator {

    /** 处理代码 A。 */
    public static final String TREATMENT_A = "A";
    /** 处理代码 B。 */
    public static final String TREATMENT_B = "B";

    private MinimizationAllocator() {
    }

    /**
     * 选择处理：在还有席位的处理中选模拟分配后失衡分数最小者，同分选 A。
     *
     * @param rows       历史分配（含已退组）的处理与特征
     * @param feature1   新参与者特征一（0/1）
     * @param feature2   新参与者特征二（0/1）
     * @param aRemaining A 处理剩余席位数
     * @param bRemaining B 处理剩余席位数
     * @return 选中的处理代码；两个处理均无席位（满额）时返回 null
     */
    public static String chooseTreatment(List<BalanceRow> rows, int feature1, int feature2,
                                         int aRemaining, int bRemaining) {
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        if (aRemaining > 0) {
            best = TREATMENT_A;
            bestScore = imbalanceScore(rows, feature1, feature2, TREATMENT_A);
        }
        if (bRemaining > 0) {
            int score = imbalanceScore(rows, feature1, feature2, TREATMENT_B);
            if (score < bestScore) {
                best = TREATMENT_B;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * 模拟把新参与者分给 candidate 后的失衡分数：
     * 四个边际格 |A人数 - B人数| 的总和。格索引：0=特征一取0，1=特征一取1，2=特征二取0，3=特征二取1。
     */
    static int imbalanceScore(List<BalanceRow> rows, int feature1, int feature2, String candidate) {
        int[] a = new int[4];
        int[] b = new int[4];
        for (BalanceRow row : rows) {
            int[] counts = TREATMENT_A.equals(row.treatment()) ? a : b;
            counts[row.feature1()]++;
            counts[2 + row.feature2()]++;
        }
        int[] simulated = TREATMENT_A.equals(candidate) ? a : b;
        simulated[feature1]++;
        simulated[2 + feature2]++;
        int sum = 0;
        for (int i = 0; i < 4; i++) {
            sum += Math.abs(a[i] - b[i]);
        }
        return sum;
    }
}
