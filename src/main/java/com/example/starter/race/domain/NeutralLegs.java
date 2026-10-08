package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 中立赛段（转运区间）的纯逻辑：区间定义校验与净竞技用时计算，不涉及数据库与事务。
 *
 * <p>规则：
 * <ul>
 *   <li>计时前固定 1~5 个中立区间，每项给出有序检查点的起止序号；
 *       起点可为 0（发枪零点），终点必须是更靠后的必经检查点；</li>
 *   <li>区间不得交叠，首尾相接（下一区间起点等于前一区间终点）合法；</li>
 *   <li>每个中立端点必须有当前有效计时才参与扣时，禁止漏点后按估计时长扣除；</li>
 *   <li>净竞技用时=原始完赛耗时-全部区间（终点累计时刻-起点累计时刻）之和+生效加时，
 *       必须为正值。</li>
 * </ul>
 */
public final class NeutralLegs {

    /** 中立区间个数上界，含端点。 */
    public static final int MAX_LEGS = 5;

    private NeutralLegs() {
    }

    /**
     * 校验一组中立区间定义。
     *
     * @param legs            区间定义（任意顺序，内部按起点排序后校验）
     * @param checkpointCount 赛事已配置检查点总数；必须已配置检查点
     * @return 违反规则时的错误信息；合法时为 null
     */
    public static String validateLegs(List<? extends NeutralLegView> legs, int checkpointCount) {
        if (checkpointCount < 1) {
            return "必须先配置检查点，才能启用中立赛段模式";
        }
        if (legs.isEmpty() || legs.size() > MAX_LEGS) {
            return "中立区间数量必须在 1~5 之间";
        }
        List<NeutralLegView> ordered = new ArrayList<>(legs);
        ordered.sort(Comparator.comparingInt(NeutralLegView::startPosition)
                .thenComparingInt(NeutralLegView::endPosition));
        int previousEnd = -1;
        for (NeutralLegView leg : ordered) {
            if (leg.startPosition() < 0) {
                return "中立区间起点不能为负: " + leg.startPosition();
            }
            if (leg.startPosition() >= checkpointCount) {
                return "中立区间起点必须是发枪零点或已配置检查点: position=" + leg.startPosition();
            }
            if (leg.endPosition() <= leg.startPosition()) {
                return "中立区间终点必须严格大于起点: " + leg.startPosition()
                        + "->" + leg.endPosition();
            }
            if (leg.endPosition() > checkpointCount) {
                return "中立区间终点必须是已配置的必经检查点: position=" + leg.endPosition();
            }
            if (leg.startPosition() < previousEnd) {
                return "中立区间不得交叠: position=" + leg.startPosition();
            }
            previousEnd = leg.endPosition();
        }
        return null;
    }

    /**
     * 判断指定区间的两个端点是否都有当前有效计时（起点为发枪零点时只需终点）。
     *
     * @param leg                中立区间
     * @param elapsedByPosition  该选手各检查点顺序对应的当前累计耗时
     */
    public static boolean endpointsCovered(
            NeutralLegView leg, Map<Integer, Long> elapsedByPosition) {
        if (leg.startPosition() > 0 && !elapsedByPosition.containsKey(leg.startPosition())) {
            return false;
        }
        return elapsedByPosition.containsKey(leg.endPosition());
    }

    /**
     * 计算单个区间的扣时（终点累计时刻-起点累计时刻）；起点为发枪零点时起点按 0 计。
     * 调用前须保证 {@link #endpointsCovered} 为 true。
     */
    public static long legDeduction(NeutralLegView leg, Map<Integer, Long> elapsedByPosition) {
        long start = leg.startPosition() == 0
                ? 0L : elapsedByPosition.get(leg.startPosition());
        return elapsedByPosition.get(leg.endPosition()) - start;
    }

    /**
     * 计算全部中立区间的扣时合计；任一端点缺失计时返回 null（不做估计扣除）。
     */
    public static Long totalDeduction(
            List<? extends NeutralLegView> legs, Map<Integer, Long> elapsedByPosition) {
        long total = 0L;
        for (NeutralLegView leg : legs) {
            if (!endpointsCovered(leg, elapsedByPosition)) {
                return null;
            }
            total += legDeduction(leg, elapsedByPosition);
        }
        return total;
    }

    /**
     * 净竞技用时不为正值时的错误信息；合法时返回 null。
     * 正常数据下（分段严格递增且小于完赛耗时）净值恒为正，此为防御性校验。
     */
    public static String netTimeViolation(long netTimeMs) {
        if (netTimeMs <= 0L) {
            return "净竞技用时必须为正值: " + netTimeMs;
        }
        return null;
    }
}
