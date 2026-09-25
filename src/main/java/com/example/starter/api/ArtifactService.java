package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.AttestationRequest;
import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.CreatePolicyRequest;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MigrationCheckRequest;
import com.example.starter.api.dto.MigrationCheckResponse;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.ProvenanceResponse;
import com.example.starter.api.dto.PublishDiagnosticResponse;
import com.example.starter.api.dto.PublishRequest;
import com.example.starter.api.dto.PublishResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、锁定与历史查询、来源策略与证明、发布门禁，均保证写操作幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /** 创建锁文件（requestId 幂等）；存在策略时按当前策略版本校验全部命中坐标。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);

    /** 追加一个来源策略版本（requestId 幂等）；历史版本不可改写。 */
    PolicyResponse createPolicyVersion(String requestId, CreatePolicyRequest request);

    /** 查询全部策略版本，按版本号升序。 */
    List<PolicyResponse> listPolicies();

    /** 登记坐标来源证明（requestId 幂等）；同坐标重复登记生成递增证明版本。 */
    AttestationResponse attest(String requestId, AttestationRequest request);

    /** 撤销坐标当前证明（requestId 幂等）；已发布快照不受影响。 */
    AttestationResponse revokeAttestation(String requestId, String name, int version);

    /** 发布锁定图：按当前策略版本校验，provenanceKey 同键重放，失败不占键。 */
    PublishResponse publishLock(long lockFileId, PublishRequest request);

    /** 查询锁定图来源路径：已发布返回冻结快照，未发布返回当前证明状态。 */
    ProvenanceResponse getProvenance(long lockFileId);

    /** 发布阻断诊断：按当前策略版本评估锁定图的全部违规。 */
    PublishDiagnosticResponse getPublishDiagnostic(long lockFileId);

    /** 批量策略迁移预校验：以候选策略评估全部锁定图的最终命中，只读不写。 */
    MigrationCheckResponse checkMigration(MigrationCheckRequest request);
}
