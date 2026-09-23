package com.example.starter.incident;

/**
 * 票决表决方向：APPROVE 赞成，REJECT 反对。任一 REJECT 使整案 REJECTED。
 */
public enum VoteDecision {

    /** 赞成。 */
    APPROVE,

    /** 反对。 */
    REJECT;
}
