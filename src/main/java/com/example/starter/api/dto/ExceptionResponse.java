package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 豁免申请视图（含当前状态与审批人）。
 *
 * @param id                豁免申请 ID
 * @param dependencyName    豁免适用的依赖制品名称
 * @param dependencyVersion 豁免适用的依赖精确版本
 * @param expiresAt         到期 UTC 时刻；恰好到达即失效
 * @param reason            业务豁免原因
 * @param status            PENDING=待审批，APPROVED=双人批准已生效，REVOKED=已撤销
 * @param contentVersion    申请内容版本，审批必须确认同一版本
 * @param approvers         已确认的审批人（按确认先后，去重，最多两名）
 * @param createdAt         创建时间，UTC
 * @param approvedAt        生效时间（第二名审批人确认时刻），未生效为 null
 * @param revokedAt         撤销时间，未撤销为 null
 */
public record ExceptionResponse(
        long id,
        String dependencyName,
        int dependencyVersion,
        Instant expiresAt,
        String reason,
        String status,
        int contentVersion,
        List<String> approvers,
        Instant createdAt,
        Instant approvedAt,
        Instant revokedAt) {
}
