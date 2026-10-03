package com.example.starter.service;

/**
 * 色差计算：以目标值为基准的三维欧氏距离，比较前不四舍五入。
 */
public final class ColorDifference {

    /** 合格阈值：色差不超过 2.0（含边界）才合格。 */
    public static final double TOLERANCE = 2.0;

    private ColorDifference() {
    }

    /**
     * 计算实测 (l,a,b) 相对目标 (tl,ta,tb) 的三维欧氏距离。
     */
    public static double deltaE(double l, double a, double b, double tl, double ta, double tb) {
        double dl = l - tl;
        double da = a - ta;
        double db = b - tb;
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    /**
     * 判定色差是否合格：不超过 2.0（含边界）。
     */
    public static boolean qualified(double deltaE) {
        return deltaE <= TOLERANCE;
    }
}