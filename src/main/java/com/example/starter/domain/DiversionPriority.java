package com.example.starter.domain;

/** 备降优先级：EMERGENCY 可抢占未起飞的 NORMAL 已批准航线。 */
public enum DiversionPriority {
    /** 普通优先级，可被 EMERGENCY 抢占。 */
    NORMAL,
    /** 紧急优先级，必须附事件编号；不可被抢占。 */
    EMERGENCY
}
