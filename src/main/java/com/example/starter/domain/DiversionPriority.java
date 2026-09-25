package com.example.starter.domain;

/**
 * 备降优先级。
 */
public enum DiversionPriority {
    /** 普通航线：已批准但未起飞时可被同桶 EMERGENCY 航线抢占。 */
    NORMAL,
    /** 紧急备降航线：必须附事件编号；可抢占未起飞 NORMAL，航线之间不得互相抢占。 */
    EMERGENCY
}
