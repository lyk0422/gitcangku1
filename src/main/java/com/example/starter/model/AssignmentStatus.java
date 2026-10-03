package com.example.starter.model;

/**
 * 指派状态：ACTIVE 生效中（占用引航员区间）；RELEASED 已释放（撤销或船舶取消）；
 * COMPLETED 已确认离轮，占用结束。
 */
public enum AssignmentStatus {
    ACTIVE,
    RELEASED,
    COMPLETED
}