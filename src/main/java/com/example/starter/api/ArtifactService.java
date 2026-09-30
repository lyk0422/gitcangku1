package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.ExceptionApproveRequest;
import com.example.starter.api.dto.ExceptionCreateRequest;
import com.example.starter.api.dto.ExceptionResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MarkRiskRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、风险标记、风险豁免审批、锁定与历史查询，均保证写操作幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /** 标记制品版本的依赖风险级别（requestId 幂等）。 */
    ArtifactResponse markRisk(String requestId, String name, int version, MarkRiskRequest request);

    /** 创建依赖风险豁免申请（requestId 幂等）。 */
    ExceptionResponse createException(String requestId, ExceptionCreateRequest request);

    /** 审批人确认豁免申请；第二名不同审批人确认同一内容版本后生效。 */
    ExceptionResponse approveException(long exceptionId, ExceptionApproveRequest request);

    /** 撤销已生效豁免；仅影响此后新锁定。 */
    ExceptionResponse revokeException(long exceptionId, com.example.starter.api.dto.ExceptionRevokeRequest request);

    /** 查询单个豁免申请当前状态，不存在返回 null。 */
    ExceptionResponse getException(long exceptionId);

    /** 查询全部豁免申请，按 ID 升序。 */
    List<ExceptionResponse> listExceptions();

    /** 创建锁文件（requestId 幂等）。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);
}
