package com.example.starter.domain;

/**
 * 高风险依赖豁免的生命周期状态。
 */
public enum RiskExceptionStatus {

    /** 申请已创建，尚未取得两名不同审批人对当前申请版本的确认。 */
    PENDING,
    /** 已由两名不同审批人确认同一申请版本，且未撤销、未到期，可用于新锁定。 */
    APPROVED,
    /** 已撤销；仅影响撤销后的新锁定，历史锁文件中固化的依据不改写。 */
    REVOKED;

    /** 解析外部传入的状态代码，大小写不敏感。 */
    public static RiskExceptionStatus fromCode(String code) {
        return RiskExceptionStatus.valueOf(code.trim().toUpperCase());
    }
}
