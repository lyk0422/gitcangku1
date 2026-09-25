package com.example.starter.api.dto;

import java.util.List;

/**
 * 来源路径/发布阻断诊断视图。
 *
 * @param policyVersion        锁定图当前绑定的策略版本；0 表示未绑定来源策略
 * @param currentPolicyVersion 锁定图所属策略分组的最新策略版本；未定义为 0
 * @param compliant            锁定图当前是否满足所绑定策略版本
 * @param provenancePath       每个解析坐标命中的来源信息，按名称升序
 * @param violations           全部违规项（可区分原因 + 完整路径），合规时为空
 */
public record ProvenanceDiagnosticResponse(
        long lockFileId,
        String rootName,
        int rootVersion,
        int policyVersion,
        int currentPolicyVersion,
        boolean compliant,
        List<ProvenancePathView> provenancePath,
        List<ViolationView> violations) {
}
