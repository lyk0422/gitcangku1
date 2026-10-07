package com.example.starter.incident;

/**
 * 排程提案状态：CURRENT 当前有效；STALE 已失效（被新版本替换或事件版本推进）。
 * STALE 为终态，历史保留不删除。
 */
public enum ScheduleStatus {

    /** 当前有效。 */
    CURRENT,

    /** 已失效：被更新版本替换，或任务完成/取消/指挥交接推进事件版本后失效。 */
    STALE;
}
