package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.AttestationRequest;
import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.AttestationStatusResponse;
import com.example.starter.api.dto.ChecksumResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterChecksumRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、摘要登记、双人见证、锁定与历史查询，均保证写操作幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /** 为制品版本登记内容校验摘要（requestId 幂等）。 */
    ChecksumResponse registerChecksum(String requestId, String name, int version,
                                      RegisterChecksumRequest request);

    /** 发布角色提交见证（requestId 幂等）。 */
    AttestationResponse attest(String requestId, String name, int version, AttestationRequest request);

    /** 撤销指定角色的见证，仅影响后续锁定（requestId 幂等）。 */
    AttestationResponse revokeAttestation(String requestId, String name, int version, String role);

    /** 查询制品版本见证状态明细（只读，不改状态）。 */
    AttestationStatusResponse getAttestationStatus(String name, int version);

    /** 创建锁文件（requestId 幂等）；闭包内版本逐一验证摘要与双人见证。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);
}
