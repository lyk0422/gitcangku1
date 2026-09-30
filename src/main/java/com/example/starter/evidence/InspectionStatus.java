package com.example.starter.evidence;

/**
 * 容器巡检单状态。
 * AWAITING_WITNESS 仅一名保管人确认，尚未产生任何证物状态变更；
 * CONFIRMED_INTACT 两名不同保管人同一版本确认封条完好；
 * CONFIRMED_ANOMALY 两名不同保管人同一版本确认封条异常，箱内在箱证物已一次隔离；
 * CONFLICTED 两名保管人结论分歧或封条版本不一致，巡检作废且不改变证物状态。
 */
public enum InspectionStatus {
    AWAITING_WITNESS,
    CONFIRMED_INTACT,
    CONFIRMED_ANOMALY,
    CONFLICTED
}
