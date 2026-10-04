package com.example.starter.race.domain;

import java.util.List;

/**
 * 绕圈赛事圈次提交不变量的纯逻辑校验，不涉及数据库与事务。
 *
 * <p>规则（违反任一则提交/更正被拒绝，不写入）：
 * <ul>
 *   <li>elapsedMillis 为自起跑开始的非负整数毫秒；</li>
 *   <li>同一选手同一圈同一点位最多一条当前记录（更正走追加历史，不在此覆盖）；</li>
 *   <li>允许乱序收件，但按（圈号, 点位顺序）全序查看时耗时必须严格递增：
 *       同圈 A&lt;B&lt;FINISH，下一圈 A 严格大于上一圈 FINISH。</li>
 * </ul>
 */
public final class LapRules {

    private LapRules() {
    }

    /**
     * 校验一次圈次点位提交或更正。
     *
     * @param existingForRunner 该选手已存在的全部当前记录（任意顺序；
     *                          更正时由调用方先剔除被更正的那条）
     * @param lapNumber         本次写入的圈号（范围 1~总圈数 由调用方校验）
     * @param point             本次写入的点位
     * @param elapsedMillis     本次写入的累计耗时（毫秒）
     * @return 违反不变量时的错误信息；合法时为 null
     */
    public static String validate(
            List<? extends LapTimingView> existingForRunner,
            int lapNumber,
            LapPoint point,
            long elapsedMillis) {
        if (elapsedMillis < 0L) {
            return "圈次耗时必须为非负整数毫秒";
        }
        for (LapTimingView timing : existingForRunner) {
            if (timing.lapNumber() == lapNumber && timing.point() == point) {
                return "同一选手同一圈同一点位最多一条当前记录: L" + lapNumber + "-" + point;
            }
        }
        for (LapTimingView timing : existingForRunner) {
            int cmp = compareOrder(timing.lapNumber(), timing.point(), lapNumber, point);
            if (cmp < 0 && timing.elapsedMillis() >= elapsedMillis) {
                return "圈次耗时必须严格大于前序点位: L" + timing.lapNumber()
                        + "-" + timing.point() + "=" + timing.elapsedMillis();
            }
            if (cmp > 0 && timing.elapsedMillis() <= elapsedMillis) {
                return "圈次耗时必须严格小于后序点位: L" + timing.lapNumber()
                        + "-" + timing.point() + "=" + timing.elapsedMillis();
            }
        }
        return null;
    }

    /**
     * 校验一组当前记录是否按（圈号, 点位顺序）全序严格递增（封榜前的防御性复核）。
     *
     * @param timings 单选手的全部当前记录
     * @return 存在逆序时为 true
     */
    public static boolean hasInversion(List<? extends LapTimingView> timings) {
        List<? extends LapTimingView> ordered = timings.stream()
                .sorted((left, right) -> compareOrder(
                        left.lapNumber(), left.point(), right.lapNumber(), right.point()))
                .toList();
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).elapsedMillis() <= ordered.get(i - 1).elapsedMillis()) {
                return true;
            }
        }
        return false;
    }

    /** （圈号, 点位顺序）全序比较：先圈号后点位顺序。 */
    public static int compareOrder(LapTimingView left, LapTimingView right) {
        return compareOrder(left.lapNumber(), left.point(), right.lapNumber(), right.point());
    }

    private static int compareOrder(int lapA, LapPoint pointA, int lapB, LapPoint pointB) {
        if (lapA != lapB) {
            return Integer.compare(lapA, lapB);
        }
        return Integer.compare(pointA.sequence(), pointB.sequence());
    }
}