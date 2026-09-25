package com.example.starter.domain;

/**
 * 时空桶引用：空域单元 + 15 分钟 UTC 时间桶起点。
 *
 * @param cellId      空域单元标识，格式 gx:gy（1000m×1000m 网格坐标）
 * @param bucketStart 15 分钟 UTC 时间桶起点，epoch 秒，900 的整数倍
 */
public record BucketRef(String cellId, long bucketStart) {

    /** 时间桶宽度，单位秒（15 分钟）。 */
    public static final long BUCKET_SECONDS = 900L;

    /** 空域单元边长，单位米。 */
    public static final long CELL_SIZE_METERS = 1000L;

    /** 计算坐标所属网格单元的 X 向索引。 */
    public static long cellIndex(long coordinate) {
        return Math.floorDiv(coordinate, CELL_SIZE_METERS);
    }

    /** 由网格索引构造单元标识。 */
    public static String cellId(long gx, long gy) {
        return gx + ":" + gy;
    }

    /** 计算时刻（epoch 秒，可为小数）所属的 15 分钟桶起点。 */
    public static long bucketOf(double epochSeconds) {
        return (long) Math.floor(epochSeconds / BUCKET_SECONDS) * BUCKET_SECONDS;
    }
}
