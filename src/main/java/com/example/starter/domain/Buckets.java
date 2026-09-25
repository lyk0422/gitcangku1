package com.example.starter.domain;

/**
 * 时空桶计算：固定 20 米网格单元、15 分钟 UTC 时间桶对齐与相邻连续性判定。
 */
public final class Buckets {

    /** 网格边长（米）。 */
    public static final int CELL_SIZE_METERS = 20;

    /** 时间桶长度（毫秒）：15 分钟。 */
    public static final long BUCKET_SIZE_MILLIS = 15L * 60L * 1000L;

    private Buckets() {
    }

    /** 坐标到网格单元索引（负坐标使用 floorDiv，保证单元连续无重叠）。 */
    public static int cellIndex(int coordinateMeters) {
        return Math.floorDiv(coordinateMeters, CELL_SIZE_METERS);
    }

    /** 任意时刻向下对齐到所属 15 分钟 UTC 时间桶起点。 */
    public static long alignToBucket(long epochMillis) {
        return Math.floorDiv(epochMillis, BUCKET_SIZE_MILLIS) * BUCKET_SIZE_MILLIS;
    }

    /**
     * 判断两个网格单元是否相邻路径连续：同一单元或共享边/顶点
     *（切比雪夫距离 ≤ 1，即 8 邻接）。
     */
    public static boolean cellsAdjacent(Cell a, Cell b) {
        return cellsAdjacent(a.x(), a.y(), b.x(), b.y());
    }

    /** 坐标形式的网格单元 8 邻接判定。 */
    public static boolean cellsAdjacent(int ax, int ay, int bx, int by) {
        return Math.abs(ax - bx) <= 1 && Math.abs(ay - by) <= 1;
    }

    /**
     * 判断两个时间桶是否相邻路径连续：同一桶或相邻的 15 分钟桶。
     */
    public static boolean bucketsAdjacent(long bucketStartA, long bucketStartB) {
        long diff = Math.abs(bucketStartA - bucketStartB);
        return diff == 0L || diff == BUCKET_SIZE_MILLIS;
    }
}
