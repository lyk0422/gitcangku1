package com.example.starter.batch;

/**
 * 产率平衡核算状态机。
 * WITHIN_LIMIT 偏差不超过上限（恰好等于上限亦合格），门禁开放可直接放行；
 * PENDING_DISPOSITION 偏差超限待处置，门禁锁定、批次不能放行，须两名不同处置人确认同一版本；
 * DISPOSITION_CLEARED 两名不同处置人已确认同一超限版本，门禁解除可放行。
 * 提交新核算即升版，新版本按自身偏差重新裁决，旧版本处置不结转、历史保留。
 */
public enum YieldStatus {
    WITHIN_LIMIT,
    PENDING_DISPOSITION,
    DISPOSITION_CLEARED
}
