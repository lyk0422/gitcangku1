package com.example.starter.api;

import com.example.starter.api.dto.AddSignatureRequest;
import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.KeyResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.PublishPolicyRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.SignatureResponse;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、签名信任策略、补签/撤销、锁定与证据查询，写操作均保证幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /** 发布签名信任策略版本（requestId 幂等）；新版本激活即替代旧版本。 */
    PolicyResponse publishPolicy(String requestId, PublishPolicyRequest request);

    /** 撤销可信钥匙（requestId 幂等）；历史签名与锁文件不改写。 */
    KeyResponse revokeKey(String requestId, String keyId);

    /** 为制品版本追加签名（requestId 幂等）；同一钥匙仅一份，digest 必须等于内容摘要。 */
    SignatureResponse addSignature(String requestId, String name, int version,
                                   AddSignatureRequest request);

    /** 创建锁文件（requestId 幂等）；闭包每个节点须满足当前生效策略的阈值签名。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);

    /** 查询全部已发布策略，按版本号升序（只读）。 */
    List<PolicyResponse> listPolicies();

    /** 按版本号查询策略，不存在抛 404（只读）。 */
    PolicyResponse getPolicy(long policyVersion);

    /** 查询全部可信钥匙（含已撤销），按 keyId 升序（只读）。 */
    List<KeyResponse> listKeys();

    /** 查询单个可信钥匙，不存在抛 404（只读）。 */
    KeyResponse getKey(String keyId);

    /** 查询某制品版本的全部历史签名，按 keyId 升序（只读）。 */
    List<SignatureResponse> listSignatures(String name, int version);
}
