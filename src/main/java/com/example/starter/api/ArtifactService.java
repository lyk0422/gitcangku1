package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DiagnoseLockRequest;
import com.example.starter.api.dto.LicenseResponse;
import com.example.starter.api.dto.LockDiagnosisResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockLicenseSnapshotResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.SetLicenseRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、许可证登记、命名空间策略、锁定与历史查询，均保证写操作幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /** 登记或修订制品版本许可证（requestId 幂等）；已撤回版本返回 409。 */
    LicenseResponse setLicense(String requestId, String name, int version, SetLicenseRequest request);

    /** 创建或修改命名空间许可证策略（requestId 幂等）；期望版本不匹配返回 409。 */
    PolicyResponse upsertPolicy(String requestId, String namespace, PolicyRequest request);

    /** 查询命名空间许可证策略，未配置返回 404。 */
    PolicyResponse getPolicy(String namespace);

    /** 创建锁文件（requestId 幂等）；许可证策略违规返回 422 且不生成部分锁文件。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);

    /** 查询锁文件固化的许可证快照（含锁定时策略版本），锁文件不存在返回 404。 */
    LockLicenseSnapshotResponse getLockLicenseSnapshot(long lockId);

    /** 只读违规诊断：按当前仓库状态解析闭包并评估策略，不产生任何写入。 */
    LockDiagnosisResponse diagnoseLock(DiagnoseLockRequest request);
}
