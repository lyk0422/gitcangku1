package com.example.starter.api.dto;

import java.util.List;

/**
 * 锁文件中单个名称对应的精确版本及其签名验证证据。
 *
 * @param digest        锁定时冻结的制品内容摘要；无生效策略时为 null
 * @param policyVersion 锁定时采用的策略版本号；无生效策略时为 null
 * @param signerKeyIds  实际计入阈值的 keyId 升序集合；无生效策略时为 null
 */
public record LockEntryResponse(
        String name,
        int version,
        String digest,
        Long policyVersion,
        List<String> signerKeyIds) {
}
