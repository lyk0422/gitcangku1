package com.example.starter.evidence;

/**
 * 批量入库差异复核状态（仅 DISCREPANT 证物非空）。
 * PENDING_REVIEW 待复核，复核前禁止发起交接与封条核验；
 * REVIEWED 保管人已提交复核结果，状态不可逆关闭，权限与 MATCHED 证物一致。
 */
public enum ReviewStatus {
    PENDING_REVIEW,
    REVIEWED
}
