package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.AttestRequest;
import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.ChecksumDetailResponse;
import com.example.starter.api.dto.ChecksumResponse;
import com.example.starter.api.dto.LockDiagnosticsResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterChecksumRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、摘要见证、锁定与历史查询，均保证写操作幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等，操作者缺省为 anonymous）。 */
    default ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request) {
        return registerArtifact(requestId, "anonymous", request);
    }

    /** 登记新制品版本（requestId 幂等，指纹含操作者）。 */
    ArtifactResponse registerArtifact(String requestId, String operator, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等，操作者缺省为 anonymous）。 */
    default ArtifactResponse withdrawArtifact(String requestId, String name, int version) {
        return withdrawArtifact(requestId, "anonymous", name, version);
    }

    /** 撤回制品版本（requestId 幂等，指纹含操作者）。 */
    ArtifactResponse withdrawArtifact(String requestId, String operator, String name, int version);

    /** 创建锁文件（requestId 幂等，操作者缺省为 anonymous）。 */
    default LockFileResponse createLock(String requestId, LockRequest request) {
        return createLock(requestId, "anonymous", request);
    }

    /** 创建锁文件（requestId 幂等，指纹含操作者）。 */
    LockFileResponse createLock(String requestId, String operator, LockRequest request);

    /** 为制品版本登记内容校验摘要（requestId 幂等）。 */
    ChecksumResponse registerChecksum(String requestId, String operator, String name, int version,
                                      RegisterChecksumRequest request);

    /** 发布角色提交见证（requestId 幂等）。 */
    AttestationResponse attest(String requestId, String operator, String name, int version,
                               AttestRequest request);

    /** 撤销指定发布角色的见证（requestId 幂等，仅影响后续锁定）。 */
    AttestationResponse revokeAttestation(String requestId, String operator, String name, int version,
                                          String targetOperator);

    /** 查询制品版本的摘要与见证明细，版本不存在返回 404。 */
    ChecksumDetailResponse getChecksumDetail(String name, int version);

    /** 锁定可行性诊断（只读）：闭包解析结果与逐版本见证就绪状态。 */
    LockDiagnosticsResponse diagnoseLock(String rootName, int rootVersion);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);
}
