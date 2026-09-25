package com.example.starter.plan.web.dto;

/**
 * 交路链中一段到其后继段的衔接评估。
 *
 * @param nextScheduleKey   后继段计划业务键
 * @param stationConnected  前段终到站是否等于后段始发站
 * @param actualGapMinutes  实际间隔分钟数（后段始发时刻减前段终到时刻，可为负）
 * @param requiredMinutes   要求的最小周转分钟数
 * @param satisfied         站点衔接且周转时间均满足
 */
public record ChainLinkView(String nextScheduleKey, boolean stationConnected,
                            long actualGapMinutes, int requiredMinutes, boolean satisfied) {
}
