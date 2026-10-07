package com.example.starter.plan.model;

/**
 * 占用实际状态：NOT_ENTERED 未进入（初始）；INSIDE 已进入未退出（同区段至多一条）；
 * EXITED 已退出（轨迹保留，不可再进入）。状态仅由服务端进入/退出确认推进。
 */
public enum ActualStatus {
    NOT_ENTERED,
    INSIDE,
    EXITED
}
