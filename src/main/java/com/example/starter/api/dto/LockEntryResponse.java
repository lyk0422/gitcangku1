package com.example.starter.api.dto;

import java.util.List;

/**
 * 锁文件中单个名称对应的精确版本及签名验证冻结证据。
 *
 * @param contentDigest    锁定时冻结的制品内容 SHA-256 摘要（64 位十六进制）
 * @param policyVersion    该节点采用的策略版本号；锁定时无生效策略为 null
 * @param signatureKeyIds  实际计入阈值的 keyId，按字典序升序；无生效策略为空列表
 */
public record LockEntryResponse(
        String name,
        int version,
        String contentDigest,
        Long policyVersion,
        List<String> signatureKeyIds) {
}
