package com.example.starter.water;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 简化水力模型（仅合成参数，不模拟真实泵站）。
 *
 * <p>规则：每份申请为一条恒定流量曲线（整分钟左闭右开时段）加一条有序无重复渠段路径；
 * 渠段损失 = k × 该段同时总流量^2；将全部已批曲线按起止切片后，任一申请在其时段内
 * 每个时片的路径损失之和（沿路径各段损失相加）不得超过其可用水头。</p>
 *
 * <p>全部使用 long 精确整数运算，溢出抛 {@link ArithmeticException}，由调用方映射为业务失败。</p>
 */
public final class HydraulicCalculator {

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;

    private HydraulicCalculator() {
    }

    /** 参与计算的申请：恒定流量曲线 + 有序路径 + 可用水头。 */
    public record Request(long allocationId, String allocationKey, long flowRate,
                          long startNanos, long endNanos, long availableHead, List<Segment> path) {
    }

    /** 路径上的渠段及其当前阻力参数与版本。 */
    public record Segment(long segmentId, String segmentKey, long resistance, long version) {
    }

    /** 时片核算事实：某申请路径上某渠段在某时片的同时总流量、阻力参数与段损失。 */
    public record SliceFact(long allocationId, long segmentId, long sliceStartNanos, long sliceEndNanos,
                            long totalFlow, long resistance, long resistanceVersion, long loss) {
    }

    /** 水头不足：携带越限申请、时片与损失明细。 */
    public static class HeadExceededException extends RuntimeException {
        private final String allocationKey;
        private final long sliceStartNanos;
        private final long loss;
        private final long availableHead;

        public HeadExceededException(String allocationKey, long sliceStartNanos, long loss, long availableHead) {
            super("申请 " + allocationKey + " 时片起点 " + sliceStartNanos + " 路径损失 " + loss
                    + " 超过可用水头 " + availableHead);
            this.allocationKey = allocationKey;
            this.sliceStartNanos = sliceStartNanos;
            this.loss = loss;
            this.availableHead = availableHead;
        }

        public String allocationKey() {
            return allocationKey;
        }

        public long sliceStartNanos() {
            return sliceStartNanos;
        }

        public long loss() {
            return loss;
        }

        public long availableHead() {
            return availableHead;
        }
    }

    /** 整分钟对齐的时段分钟数（调用方保证整分钟且 endNanos > startNanos）。 */
    public static long minutesBetween(long startNanos, long endNanos) {
        return Math.subtractExact(endNanos, startNanos) / NANOS_PER_MINUTE;
    }

    /** 判断时段是否为整分钟左闭右开区间。 */
    public static boolean isMinuteAligned(long nanos) {
        return Math.floorMod(nanos, NANOS_PER_MINUTE) == 0;
    }

    /**
     * 对给定申请集合（已批 + 候选）重算全部时片事实。
     * 任一申请在任一时片路径损失之和超过其可用水头时抛 {@link HeadExceededException}。
     * 空集合返回空列表。
     */
    public static List<SliceFact> recompute(List<Request> requests) {
        TreeSet<Long> boundaries = new TreeSet<>();
        for (Request request : requests) {
            boundaries.add(request.startNanos());
            boundaries.add(request.endNanos());
        }
        List<Long> points = new ArrayList<>(boundaries);
        List<SliceFact> facts = new ArrayList<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            long sliceStart = points.get(i);
            long sliceEnd = points.get(i + 1);
            for (Request request : requests) {
                if (request.startNanos() > sliceStart || request.endNanos() < sliceEnd) {
                    continue;
                }
                long pathLoss = 0;
                for (Segment segment : request.path()) {
                    long totalFlow = totalFlowOn(requests, segment.segmentId(), sliceStart, sliceEnd);
                    long loss = Math.multiplyExact(segment.resistance(),
                            Math.multiplyExact(totalFlow, totalFlow));
                    pathLoss = Math.addExact(pathLoss, loss);
                    facts.add(new SliceFact(request.allocationId(), segment.segmentId(), sliceStart, sliceEnd,
                            totalFlow, segment.resistance(), segment.version(), loss));
                }
                if (pathLoss > request.availableHead()) {
                    throw new HeadExceededException(request.allocationKey(), sliceStart, pathLoss,
                            request.availableHead());
                }
            }
        }
        return facts;
    }

    /** 时片内某渠段的同时总流量：覆盖该时片且路径含该段的全部申请流量之和。 */
    private static long totalFlowOn(List<Request> requests, long segmentId, long sliceStart, long sliceEnd) {
        long total = 0;
        for (Request request : requests) {
            if (request.startNanos() > sliceStart || request.endNanos() < sliceEnd) {
                continue;
            }
            for (Segment segment : request.path()) {
                if (segment.segmentId() == segmentId) {
                    total = Math.addExact(total, request.flowRate());
                    break;
                }
            }
        }
        return total;
    }
}
