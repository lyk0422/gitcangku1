package com.example.starter.api;

import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.DefinePolicyRequest;
import com.example.starter.api.dto.MigratePoliciesRequest;
import com.example.starter.api.dto.MigrationResponse;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.ProvenanceDiagnosticResponse;
import com.example.starter.api.dto.PublishLockRequest;
import com.example.starter.api.dto.ReleaseSnapshotResponse;
import com.example.starter.api.dto.SubmitAttestationRequest;

/**
 * 制品来源策略业务服务：策略版本、证明、撤销、迁移、发布与诊断，写操作均幂等。
 */
public interface ProvenanceService {

    /** 为锁定图定义新的不可改写策略版本（requestId 幂等）。 */
    PolicyResponse definePolicy(String requestId, String operatorHeader, DefinePolicyRequest request);

    /** 查询锁定图指定策略版本；version 为 null 时返回最新版本。 */
    PolicyResponse getPolicy(String lockName, Integer version);

    /** 提交制品坐标来源证明（requestId 幂等）。 */
    AttestationResponse submitAttestation(String requestId, String operatorHeader,
                                          SubmitAttestationRequest request);

    /** 撤销来源证明（requestId 幂等）；已发布快照不倒改。 */
    AttestationResponse revokeAttestation(String requestId, long attestationId);

    /**
     * 批量策略迁移：先预校验所有锁定图的最终命中，全部合规才原子绑定，否则 422 不写入。
     */
    MigrationResponse migratePolicies(String requestId, String operatorHeader,
                                      MigratePoliciesRequest request);

    /** 发布锁定图：固化所用策略与证明版本（requestId 幂等，provenanceKey 同键重放）。 */
    ReleaseSnapshotResponse publishLock(String requestId, String operatorHeader,
                                        PublishLockRequest request);

    /** 查询发布快照；未发布返回 null。 */
    ReleaseSnapshotResponse getRelease(long lockFileId);

    /** 查询来源路径、策略版本与发布阻断诊断。 */
    ProvenanceDiagnosticResponse diagnose(long lockFileId);
}
