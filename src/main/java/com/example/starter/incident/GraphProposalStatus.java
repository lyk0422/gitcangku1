package com.example.starter.incident;

/**
 * 依赖图变更提案状态机：PENDING → APPROVED → ACTIVATED，或 PENDING → REJECTED。
 * PENDING 投票中；任一反对票即 REJECTED；名册全部席位赞成后进入 APPROVED；
 * 仅 APPROVED 可激活，激活成功进入 ACTIVATED（终态）。REJECTED/ACTIVATED 均为终态。
 */
public enum GraphProposalStatus {

    /** 投票中，尚未达到法定人数。 */
    PENDING,

    /** 全体指挥官席位与安全审核员席位均已赞成，可激活。 */
    APPROVED,

    /** 任一名册成员反对，整案否决（终态）。 */
    REJECTED,

    /** 已激活，边集变更原子生效并生成新图版本（终态）。 */
    ACTIVATED;
}
