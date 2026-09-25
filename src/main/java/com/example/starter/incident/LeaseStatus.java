package com.example.starter.incident;

/**
 * 资源租约状态机：ACTIVE → CREDENTIAL_RISK（资质提前撤销）→ ACTIVE（合格资源替换后新租约生效）；
 * ACTIVE/CREDENTIAL_RISK → REPLACED（被替换的旧租约）；ACTIVE → RELEASED（任务进入终态时释放）。
 * REPLACED 与 RELEASED 为终态。
 */
public enum LeaseStatus {

    /** 生效中。 */
    ACTIVE,

    /** 资质风险：租约资源必需资质被提前撤销，任务不得开始或完成。 */
    CREDENTIAL_RISK,

    /** 已被合格资源的新租约替换。 */
    REPLACED,

    /** 任务进入 DONE/CANCELLED 终态后释放。 */
    RELEASED;
}
