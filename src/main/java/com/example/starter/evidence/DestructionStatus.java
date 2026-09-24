package com.example.starter.evidence;

/**
 * 销毁令状态。
 * PENDING 待审批（一名审批人同意后仍为 PENDING）；APPROVED 两名互异且不同于提交人的审批人均已同意；
 * REJECTED 任一审批人拒绝（终态，证物随即恢复可用，拒绝原因不可改写）；
 * DESTROYED 保管人已执行，证物全部置为 DESTROYED 终态（终态，同一销毁令最多执行成功一次）。
 */
public enum DestructionStatus {
    PENDING,
    APPROVED,
    REJECTED,
    DESTROYED
}
