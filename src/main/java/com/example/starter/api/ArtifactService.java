package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.CapabilityExclusionRequest;
import com.example.starter.api.dto.CapabilityExclusionResponse;
import com.example.starter.api.dto.CapabilityPairView;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、互斥规则维护、锁定与历史查询，均保证写操作幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, String operator, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String operator, String name, int version);

    /** 新增互斥能力对（requestId 幂等），推进仓库版本。 */
    CapabilityExclusionResponse addExclusion(String requestId, String operator,
                                             CapabilityExclusionRequest request);

    /** 撤销互斥能力对（requestId 幂等），推进仓库版本。 */
    CapabilityExclusionResponse removeExclusion(String requestId, String operator,
                                                CapabilityExclusionRequest request);

    /** 查询当前生效的全部互斥能力对。 */
    List<CapabilityPairView> listExclusions();

    /** 创建锁文件（requestId 幂等）。 */
    LockFileResponse createLock(String requestId, String operator, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);
}