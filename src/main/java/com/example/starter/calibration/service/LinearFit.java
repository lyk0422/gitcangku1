package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.starter.calibration.api.ApiException;

/**
 * 参考点线性最小二乘拟合：a=(nΣxy−ΣxΣy)/(nΣx²−(Σx)²)，b=(Σy−aΣx)/n。
 * 全程 BigDecimal 精确有理数计算；a、b 必须能精确表示为最多 6 位小数，否则 422，不做强行舍入。
 */
public final class LinearFit {

    /** 参考点数量下限。 */
    static final int MIN_POINTS = 3;
    /** 参考点数量上限。 */
    static final int MAX_POINTS = 8;
    /** 系数允许的最大小数位数。 */
    private static final int COEFF_SCALE = 6;

    private LinearFit() {
    }

    /**
     * 一对参考点：合成原值 x 与标准值 y（均为最多 6 位小数的精确十进制）。
     */
    public record Point(BigDecimal x, BigDecimal y) {
    }

    /**
     * 拟合结果：精确系数 a/b、各点残差 y−(a·x+b) 及最大绝对残差。
     */
    public record Result(BigDecimal a, BigDecimal b,
                         List<BigDecimal> residuals, BigDecimal maxAbsResidual) {
    }

    /**
     * 执行拟合。点数须为 3～8；x 互不相同（重复 x 或分母为 0 抛 400）；
     * a、b 不能精确表示为最多 6 位小数时抛 422。
     */
    public static Result fit(List<Point> points) {
        if (points == null || points.size() < MIN_POINTS || points.size() > MAX_POINTS) {
            throw ApiException.badRequest("参考点数量必须为 " + MIN_POINTS + "～" + MAX_POINTS + " 对");
        }
        Set<BigDecimal> seen = new HashSet<>();
        for (Point point : points) {
            if (!seen.add(point.x().stripTrailingZeros())) {
                throw ApiException.badRequest("参考点 x 存在重复值: " + point.x().toPlainString());
            }
        }

        BigDecimal n = BigDecimal.valueOf(points.size());
        BigDecimal sumX = BigDecimal.ZERO;
        BigDecimal sumY = BigDecimal.ZERO;
        BigDecimal sumXY = BigDecimal.ZERO;
        BigDecimal sumX2 = BigDecimal.ZERO;
        for (Point point : points) {
            sumX = sumX.add(point.x());
            sumY = sumY.add(point.y());
            sumXY = sumXY.add(point.x().multiply(point.y()));
            sumX2 = sumX2.add(point.x().multiply(point.x()));
        }

        BigDecimal denominator = n.multiply(sumX2).subtract(sumX.multiply(sumX));
        if (denominator.signum() == 0) {
            throw ApiException.badRequest("拟合分母为 0：参考点 x 无法确定唯一直线");
        }
        BigDecimal aNumerator = n.multiply(sumXY).subtract(sumX.multiply(sumY));
        BigDecimal a = divideExact(aNumerator, denominator, "a");
        BigDecimal b = divideExact(sumY.subtract(a.multiply(sumX)), n, "b");

        List<BigDecimal> residuals = new ArrayList<>(points.size());
        BigDecimal maxAbs = BigDecimal.ZERO;
        for (Point point : points) {
            BigDecimal residual = point.y().subtract(a.multiply(point.x()).add(b));
            residuals.add(residual);
            BigDecimal abs = residual.abs();
            if (abs.compareTo(maxAbs) > 0) {
                maxAbs = abs;
            }
        }
        return new Result(a, b, List.copyOf(residuals), maxAbs);
    }

    /**
     * 精确除法：商必须能精确表示为最多 6 位小数，否则抛 422（不强行舍入）。
     */
    private static BigDecimal divideExact(BigDecimal numerator, BigDecimal denominator, String name) {
        try {
            return numerator.divide(denominator, COEFF_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw ApiException.unprocessable("COEFFICIENT_NOT_REPRESENTABLE",
                    "拟合系数 " + name + " 不能精确表示为最多 6 位小数，拒绝签发");
        }
    }
}
