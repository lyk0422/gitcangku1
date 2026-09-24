package com.example.starter.race.domain;

import java.util.List;

/**
 * 分段提交不变量的纯逻辑校验，不涉及数据库与事务。
 *
 * <p>规则（违反任一则提交被拒绝，不写入）：
 * <ul>
 *   <li>elapsedMillis 取值 1~86400000；</li>
 *   <li>已有原始完赛耗时（非 null）时，elapsedMillis 必须严格小于它；
 *       尚无完赛计时的在途选手允许提交分段（退赛 DNF 场景要求）；</li>
 *   <li>同一选手同一检查点最多一条；</li>
 *   <li>允许乱序到达，但按检查点顺序查看时耗时必须严格递增：
 *       新记录必须严格大于已存在的最近前序检查点耗时，
 *       并严格小于已存在的最近后序检查点耗时。</li>
 * </ul>
 */
public final class CheckpointRules {

    /** 分段耗时上界（毫秒），含端点：1天。 */
    public static final long MAX_ELAPSED_MILLIS = 86_400_000L;

    private CheckpointRules() {
    }

    /**
     * 校验一次分段提交。
     *
     * @param existingForRunner 该选手已存在的全部分段记录（任意顺序）
     * @param targetPosition    本次提交检查点的顺序
     * @param elapsedMillis     本次提交的累计耗时
     * @param finishTimeMs      该选手已有原始完赛耗时；null 表示尚无完赛计时（在途，允许提交）
     * @return 违反不变量时的错误信息；合法时为 null
     */
    public static String validate(
            List<? extends TimingView> existingForRunner,
            int targetPosition,
            long elapsedMillis,
            Long finishTimeMs) {
        if (elapsedMillis < 1L || elapsedMillis > MAX_ELAPSED_MILLIS) {
            return "分段耗时毫秒数必须在 1~86400000 之间";
        }
        if (finishTimeMs != null && elapsedMillis >= finishTimeMs) {
            return "分段耗时必须严格小于该选手原始完赛耗时";
        }

        TimingView predecessor = null;
        TimingView successor = null;
        for (TimingView timing : existingForRunner) {
            if (timing.position() == targetPosition) {
                return "同一选手同一检查点最多一条分段记录";
            }
            if (timing.position() < targetPosition) {
                if (predecessor == null || timing.position() > predecessor.position()) {
                    predecessor = timing;
                }
            } else {
                if (successor == null || timing.position() < successor.position()) {
                    successor = timing;
                }
            }
        }

        if (predecessor != null && elapsedMillis <= predecessor.elapsedMillis()) {
            return "分段耗时必须严格大于前一检查点耗时: position=" + predecessor.position();
        }
        if (successor != null && elapsedMillis >= successor.elapsedMillis()) {
            return "分段耗时必须严格小于后一检查点耗时: position=" + successor.position();
        }
        return null;
    }
}
