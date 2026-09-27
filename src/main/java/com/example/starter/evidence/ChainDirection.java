package com.example.starter.evidence;

/**
 * 跨案移交保管链方向。链只追加：移交写 OUT/IN，撤销写 REVOKE_OUT/REVOKE_IN。
 */
public enum ChainDirection {
    /** 来源案件移出。 */
    OUT,
    /** 目标案件移入。 */
    IN,
    /** 撤销：自目标案件移出。 */
    REVOKE_OUT,
    /** 撤销：回来源案件移入。 */
    REVOKE_IN
}
