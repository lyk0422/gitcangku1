package com.example.starter.race.domain;

import java.util.List;

/**
 * 绕圈赛事圈次提交不变量的纯逻辑校验，不涉及数据库与事务。
 *
 * <p>规则（违反任一则提交被拒绝，不写入）：
 * <ul>
 *   <li>elapsedMillis 为自起跑开始的非负整数毫秒，取值 0~86400000；</li>
 *   <li>圈号取值 1~总圈数（越界由服务层以400拒绝，此处假定已校验）；</li>
 *   <li>允许乱序收件：只要求按（圈号, 点序）展开的全赛程序列上耗时严格递增，
 *       即同圈 A&lt;B&lt;FINISH，且下一圈 A 严格大于上一圈 FINISH；
 *       新记录必须严格大于已存在的最近前序点耗时，并严格小于已存在的最近后序点耗时。</li>
 * </ul>
 */
public final class LapRules {

    /** 圈次耗时上界（毫秒），含端点：1天。 */
    public static final long MAX_ELAPSED_MILLIS = 86_400_000L;
    /** 绕圈赛事总圈数下界，含端点。 */
    public static final int MIN_TOTAL_LAPS = 2;
    /** 绕圈赛事总圈数上界，含端点。 */
    public static final int MAX_TOTAL_LAPS = 10;

    private LapRules() {
    }

    /**
     * 校验一次圈次点提交（不含圈号范围与重复点检查，二者由服务层分别映射为400与409）。
     *
     * @param existingForRunner 该选手已存在的全部当前圈次记录（任意顺序；更正时须先排除被替换的记录）
     * @param lapNo             本次提交的圈号（从1开始）
     * @param point             本次提交的圈内点
     * @param elapsedMillis     本次提交的累计耗时（自起跑开始的非负整数毫秒）
     * @return 违反不变量时的错误信息；合法时为 null
     */
    public static String validate(
            List<? extends LapPassView> existingForRunner,
            int lapNo,
            LapPoint point,
            long elapsedMillis) {
        if (elapsedMillis < 0L || elapsedMillis > MAX_ELAPSED_MILLIS) {
            return "圈次耗时必须为 0~86400000 的非负整数毫秒";
        }
        int targetSequence = LapPoint.sequence(lapNo, point);
        LapPassView predecessor = null;
        LapPassView successor = null;
        for (LapPassView pass : existingForRunner) {
            int sequence = LapPoint.sequence(pass.lapNo(), pass.point());
            if (sequence < targetSequence) {
                if (predecessor == null
                        || LapPoint.sequence(predecessor.lapNo(), predecessor.point()) < sequence) {
                    predecessor = pass;
                }
            } else if (sequence > targetSequence) {
                if (successor == null
                        || LapPoint.sequence(successor.lapNo(), successor.point()) > sequence) {
                    successor = pass;
                }
            }
        }
        if (predecessor != null && elapsedMillis <= predecessor.elapsedMillis()) {
            return "圈次耗时必须严格大于前一点耗时: lap=" + predecessor.lapNo()
                    + ", point=" + predecessor.point();
        }
        if (successor != null && elapsedMillis >= successor.elapsedMillis()) {
            return "圈次耗时必须严格小于后一点耗时: lap=" + successor.lapNo()
                    + ", point=" + successor.point();
        }
        return null;
    }
}