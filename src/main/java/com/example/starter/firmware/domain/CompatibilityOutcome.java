package com.example.starter.firmware.domain;

/**
 * 兼容判定结果：GRANTED 模块全部满足直接下发；EXEMPTION 有模块依靠有效豁免放行后下发；
 * BLOCKED 存在缺失或低版本模块，阻断下发且不创建任务。
 */
public enum CompatibilityOutcome {
    GRANTED,
    EXEMPTION,
    BLOCKED
}
