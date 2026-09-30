package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 高风险依赖豁免视图：既用于豁免管理接口，也作为历史锁文件中“豁免依据”的内嵌视图。
 *
 * <p>历史锁文件中风险级别与豁免 ID/申请版本在锁定时固化；本视图的 status/valid
 * 反映豁免当前状态（例如锁定后被撤销显示 REVOKED），但历史锁文件内容本身不改写。
 *
 * @param id                豁免 ID
 * @param dependencyName    豁免绑定的依赖制品名称（精确适用，不跨名称）
 * @param dependencyVersion 豁免绑定的依赖精确版本号（精确适用，不跨版本）
 * @param exceptionVersion  申请版本号；两名审批人必须确认同一版本
 * @param status            PENDING/APPROVED/REVOKED
 * @param reason            业务豁免原因
 * @param expiresAt         到期 UTC 时刻；恰好到期即失效
 * @param valid             当前是否可用于新锁定：APPROVED 且未撤销且未到期
 * @param createdAt         创建时间，UTC 时间戳
 * @param decidedAt         生效或撤销时间，UTC 时间戳；未终态前为 null
 * @param approvals         全部审批记录（审批人、确认的申请版本、审批时间）
 */
public record RiskExceptionView(
        long id,
        String dependencyName,
        int dependencyVersion,
        int exceptionVersion,
        String status,
        String reason,
        Instant expiresAt,
        boolean valid,
        Instant createdAt,
        Instant decidedAt,
        List<ExceptionApprovalView> approvals) {
}
