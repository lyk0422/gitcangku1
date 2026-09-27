package com.example.starter.evidence;

/**
 * 跨案保管链事件类型。时间均为 UTC，微秒精度；记录只追加、不可变。
 * CASE_TRANSFER_OUT 来源案件移出链；CASE_TRANSFER_IN 目标案件移入链；
 * CASE_TRANSFER_REVOKE_OUT 撤销时目标案件移出链；CASE_TRANSFER_REVOKE_IN 撤销时来源案件移回链。
 */
public enum ChainEventType {
    CASE_TRANSFER_OUT,
    CASE_TRANSFER_IN,
    CASE_TRANSFER_REVOKE_OUT,
    CASE_TRANSFER_REVOKE_IN
}
