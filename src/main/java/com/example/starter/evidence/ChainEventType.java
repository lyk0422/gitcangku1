package com.example.starter.evidence;

/**
 * 案件保管链事件类型（只追加、不可变）。
 * CASE_OUT 来源案移出；CASE_IN 目标案移入；
 * REVERSE_OUT 撤销时目标案反向移出；REVERSE_IN 撤销时来源案反向移入。
 */
public enum ChainEventType {
    CASE_OUT,
    CASE_IN,
    REVERSE_OUT,
    REVERSE_IN
}
