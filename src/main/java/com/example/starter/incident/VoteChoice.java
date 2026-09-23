package com.example.starter.incident;

/**
 * 名册人员的票决：只能首次投赞成或反对，同一人员兼任多个角色席位时仍只计一票。
 */
public enum VoteChoice {

    /** 赞成：激活还要求名册中全部人员均赞成。 */
    YES,

    /** 反对：任一反对立即使提案进入 REJECTED。 */
    NO;
}
