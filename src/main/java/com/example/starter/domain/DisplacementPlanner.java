package com.example.starter.domain;

import com.example.starter.repo.ReservationPo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 备降预留容量裁决与挤出选择（纯逻辑，不访问数据库）。
 *
 * <p>窗口为 UTC 左闭右开区间，两窗口满足 {@code start < otherEnd && end > otherStart} 即重叠。
 * 新预留进入后，窗口内任一时段的有效占用数不得超过机场容量；超出时只能挤出
 * 尚未起飞（departed=FALSE）、仍 ACTIVE 且优先级严格更低的预留，且必须整体挤出
 * 该预留记录。已起飞预留或同级/更高优先级预留不可挤出，此时裁决不可行（申请返回 409）。</p>
 *
 * <p>选择算法：对窗口内占用超过容量的最早时段，从可挤出候选中选择结束时间最晚的预留
 * （经典区间点覆盖贪心，挤出条数最少且不会只挤掉一部分），直到所有时段占用回到容量内；
 * 若任一时段无足够可挤出候选，则裁决为不可行。</p>
 */
public final class DisplacementPlanner {

    /** 裁决不可行原因：存在无法挤出的已起飞预留占用重叠窗口。 */
    public static final String BLOCKED_BY_DEPARTED = "BLOCKED_BY_DEPARTED";
    /** 裁决不可行原因：剩余重叠占用优先级不低于申请，或可挤出低优先级预留数量不足。 */
    public static final String BLOCKED_BY_PRIORITY = "BLOCKED_BY_PRIORITY";

    private DisplacementPlanner() {
    }

    /**
     * 裁决结果。
     *
     * @param feasible  是否可以合法接纳新预留
     * @param displaced feasible=TRUE 时需要整体挤出的全部预留（空列表表示无需挤出）
     * @param reason    feasible=FALSE 时的原因码（BLOCKED_BY_DEPARTED / BLOCKED_BY_PRIORITY）
     */
    public record Decision(boolean feasible, List<ReservationPo> displaced, String reason) {

        /** 构造可行裁决。 */
        public static Decision feasible(List<ReservationPo> displaced) {
            return new Decision(true, List.copyOf(displaced), null);
        }

        /** 构造不可行裁决。 */
        public static Decision conflict(String reason) {
            return new Decision(false, List.of(), reason);
        }
    }

    /** 两个 UTC 左闭右开窗口是否重叠。 */
    public static boolean overlaps(long start, long end, long otherStart, long otherEnd) {
        return start < otherEnd && end > otherStart;
    }

    /**
     * 在给定机场容量下裁决新预留是否可被接纳。
     *
     * @param capacity 机场窗口容量（&gt;=1，由调用方校验）
     * @param start    新窗口起点（UTC，左闭）
     * @param end      新窗口终点（UTC，右开）
     * @param priority 新预留优先级（数值越大越高）
     * @param existing 与新窗口重叠的全部预留（含已挤出/已起飞，由调用方按机场加锁后读取）
     * @return 可行裁决（含需挤出的预留列表）或不可行裁决（含原因码）
     */
    public static Decision plan(int capacity, long start, long end, int priority,
                                List<ReservationPo> existing) {
        // 仅 ACTIVE 预留参与容量计算；已 DISPLACED 的记录不占容量、不参与裁决
        List<ReservationPo> occupancies = new ArrayList<>();
        for (ReservationPo po : existing) {
            if (ReservationPo.STATUS_ACTIVE.equals(po.status())) {
                occupancies.add(po);
            }
        }

        // 可挤出候选：未起飞且优先级严格更低；同级与更高优先级任何情况下都不可挤出
        List<ReservationPo> candidates = new ArrayList<>();
        for (ReservationPo po : occupancies) {
            if (!po.departed() && po.priority() < priority) {
                candidates.add(po);
            }
        }

        // 新窗口内占用量只在既有窗口边界处变化；收集与新窗口相交部分的全部分段点
        Set<Long> points = new TreeSet<>();
        points.add(start);
        for (ReservationPo po : occupancies) {
            long segStart = Math.max(start, po.windowStart());
            long segEnd = Math.min(end, po.windowEnd());
            if (segStart < segEnd) {
                points.add(segStart);
                points.add(segEnd);
            }
        }
        List<Long> boundaries = new ArrayList<>(points);

        Set<String> selectedIds = new TreeSet<>();
        List<ReservationPo> selected = new ArrayList<>();

        // 逐分段裁决：新预留全程 +1 占用；每个分段占用必须 <= capacity
        for (int i = 0; i + 1 < boundaries.size(); i++) {
            long segStart = boundaries.get(i);
            long segEnd = boundaries.get(i + 1);
            if (segStart >= segEnd) {
                continue;
            }
            // 当前分段仍占用容量的预留（尚未被选中挤出）+ 新预留自身 1 个
            int over = activeCount(occupancies, selectedIds, segStart) + 1 - capacity;
            while (over > 0) {
                ReservationPo pick = chooseCandidate(candidates, selectedIds, segStart);
                if (pick == null) {
                    // 该分段存在无法挤出的占用：优先报告已起飞阻挡
                    String reason = hasDepartedBlocker(occupancies, segStart)
                            ? BLOCKED_BY_DEPARTED : BLOCKED_BY_PRIORITY;
                    return Decision.conflict(reason);
                }
                selectedIds.add(pick.reservationId());
                selected.add(pick);
                over--;
            }
        }
        // 按窗口起点、reservationId 排序，保证替换事件写入顺序确定
        selected.sort(Comparator.comparingLong(ReservationPo::windowStart)
                .thenComparing(ReservationPo::reservationId));
        return Decision.feasible(selected);
    }

    /** 统计某时刻未被选中挤出的 ACTIVE 占用数。 */
    private static int activeCount(List<ReservationPo> occupancies, Set<String> selectedIds,
                                   long at) {
        int count = 0;
        for (ReservationPo po : occupancies) {
            if (!selectedIds.contains(po.reservationId())
                    && po.windowStart() <= at && at < po.windowEnd()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 在覆盖该时刻的可挤出候选中选择窗口结束最晚的一条（挤出一条即可缓解最多后续分段；
     * 结束时间相同时取 reservationId 较大者，保证选择确定）。
     */
    private static ReservationPo chooseCandidate(List<ReservationPo> candidates,
                                                 Set<String> selectedIds, long at) {
        ReservationPo best = null;
        for (ReservationPo po : candidates) {
            if (selectedIds.contains(po.reservationId())) {
                continue;
            }
            if (po.windowStart() <= at && at < po.windowEnd()) {
                if (best == null || po.windowEnd() > best.windowEnd()
                        || (po.windowEnd() == best.windowEnd()
                        && po.reservationId().compareTo(best.reservationId()) > 0)) {
                    best = po;
                }
            }
        }
        return best;
    }

    private static boolean hasDepartedBlocker(List<ReservationPo> occupancies, long at) {
        for (ReservationPo po : occupancies) {
            if (po.departed() && po.windowStart() <= at && at < po.windowEnd()) {
                return true;
            }
        }
        return false;
    }
}
