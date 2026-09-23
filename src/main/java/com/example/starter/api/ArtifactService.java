package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.PublishPolicyRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回/恢复、替代策略发布、锁定与历史查询，均保证写操作幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /** 恢复已撤回的制品版本（requestId 幂等）；不影响历史锁解释。 */
    ArtifactResponse restoreArtifact(String requestId, String name, int version);

    /** 发布一套整体激活的依赖替代策略（requestId 幂等，policyKey 唯一）。 */
    PolicyResponse publishPolicy(String requestId, PublishPolicyRequest request);

    /** 查询全部策略版本（只读，按版本号稳定排序）。 */
    List<PolicyResponse> listPolicies();

    /** 按版本号查询单个策略，不存在返回 null。 */
    PolicyResponse getPolicy(long policyVersion);

    /** 创建锁文件（requestId 幂等），含替代解释快照。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);
}
