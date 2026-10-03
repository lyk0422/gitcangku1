package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 芯片归属时间线的纯逻辑：由更换链构建左闭右开区间并判定任意时刻的归属芯片，
 * 不涉及数据库与事务。
 *
 * <p>规则：
 * <ul>
 *   <li>首次更换的 oldChip 固化该选手的初始芯片，覆盖 (-∞, 首次生效时刻)；</li>
 *   <li>第 i 次更换的 newChip 覆盖 [第i次生效时刻, 第i+1次生效时刻)，最后一次覆盖到 +∞；</li>
 *   <li>区间左闭右开：读数提交时刻等于生效时刻时归属新芯片；</li>
 *   <li>生效时刻必须严格递增；非首次更换的 oldChip 必须等于上一次更换的 newChip。</li>
 * </ul>
 */
public final class ChipTimeline {

    private ChipTimeline() {
    }

    /** 芯片区间：左闭右开 [effectiveFrom, effectiveTo)；null 表示对应方向无界。 */
    public record ChipInterval(String chipId, Long effectiveFrom, Long effectiveTo) {
    }

    /** 芯片更换记录视图。 */
    public interface ChipReplacementView {
        /** 旧芯片ID。 */
        String oldChip();

        /** 新芯片ID。 */
        String newChip();

        /** 新芯片生效时刻，Unix毫秒时间戳（UTC）。 */
        long effectiveAt();
    }

    /**
     * 校验一次新更换相对既有更换链是否合法。
     *
     * @param existing    该选手既有更换记录（任意顺序，方法内按生效时刻排序）
     * @param oldChip     本次声明的旧芯片
     * @param newChip     本次声明的新芯片
     * @param effectiveAt 本次生效时刻（UTC毫秒）
     * @return 违反规则时的错误信息（含实际值与要求值）；合法时为 null
     */
    public static String validateReplacement(
            List<? extends ChipReplacementView> existing,
            String oldChip,
            String newChip,
            long effectiveAt) {
        if (oldChip.equals(newChip)) {
            return "新旧芯片不能相同: " + newChip;
        }
        List<ChipReplacementView> ordered = orderedByEffectiveAt(existing);
        if (ordered.isEmpty()) {
            return null;
        }
        ChipReplacementView last = ordered.get(ordered.size() - 1);
        if (!last.newChip().equals(oldChip)) {
            return "旧芯片与当前绑定不一致: 当前=" + last.newChip() + ", 声明=" + oldChip;
        }
        if (effectiveAt <= last.effectiveAt()) {
            return "生效时刻必须严格晚于上次更换: 上次=" + last.effectiveAt()
                    + ", 本次=" + effectiveAt;
        }
        return null;
    }

    /**
     * 由更换链构建左闭右开区间（按生效时刻升序）。
     * 空链返回空列表（该选手尚无芯片概念，读数归属为 null）。
     */
    public static List<ChipInterval> buildIntervals(
            List<? extends ChipReplacementView> replacements) {
        List<ChipReplacementView> ordered = orderedByEffectiveAt(replacements);
        List<ChipInterval> intervals = new ArrayList<>(ordered.size() + 1);
        if (ordered.isEmpty()) {
            return intervals;
        }
        ChipReplacementView first = ordered.get(0);
        intervals.add(new ChipInterval(first.oldChip(), null, first.effectiveAt()));
        for (int i = 0; i < ordered.size(); i++) {
            ChipReplacementView current = ordered.get(i);
            Long to = i + 1 < ordered.size() ? ordered.get(i + 1).effectiveAt() : null;
            intervals.add(new ChipInterval(current.newChip(), current.effectiveAt(), to));
        }
        return intervals;
    }

    /**
     * 判定某时刻归属的芯片（左闭右开）。
     *
     * @return 归属芯片ID；无区间覆盖（空链）时为 null
     */
    public static String chipAt(List<ChipInterval> intervals, long timestamp) {
        for (ChipInterval interval : intervals) {
            boolean afterFrom = interval.effectiveFrom() == null
                    || timestamp >= interval.effectiveFrom();
            boolean beforeTo = interval.effectiveTo() == null
                    || timestamp < interval.effectiveTo();
            if (afterFrom && beforeTo) {
                return interval.chipId();
            }
        }
        return null;
    }

    /** 当前生效芯片：链尾更换的 newChip；空链为 null。 */
    public static String currentChip(List<? extends ChipReplacementView> replacements) {
        List<ChipReplacementView> ordered = orderedByEffectiveAt(replacements);
        return ordered.isEmpty() ? null : ordered.get(ordered.size() - 1).newChip();
    }

    private static List<ChipReplacementView> orderedByEffectiveAt(
            List<? extends ChipReplacementView> replacements) {
        List<ChipReplacementView> ordered = new ArrayList<>(replacements);
        ordered.sort(java.util.Comparator.comparingLong(ChipReplacementView::effectiveAt));
        return ordered;
    }
}
