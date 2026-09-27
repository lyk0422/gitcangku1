package com.example.starter.api.dto;

import java.util.List;

/**
 * 制品版本校验见证诊断视图（只读，不改变状态）。
 *
 * @param checksumRegistered 是否已登记内容校验摘要
 * @param checksum           已登记摘要原文；未登记为 null
 * @param withdrawn          版本是否已撤回
 * @param activeWitnessCount 当前有效见证角色数（要求 {@code requiredWitnesses} 个不同角色）
 * @param requiredWitnesses  进入锁闭包要求的见证角色数
 * @param attested            是否满足“摘要一致且达到双人见证”，可进入新锁文件闭包
 * @param reasons             不满足时的稳定原因列表（含实际值/要求值/差额）；满足时为空
 * @param attestations        全部见证记录（含已撤销），按角色升序
 */
public record AttestationStatusResponse(
        String name,
        int version,
        boolean checksumRegistered,
        String checksum,
        boolean withdrawn,
        int activeWitnessCount,
        int requiredWitnesses,
        boolean attested,
        List<String> reasons,
        List<AttestationView> attestations) {
}
