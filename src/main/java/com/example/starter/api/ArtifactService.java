package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.BatchDeprecationRequest;
import com.example.starter.api.dto.BatchDeprecationResponse;
import com.example.starter.api.dto.DeprecationDiagnosticsResponse;
import com.example.starter.api.dto.DeprecationResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RevokeDeprecationResponse;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、弃用窗口、锁定与历史查询，均保证写操作幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /** 创建锁文件（requestId 幂等），按锁定时刻裁决弃用窗口。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 批量登记弃用窗口（requestId 幂等）：先校验完整替代图，任一失败整次不写入。 */
    BatchDeprecationResponse registerDeprecations(String requestId, BatchDeprecationRequest request);

    /** 撤销尚未生效的弃用窗口（requestId 幂等），撤销记录保留。 */
    RevokeDeprecationResponse revokeDeprecation(String requestId, String name, int version);

    /** 查询全部弃用窗口历史（含已撤销），按名称、版本升序。 */
    List<DeprecationResponse> listDeprecations();

    /** 查询单个制品版本的弃用窗口明细，不存在返回 404。 */
    DeprecationResponse getDeprecation(String name, int version);

    /** 弃用窗口诊断查询：按当前时钟统计待生效/已生效/已撤销，读取不改变状态。 */
    DeprecationDiagnosticsResponse getDeprecationDiagnostics();

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);
}
