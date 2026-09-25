package com.example.starter.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 航线折线在固定网格（1000m×1000m）上的有序穿越序列计算。
 *
 * <p>从起飞时刻起按恒定地速沿折线飞行，依次记录进入的每个空域单元
 * 及进入时刻所属的 15 分钟 UTC 时间桶。连续重复单元只保留一次；
 * 同一单元被多次穿越（序列中不相邻）时各算一段。</p>
 */
public final class Trajectory {

    private Trajectory() {
    }

    /**
     * 穿越序列中的一段。
     *
     * @param cellId         空域单元标识
     * @param bucketStart    进入时刻所属的 15 分钟桶起点，epoch 秒
     * @param enterEpochSec  进入该单元的时刻，epoch 秒（UTC）
     */
    public record Leg(String cellId, long bucketStart, double enterEpochSec) {
    }

    /**
     * 计算折线的有序穿越序列。
     *
     * @param points        有序航点（至少 2 个）
     * @param departureTime 起飞时刻，epoch 秒（UTC）
     * @param speedMps      恒定地速，米/秒，必须 &gt; 0
     * @return 有序穿越段列表，索引即穿越序号 seq
     */
    public static List<Leg> compute(List<Point> points, long departureTime, double speedMps) {
        if (speedMps <= 0.0d) {
            throw new IllegalArgumentException("speedMps 必须为正数");
        }
        List<Leg> legs = new ArrayList<>();
        double flownMeters = 0.0d;
        for (int i = 0; i + 1 < points.size(); i++) {
            Point a = points.get(i);
            Point b = points.get(i + 1);
            double dx = (double) b.x() - a.x();
            double dy = (double) b.y() - a.y();
            double segLen = Math.hypot(dx, dy);
            if (segLen == 0.0d) {
                continue;
            }
            // 收集段内所有网格边界穿越参数 t（含端点 0、1）
            TreeSet<Double> cuts = new TreeSet<>();
            cuts.add(0.0d);
            cuts.add(1.0d);
            addBoundaryCuts(cuts, a.x(), dx, segLen);
            addBoundaryCuts(cuts, a.y(), dy, segLen);
            List<Double> ts = new ArrayList<>(cuts);
            for (int k = 0; k + 1 < ts.size(); k++) {
                double t0 = ts.get(k);
                double t1 = ts.get(k + 1);
                // 子区间中点确定所属单元，避免边界归属歧义
                double mid = (t0 + t1) / 2.0d;
                long gx = BucketRef.cellIndex(Math.round(a.x() + mid * dx));
                long gy = BucketRef.cellIndex(Math.round(a.y() + mid * dy));
                String cellId = BucketRef.cellId(gx, gy);
                double enterSec = departureTime + (flownMeters + t0 * segLen) / speedMps;
                if (!legs.isEmpty() && legs.get(legs.size() - 1).cellId().equals(cellId)) {
                    continue;
                }
                legs.add(new Leg(cellId, BucketRef.bucketOf(enterSec), enterSec));
            }
            flownMeters += segLen;
        }
        return legs;
    }

    /** 收集线段在一维上穿越网格边界（坐标为 1000 的整数倍）的参数 t。 */
    private static void addBoundaryCuts(TreeSet<Double> cuts, long start, double delta, double segLen) {
        if (delta == 0.0d) {
            return;
        }
        double end = start + delta;
        long lo = (long) Math.ceil(Math.min(start, end) / (double) BucketRef.CELL_SIZE_METERS);
        long hi = (long) Math.floor(Math.max(start, end) / (double) BucketRef.CELL_SIZE_METERS);
        for (long k = lo; k <= hi; k++) {
            double boundary = k * (double) BucketRef.CELL_SIZE_METERS;
            double t = (boundary - start) / delta;
            // 排除恰好落在端点上的边界（端点已由 0/1 覆盖，归属由中点判定）
            if (t > 1e-9 && t < 1.0d - 1e-9) {
                cuts.add(t);
            }
        }
    }
}
