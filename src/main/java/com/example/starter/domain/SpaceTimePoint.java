package com.example.starter.domain;

/**
 * 时空段采样点：航线在 {@code atMillis}（epoch 毫秒，UTC）时刻预计位于坐标 (x, y)（米）。
 * 同一航线按时间升序给出 1~N 个采样点，每个采样点落入一个时空桶；
 * 相邻采样点间跨越的桶按题目时空桶模型以采样点归属为准。
 *
 * @param atMillis 预计到达该点的 epoch 毫秒（UTC）
 * @param x        X 坐标，单位米
 * @param y        Y 坐标，单位米
 */
public record SpaceTimePoint(long atMillis, int x, int y) {

    /** 格网边长（米）：坐标除以 1000 向下取整得到单元下标。 */
    public static final int CELL_SIZE_METERS = 1000;

    /** 时间桶边长（毫秒）：10 分钟。 */
    public static final long TIME_BUCKET_MILLIS = 10L * 60L * 1000L;

    /** floor(a/b)，对负数同样向下取整（Java 的 / 向零取整，不能直接使用）。 */
    public static long floorDiv(long a, long b) {
        long q = a / b;
        if ((a % b != 0) && ((a < 0) != (b < 0))) {
            q--;
        }
        return q;
    }

    /** X 方向格网单元下标。 */
    public int cellX() {
        return (int) floorDiv(x, CELL_SIZE_METERS);
    }

    /** Y 方向格网单元下标。 */
    public int cellY() {
        return (int) floorDiv(y, CELL_SIZE_METERS);
    }

    /** 时间桶下标。 */
    public long timeBucket() {
        return floorDiv(atMillis, TIME_BUCKET_MILLIS);
    }
}
