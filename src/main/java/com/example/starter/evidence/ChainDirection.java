package com.example.starter.evidence;

/**
 * 双案保管链事件方向。
 * OUT 来源案件移出；IN 目标案件移入；
 * REVOKE_OUT 撤销时目标案件移出；REVOKE_IN 撤销时来源案件移回。
 */
public enum ChainDirection {
    OUT,
    IN,
    REVOKE_OUT,
    REVOKE_IN
}
