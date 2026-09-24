package com.example.starter.evidence.destruction;

/**
 * 销毁令状态。
 * PENDING 待双人审批（入列证物冻结）；APPROVED 已批准待执行（证物继续冻结）；
 * REJECTED 已拒绝（终态，证物恢复可用，拒绝原因不可改写）；
 * DESTROYED 已执行（终态，证物进入 DESTROYED，保管链封存）。
 */
public enum DestructionStatus {
    PENDING,
    APPROVED,
    REJECTED,
    DESTROYED
}
