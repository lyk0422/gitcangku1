package com.example.starter.incident;

/**
 * 依赖图变更提案状态机：PENDING 投票中 → ACTIVATED 已原子生效 / REJECTED 已否决。
 * 任一在册人员投反对票立即进入 REJECTED；ACTIVATED 与 REJECTED 均为终态。
 */
public enum ProposalStatus {

    /** 投票中：名册成员仍可投出首次赞成或反对。 */
    PENDING,

    /** 已激活：法定人数满足后在一个事务内通过整图后态校验并原子改图。 */
    ACTIVATED,

    /** 已否决：任一名册人员投反对票，提案永久不可激活。 */
    REJECTED;
}
