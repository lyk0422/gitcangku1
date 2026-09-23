package com.example.starter.plan.model;

/**
 * 交换快照阶段：BEFORE 交换前服务端实际占用；AFTER 交换后目标占用（激活后即为计划新占用）。
 */
public enum CapacitySwapPhase {
    BEFORE,
    AFTER
}
