package com.example.starter.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 产率平衡计算：投料、合格产出、损耗、报废与偏差统一三位小数。
 * 偏差 = |投料 - (合格产出 + 损耗 + 报废)|；恰好等于上限视为合格。
 */
public final class YieldMath {

    /**
     * 全部数量统一保留三位小数。
     */
    public static final int SCALE = 3;

    private YieldMath() {
    }

    /**
     * 规范化数量：四舍五入到三位小数。
     */
    public static BigDecimal normalize(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算偏差绝对值 |投料 - 合格 - 损耗 - 报废|，结果三位小数。
     */
    public static BigDecimal deviation(BigDecimal inputQty, BigDecimal goodQty,
                                       BigDecimal lossQty, BigDecimal scrapQty) {
        BigDecimal remainder = inputQty.subtract(goodQty).subtract(lossQty).subtract(scrapQty);
        return remainder.abs().setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 偏差是否在上限内：偏差小于或等于上限均合格（恰好等于上限视为合格）。
     */
    public static boolean withinLimit(BigDecimal deviation, BigDecimal limit) {
        return deviation.compareTo(limit) <= 0;
    }
}
