package com.example.starter.domain;

/** 审查原因码：随审查结果不可变保存，供审查原因查询。 */
public enum ReviewReason {
    /** 正常通过：未命中禁飞区，起降不与关闭窗口冲突且在容量内。 */
    CLEAR,
    /** 命中至少一个有效禁飞区。 */
    ZONE_HIT,
    /** 紧急例外通过：EMERGENCY 航线起降落入允许例外的关闭窗口且附事件号。 */
    EMERGENCY_EXCEPTION
}
