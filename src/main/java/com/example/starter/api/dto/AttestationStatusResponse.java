package com.example.starter.api.dto;

import java.util.List;

/**
 * 制品版本见证状态明细（诊断查询，只读）。
 *
 * @param checksum             已登记摘要；未登记为 null
 * @param attestationVersion   当前见证版本号
 * @param requiredAttestations 进入锁定闭包所需的不同角色有效见证数量（固定为 2）
 * @param activeAttestations   当前有效（未撤销且摘要一致）的不同角色见证数量
 * @param missingAttestations  距离满足要求的差额：required - active（不为负）
 * @param attestations         全部见证记录（含已撤销），按角色升序
 */
public record AttestationStatusResponse(
        String name,
        int version,
        String checksum,
        int attestationVersion,
        int requiredAttestations,
        int activeAttestations,
        int missingAttestations,
        List<AttestationView> attestations) {
}
