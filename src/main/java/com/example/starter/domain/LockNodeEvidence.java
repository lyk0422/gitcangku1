package com.example.starter.domain;

import java.util.List;

/**
 * 单个锁节点的签名验证证据（成功时冻结进锁条目）。
 *
 * @param digest         节点制品内容摘要
 * @param policyVersion  采用的策略版本号
 * @param signerKeyIds   实际计入阈值的 keyId 升序集合（去重，数量 >= m）
 */
public record LockNodeEvidence(
        String digest,
        long policyVersion,
        List<String> signerKeyIds) {
}
