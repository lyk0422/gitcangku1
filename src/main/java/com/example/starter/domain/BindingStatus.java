package com.example.starter.domain;

/**
 * 绑定状态：BOUND 已绑定并占用天线；PENDING_REASSIGN 校准被作废且任务未开始，等待重配。
 */
public enum BindingStatus {
    BOUND,
    PENDING_REASSIGN
}