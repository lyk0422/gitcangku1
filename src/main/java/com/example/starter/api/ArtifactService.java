package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.AttestRequest;
import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.AttestationStatusResponse;
import com.example.starter.api.dto.ChecksumResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterChecksumRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、内容摘要登记、双人见证/撤销、锁定与历史诊断查询，
 * 写操作均保证 requestId 幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /** 为制品版本登记内容校验摘要（requestId 幂等）。 */
    ChecksumResponse registerChecksum(String requestId, String name, int version,
                                      RegisterChecksumRequest request, String operator);

    /** 由发布角色对制品版本摘要提交见证（requestId 幂等）。 */
    AttestationResponse attest(String requestId, String name, int version,
                               AttestRequest request, String operator);

    /** 撤销指定角色对制品版本的见证（requestId 幂等）；只影响后续锁定。 */
    AttestationResponse revokeAttestation(String requestId, String name, int version,
                                          String role, String operator);

    /** 创建锁文件（requestId 幂等）；闭包内每个版本须摘要一致且双人见证。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 查询全部历史锁文件，条目按名称排序返回。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在抛 404。 */
    LockFileResponse getLock(long id);

    /** 查询制品版本的摘要、见证明细与可否入锁诊断；只读，不改变状态。 */
    AttestationStatusResponse getAttestationStatus(String name, int version);
}
