package com.example.starter.domain;

/**
 * 规范化后的时空桶（航线占用的最小容量单元）。
 *
 * @param cellX      格网单元 X 下标
 * @param cellY      格网单元 Y 下标
 * @param timeBucket 10 分钟时间桶下标（UTC）
 */
public record SpaceBucket(int cellX, int cellY, long timeBucket)
        implements Comparable<SpaceBucket> {

    /** 规范化文本："cellX,cellY,timeBucket"。 */
    public String canonical() {
        return cellX + "," + cellY + "," + timeBucket;
    }

    /** 解析规范化文本。 */
    public static SpaceBucket parse(String text) {
        String[] parts = text.split(",");
        return new SpaceBucket(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Long.parseLong(parts[2]));
    }

    @Override
    public int compareTo(SpaceBucket o) {
        int c = Integer.compare(cellX, o.cellX);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(cellY, o.cellY);
        if (c != 0) {
            return c;
        }
        return Long.compare(timeBucket, o.timeBucket);
    }
}
