package com.example.starter.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 见证闸门：判定制品版本是否满足“摘要一致且两名不同发布角色双人见证”，
 * 只有合格版本可进入新锁文件的依赖闭包。
 *
 * <p>合格条件（全部满足）：未撤回；已登记摘要；至少
 * {@link ChecksumPolicy#REQUIRED_WITNESSES} 个不同角色的有效见证，
 * 且每条有效见证的摘要与登记摘要逐字符一致。
 */
public final class AttestationGate {

    private AttestationGate() {
    }

    /** 是否可进入锁闭包。 */
    public static boolean isLockEligible(ArtifactVersion artifact) {
        return violationReasons(artifact).isEmpty();
    }

    /**
     * 返回不满足入闭包条件的稳定原因列表（含实际值、要求值与差额）；满足时为空。
     * 列表顺序稳定：撤回、摘要缺失、见证数量、逐条摘要不一致。
     */
    public static List<String> violationReasons(ArtifactVersion artifact) {
        List<String> reasons = new ArrayList<>();
        if (artifact.withdrawn()) {
            reasons.add("版本 " + artifact.name() + ":" + artifact.version() + " 已撤回，不能进入锁闭包");
        }
        String registered = artifact.checksum();
        if (registered == null) {
            reasons.add("版本 " + artifact.name() + ":" + artifact.version()
                    + " 未登记内容校验摘要：实际 0 条，要求 1 条，差额 1 条");
            return reasons;
        }
        long matchingRoles = artifact.attestations().stream()
                .filter(Attestation::active)
                .filter(a -> registered.equals(a.checksumValue()))
                .map(Attestation::role)
                .distinct()
                .count();
        if (matchingRoles < ChecksumPolicy.REQUIRED_WITNESSES) {
            long delta = ChecksumPolicy.REQUIRED_WITNESSES - matchingRoles;
            reasons.add("版本 " + artifact.name() + ":" + artifact.version()
                    + " 有效见证角色数不足：实际 " + matchingRoles + " 个，要求 "
                    + ChecksumPolicy.REQUIRED_WITNESSES + " 个不同角色，差额 " + delta + " 个");
        }
        for (Attestation attestation : artifact.attestations()) {
            if (attestation.active() && !registered.equals(attestation.checksumValue())) {
                reasons.add("版本 " + artifact.name() + ":" + artifact.version()
                        + " 的角色 " + attestation.role() + " 见证摘要 " + attestation.checksumValue()
                        + " 与登记摘要 " + registered + " 不一致");
            }
        }
        return reasons;
    }
}
