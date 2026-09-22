package com.example.starter.domain;

/** 实验状态：OPEN 可继续分配，CLOSED 拒绝新增分配但保留历史结果。 */
public enum ExperimentStatus {
    OPEN,
    CLOSED
}
