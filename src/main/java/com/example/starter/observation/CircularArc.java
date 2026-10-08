package com.example.starter.observation;

/**
 * 圆周区间（圆弧）：圆周上的一段正向弧，全部使用整数运算，禁止浮点。
 *
 * <p>区间含两端并按模 360000 绕回：起始角 359000、弧长 2000 覆盖
 * 359000..359999 与 0..1000，即 359 度与 1 度之间按跨零方向相距 2 度而非 358 度。
 *
 * @param startAngle 起始角，单位千分之一度，取值 0..359999
 * @param arcLength  正向弧长，单位千分之一度；观测输入取值 1..180000，
 *                   交集结果允许退化单点（弧长 0）
 */
public record CircularArc(int startAngle, int arcLength) {

    /**
     * 圆周刻度总数：360 度 × 1000。
     */
    public static final int MODULO = 360000;

    /**
     * 校验作为观测输入的圆弧取值范围；越界抛出 400 业务异常。
     */
    public static CircularArc ofInput(int startAngle, int arcLength) {
        if (startAngle < 0 || startAngle >= MODULO) {
            throw ApiException.badRequest("startAngle out of range 0..359999: " + startAngle);
        }
        if (arcLength < 1 || arcLength > MODULO / 2) {
            throw ApiException.badRequest("arcLength out of range 1..180000: " + arcLength);
        }
        return new CircularArc(startAngle, arcLength);
    }
}
