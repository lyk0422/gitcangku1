package com.example.starter.firmware.domain;

/**
 * 投放任务状态：PENDING 待开始/待回执；INSTALLING 安装中（占设备独占安装槽，仅此时接受普通回执）；
 * CANCEL_REQUESTED 取消待确认（仍占槽，只接受同代次停止确认）；SUCCESS 成功；FAILED 失败；CANCELLED 已取消。
 */
public enum TaskStatus {
    PENDING,
    INSTALLING,
    CANCEL_REQUESTED,
    SUCCESS,
    FAILED,
    CANCELLED
}
