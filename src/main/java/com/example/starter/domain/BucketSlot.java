package com.example.starter.domain;

/**
 * 时空桶：一个网格单元与一个 15 分钟 UTC 时间桶的组合，是容量配置与占用的最小单位。
 *
 * @param cellX       网格单元 X 索引
 * @param cellY       网格单元 Y 索引
 * @param bucketStart 时间桶起始时刻，epoch 毫秒（UTC），15 分钟对齐
 */
public record BucketSlot(int cellX, int cellY, long bucketStart)
        implements Comparable<BucketSlot> {

    @Override
    public int compareTo(BucketSlot other) {
        int cmp = Long.compare(this.bucketStart, other.bucketStart);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(this.cellX, other.cellX);
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(this.cellY, other.cellY);
    }
}
