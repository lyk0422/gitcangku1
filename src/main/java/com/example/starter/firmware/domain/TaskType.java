package com.example.starter.firmware.domain;

/**
 * 投放任务类型：PLAIN 普通回执任务（拉取即待回执，保持旧行为）；
 * INSTALL 安装握手任务（PENDING 需显式开始后为 INSTALLING，占设备独占安装槽，取消需停止确认）。
 */
public enum TaskType {
    PLAIN,
    INSTALL
}
