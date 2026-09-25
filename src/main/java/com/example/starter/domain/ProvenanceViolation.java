package com.example.starter.domain;

/**
 * 来源校验违规项：携带可区分原因与从根到违规坐标的完整路径。
 *
 * @param reason 违规原因代码
 * @param path   根制品到违规坐标的完整坐标路径（含根与违规节点）
 * @param detail 人类可读说明
 */
public record ProvenanceViolation(String reason, java.util.List<String> path, String detail) {

    /** 缺少证明。 */
    public static final String MISSING_ATTESTATION = "MISSING_ATTESTATION";
    /** 证明已撤销。 */
    public static final String ATTESTATION_REVOKED = "ATTESTATION_REVOKED";
    /** 构建摘要与策略要求不匹配。 */
    public static final String DIGEST_MISMATCH = "DIGEST_MISMATCH";
    /** 证明等级不足。 */
    public static final String LEVEL_INSUFFICIENT = "LEVEL_INSUFFICIENT";
    /** 锁定图绑定的策略版本落后于当前版本，须先迁移。 */
    public static final String POLICY_VERSION_OUTDATED = "POLICY_VERSION_OUTDATED";
}
