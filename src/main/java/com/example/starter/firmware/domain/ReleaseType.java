package com.example.starter.firmware.domain;

/**
 * 发布类型：FULL 普通全量发布（沿用原接口，无摘要门禁）；DELTA 增量补丁发布（声明基底/补丁/目标摘要）。
 */
public enum ReleaseType {
    FULL,
    DELTA
}