package com.example.starter.incident;

/**
 * 疏散区域状态。区域本身不提供显式生效/结束动作：
 * 登记后为 REGISTERED，是否有效由 UTC 左闭右开窗口在每次裁决时计算；
 * 当窗口结束且被阻断任务完成恢复后，由结束裁决原子置为 ENDED。
 */
public enum ZoneStatus {

    /** 已登记：窗口可能尚未开始、正在生效或已到期但尚未执行结束裁决。 */
    REGISTERED,

    /** 已结束：结束裁决已执行（到期未阻断任何任务，或最后一个阻断任务已恢复）。 */
    ENDED
}
