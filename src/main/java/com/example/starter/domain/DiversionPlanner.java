package com.example.starter.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * 备降机场窗口容量裁决纯逻辑（不依赖数据库与时钟，便于单元测试）。
 *
 * <p>窗口为 UTC 左闭右开 {@code [start, end)}，两窗口重叠当且仅当
 * {@code a.start < b.end && b.start < a.end}。机场容量按时间轴逐段计数：
 * 新申请加入后，任何时段占用数都不得超过容量。</p>
 *
 * <p>挤出规则（整体挤出，不可只挤出一部分）：</p>
 * <ul>
 *   <li>只能挤出与新窗口重叠、尚未起飞且优先级严格更低的预留；</li>
 *   <li>相同优先级与更高优先级预留绝不能被挤出：当某个超载时段已没有可挤的
 *       严格低优先级预留时（必然还剩同级/高级预留），申请直接冲突；</li>
 *   <li>需要挤出时按经典区间贪心：在最左侧超载时段选择结束时间最晚的可挤低优先级
 *       预留整体释放（覆盖后续最多超载时段，挤出数量最小），相同结束时间按
 *       预留标识排序保证结果确定可复现；</li>
 *   <li>被选中的预留按其整个窗口整体释放，不存在“只挤出部分航线”。</li>
 * </ul>
 */
public final class DiversionPlanner {

    private DiversionPlanner() {
    }

    /**
     * 一个当前占用容量的预留。已起飞（DEPARTED）预留仍占用窗口容量，但
     * displaceable=false：绝不能被挤出，相当于不可移动的阻挡者；
     * 已被挤出（DISPLACED）的预留不再占用容量，不应出现在输入中。
     *
     * @param reservationId 预留标识
     * @param routeId       航线标识
     * @param priority      优先级，数值越大越高
     * @param start         UTC 窗口开始（含），epoch 毫秒
     * @param end           UTC 窗口结束（不含），epoch 毫秒
     * @param displaceable  是否可被合法挤出：未起飞为 true，已起飞为 false
     */
    public record Occupant(String reservationId, String routeId, int priority,
                           long start, long end, boolean displaceable) {
    }

    /**
     * 裁决结果。
     *
     * @param conflict              是否必须拒绝申请
     * @param blockingReservationId 冲突时阻挡申请的同级/高级预留标识；不冲突为 null
     * @param displaced             必须整体挤出的预留（按 reservationId 字典序）；冲突时为空
     */
    public record Plan(boolean conflict, String blockingReservationId, List<Occupant> displaced) {

        static Plan conflict(String blockingReservationId) {
            return new Plan(true, blockingReservationId, List.of());
        }

        static Plan accepted(List<Occupant> displaced) {
            List<Occupant> sorted = new ArrayList<>(displaced);
            sorted.sort(Comparator.comparing(Occupant::reservationId));
            return new Plan(false, null, List.copyOf(sorted));
        }
    }

    /** 扫描线事件：同坐标先处理结束（-1）后处理开始（+1），与左闭右开语义一致。 */
    private record Event(long time, int delta, Occupant occupant) {
    }

    /**
     * 在给定容量与当前占用下，裁决新窗口申请。
     *
     * @param capacity    机场窗口容量，必须为正
     * @param current     当前未起飞占用（调用方保证只含未起飞状态）
     * @param newPriority 新申请优先级
     * @param newStart    新窗口开始（含）
     * @param newEnd      新窗口结束（不含）
     * @return 裁决结果；接受时携带需要整体挤出的预留清单
     */
    public static Plan plan(int capacity, List<Occupant> current,
                            int newPriority, long newStart, long newEnd) {
        if (capacity <= 0 || newStart >= newEnd) {
            throw new IllegalArgumentException("容量必须为正且新窗口必须非退化");
        }
        Occupant incoming = new Occupant(NEW_ID, NEW_ID, newPriority, newStart, newEnd, false);

        TreeSet<Event> events = new TreeSet<>(
                Comparator.comparingLong(Event::time)
                        .thenComparingInt(Event::delta)
                        .thenComparing(e -> e.occupant().reservationId()));
        for (Occupant o : current) {
            events.add(new Event(o.start(), 1, o));
            events.add(new Event(o.end(), -1, o));
        }
        events.add(new Event(newStart, 1, incoming));
        events.add(new Event(newEnd, -1, incoming));

        // 当前时刻之后处于活跃状态的占用（已应用该坐标的全部 start/end 事件）
        List<Occupant> active = new ArrayList<>();
        List<Occupant> marked = new ArrayList<>();
        long cursor = Long.MIN_VALUE;
        boolean first = true;
        for (Event event : events) {
            if (!first && event.time() > cursor) {
                // (cursor, event.time) 内活跃集合恒定；只需检查落在新窗口内的时段
                String blocker = enforceSegment(capacity, newPriority, active, marked);
                if (blocker != null) {
                    return Plan.conflict(blocker);
                }
            }
            first = false;
            if (event.delta() == 1) {
                active.add(event.occupant());
            } else {
                active.remove(event.occupant());
            }
            cursor = event.time();
        }
        return Plan.accepted(marked);
    }

    /**
     * 检查一个活跃集合恒定的时段。仅当新申请在该时段活跃时才产生容量压力：
     * 未挤出的既存占用加新申请不得超过容量。超载时在该时段可挤（严格低优先级、
     * 未标记）预留中贪心挤出结束时间最晚者；若已无可挤预留，则必然存在
     * 同级/高级预留阻挡，返回其标识。
     */
    private static String enforceSegment(int capacity, int newPriority,
                                         List<Occupant> activeSegment, List<Occupant> marked) {
        boolean incomingActive = false;
        for (Occupant o : activeSegment) {
            if (NEW_ID.equals(o.reservationId())) {
                incomingActive = true;
                break;
            }
        }
        if (!incomingActive) {
            return null;
        }
        while (true) {
            List<Occupant> unmarked = new ArrayList<>();
            for (Occupant o : activeSegment) {
                if (!NEW_ID.equals(o.reservationId()) && !marked.contains(o)) {
                    unmarked.add(o);
                }
            }
            if (unmarked.size() + 1 <= capacity) {
                return null;
            }
            // 仍超载：先在可挤（可挤出且严格低优先级）预留中贪心挤出结束时间最晚者，
            // 相同结束时间按标识排序，保证结果确定可复现。已起飞预留 displaceable=false，
            // 即使优先级更低也绝不能被挤出。
            Occupant victim = unmarked.stream()
                    .filter(Occupant::displaceable)
                    .filter(o -> o.priority() < newPriority)
                    .min(Comparator.comparingLong(Occupant::end).reversed()
                            .thenComparing(Occupant::reservationId))
                    .orElse(null);
            if (victim == null) {
                // 可挤预留已耗尽仍超载：超载必然由不能挤出的预留造成
                // （同级/高级占用，或已起飞的低优先级预留）
                return unmarked.stream()
                        .filter(o -> !o.displaceable() || o.priority() >= newPriority)
                        .map(Occupant::reservationId)
                        .min(String::compareTo)
                        .orElseThrow(() -> new IllegalStateException("超载时段必须存在不可挤出占用"));
            }
            marked.add(victim);
        }
    }

    private static final String NEW_ID = "__new__";
}
