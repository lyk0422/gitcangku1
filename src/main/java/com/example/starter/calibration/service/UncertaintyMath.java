package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.example.starter.calibration.model.UncertaintyComponent;

/**
 * 不确定度合成口径：公开的平方和开根（root-sum-square, RSS）。
 * u_c = sqrt(Σ u_i²)，结果按 HALF_UP 保留 4 位小数返回；
 * 分量均为非负值。空预算的合成结果为 0（保留 4 位）。
 */
public final class UncertaintyMath {

    /** 结果保留小数位。 */
    public static final int SCALE = 4;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);

    private UncertaintyMath() {
    }

    /**
     * 计算合成不确定度并按 HALF_UP 保留 4 位小数。
     *
     * @param components 非负分量列表
     * @return sqrt(Σ u_i²)，4 位小数；空列表返回 0.0000
     */
    public static BigDecimal combined(List<UncertaintyComponent> components) {
        if (components == null || components.isEmpty()) {
            return ZERO;
        }
        BigDecimal sumSquares = BigDecimal.ZERO;
        for (UncertaintyComponent c : components) {
            sumSquares = sumSquares.add(c.magnitude().pow(2));
        }
        return sqrt(sumSquares);
    }

    /**
     * 对非负 BigDecimal 开平方并按 HALF_UP 保留 4 位小数。
     * 内部以更高精度计算后舍入，避免误差影响第 4 位。
     */
    public static BigDecimal sqrt(BigDecimal value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("不确定度平方和不能为负");
        }
        if (value.signum() == 0) {
            return ZERO;
        }
        // 牛顿迭代，计算到 12 位小数后再 HALF_UP 到 4 位
        int workingScale = 12;
        BigDecimal x = BigDecimal.valueOf(Math.sqrt(value.doubleValue()))
                .setScale(workingScale, RoundingMode.HALF_UP);
        BigDecimal two = BigDecimal.valueOf(2);
        for (int i = 0; i < 30; i++) {
            BigDecimal next = x.add(value.divide(x, workingScale, RoundingMode.HALF_UP))
                    .divide(two, workingScale, RoundingMode.HALF_UP);
            if (next.compareTo(x) == 0) {
                break;
            }
            x = next;
        }
        return x.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
