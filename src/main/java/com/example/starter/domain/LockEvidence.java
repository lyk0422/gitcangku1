package com.example.starter.domain;

import java.util.List;

/**
 * 单个锁图节点冻结的签名验证证据。
 *
 * @param name            制品名称
 * @param version         精确版本
 * @param contentDigest   锁定时冻结的内容 SHA-256 摘要
 * @param policyVersion   采用的策略版本号；无生效策略时为 null
 * @param signatureKeyIds 实际计入阈值的 keyId，按字典序升序；无生效策略为空
 */
public record LockEvidence(
        String name,
        int version,
        String contentDigest,
        Long policyVersion,
        List<String> signatureKeyIds) {
}
