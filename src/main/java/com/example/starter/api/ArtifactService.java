package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.ApproveRiskExceptionRequest;
import com.example.starter.api.dto.CreateRiskExceptionRequest;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MarkRiskRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.ReviseRiskExceptionRequest;
import com.example.starter.api.dto.RiskExceptionView;
import com.example.starter.api.dto.RiskResponse;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、风险标记、风险豁免、锁定与历史查询，均保证写操作幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /** 标记或更新制品版本的依赖风险等级（requestId 幂等）。 */
    RiskResponse markRisk(String requestId, MarkRiskRequest request);

    /** 查询全部风险标记，按名称、版本升序。 */
    List<RiskResponse> listRisks();

    /** 创建高风险依赖豁免申请（PENDING，requestId 幂等）。 */
    RiskExceptionView createRiskException(String requestId, CreateRiskExceptionRequest request);

    /** 审批人确认豁免申请的指定版本；两名不同审批人确认同一版本后生效（requestId 幂等）。 */
    RiskExceptionView approveRiskException(String requestId, long exceptionId,
                                           ApproveRiskExceptionRequest request);

    /** 修订 PENDING 豁免：原因/到期时刻更新，申请版本加一，既有审批作废（requestId 幂等）。 */
    RiskExceptionView reviseRiskException(String requestId, long exceptionId,
                                          ReviseRiskExceptionRequest request);

    /** 撤销已生效豁免；仅影响新锁定，不改写历史锁文件（requestId 幂等）。 */
    RiskExceptionView revokeRiskException(String requestId, long exceptionId);

    /** 查询全部豁免申请（含审批人与当前有效性），按 ID 升序。 */
    List<RiskExceptionView> listRiskExceptions();

    /** 按 ID 查询豁免申请，不存在抛 404。 */
    RiskExceptionView getRiskException(long exceptionId);

    /** 创建锁文件（requestId 幂等；同 requestId 改依赖版本或豁免清单返回 409）。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);
}
