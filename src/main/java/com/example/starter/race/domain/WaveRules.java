package com.example.starter.race.domain;

import java.util.Map;

/**
 * 分批起跑波次不变量的纯逻辑校验，不涉及数据库与事务。
 *
 * <p>波次起跑时刻可相同，但任一参赛者所属波次的起跑时刻相对赛事基准起跑时刻的毫秒差，
 * 不得大于（即波次起跑不得晚于）该参赛者首个有效分段计时的累计耗时；
 * 首个有效分段计时取该选手已提交记录中检查点顺序最靠前（position 最小）的一条。
 */
public final class WaveRules {

    private WaveRules() {
    }

    /**
     * 校验单个波次参赛者的起跑时刻不晚于其首个有效分段计时。
     *
     * @param waveOffsetMs         波次起跑相对赛事基准起跑的毫秒差
     * @param earliestSplitMs      首个有效分段计时累计耗时（毫秒）；尚无分段记录为 null
     * @return 违反不变量时的错误信息；合法或尚无分段记录时为 null
     */
    public static String validateEntrant(long waveOffsetMs, Long earliestSplitMs) {
        if (earliestSplitMs != null && waveOffsetMs > earliestSplitMs) {
            return "波次起跑时刻不得晚于该参赛者首个有效分段计时";
        }
        return null;
    }

    /**
     * 批量校验一份完整波次归属方案。
     *
     * @param waveStartByBib     参赛号 → 所属波次起跑时刻（Unix 毫秒时间戳）
     * @param baseStartMs        赛事基准起跑时刻（Unix 毫秒时间戳）
     * @param earliestSplitByBib 参赛号 → 首个有效分段计时累计耗时（毫秒）；无记录的参赛号可缺席
     * @return 首个违反不变量的参赛号；全部合法时为 null
     */
    public static String findViolation(
            Map<String, Long> waveStartByBib,
            long baseStartMs,
            Map<String, Long> earliestSplitByBib) {
        for (Map.Entry<String, Long> entry : waveStartByBib.entrySet()) {
            long offset = entry.getValue() - baseStartMs;
            Long earliestSplit = earliestSplitByBib.get(entry.getKey());
            if (validateEntrant(offset, earliestSplit) != null) {
                return entry.getKey();
            }
        }
        return null;
    }
}
