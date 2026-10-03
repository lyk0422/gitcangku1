package com.example.starter.domain;

/**
 * 限发指令状态机：
 * ISSUED 已签发待确认；CONFIRMED 已确认生效；CANCELLED 已撤销；ENDED 已结束。
 * 确认与撤销互斥竞争，只允许进入其中一种终态；ENDED 不因迟到更正恢复为运行中。
 */
public enum DirectiveStatus {
    ISSUED,
    CONFIRMED,
    CANCELLED,
    ENDED
}