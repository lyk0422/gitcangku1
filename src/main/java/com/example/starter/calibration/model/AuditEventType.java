package com.example.starter.calibration.model;

/**
 * 离群审计事件类型。审计记录不可变，只增不改。
 */
public enum AuditEventType {

    /** 离群检测：读数超出允许偏差，自动生成离群记录。 */
    OUTLIER_DETECTED,

    /** 有效复核提交。 */
    REVIEW_SUBMITTED,

    /** 失效复核：引用版本已变更或证书失效，复核落库为 STALE。 */
    REVIEW_STALE,

    /** 裁决保留：两名复核人一致 ACCEPT。 */
    VERDICT_ACCEPT,

    /** 裁决排除：两名复核人一致 REJECT，同事务排除读数并重算统计。 */
    VERDICT_REJECT
}