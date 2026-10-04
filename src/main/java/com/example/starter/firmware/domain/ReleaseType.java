package com.example.starter.firmware.domain;

/**
 * 发布类型：FULL 普通全量发布；DELTA 增量补丁发布（声明基底/补丁/目标摘要，仅管理元数据）。
 */
public enum ReleaseType {
    FULL,
    DELTA
}