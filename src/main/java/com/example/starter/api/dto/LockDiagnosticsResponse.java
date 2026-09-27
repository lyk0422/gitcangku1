package com.example.starter.api.dto;

import java.util.List;

/**
 * 锁定可行性诊断视图（只读，不改变状态）。
 *
 * <p>entries 按名称升序；resolvable=false 时 entries 为空列表。
 *
 * @param resolvable        当前仓库状态下是否存在可行版本组合
 * @param ready             闭包内全部版本是否均已登记摘要且见证达标
 * @param repositoryVersion 诊断读取时的仓库版本号
 */
public record LockDiagnosticsResponse(
        String rootName,
        int rootVersion,
        long repositoryVersion,
        boolean resolvable,
        boolean ready,
        List<Entry> entries) {

    /**
     * 闭包内单个版本的见证就绪状态。
     *
     * @param activeAttestations   当前有效见证人数（实际值）
     * @param requiredAttestations 锁定要求的见证人数（要求值）
     * @param missingAttestations  距锁定要求尚缺的见证人数（差额，不为负）
     * @param attesters            当前有效见证人，按字典序排列
     */
    public record Entry(
            String name,
            int version,
            boolean checksumRegistered,
            int activeAttestations,
            int requiredAttestations,
            int missingAttestations,
            List<String> attesters) {
    }
}
