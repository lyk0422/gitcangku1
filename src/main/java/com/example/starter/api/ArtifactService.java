package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、锁定与历史查询，均保证写操作幂等。
 *
 * <p>写操作幂等身份包含操作、操作者、资源及全部参数（锁定含期望仓库版本），
 * 操作者可缺省（视为空串）。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等，不带操作者）。 */
    default ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request) {
        return registerArtifact(requestId, "", request);
    }

    /** 登记新制品版本（requestId 幂等，身份含操作者）。 */
    ArtifactResponse registerArtifact(String requestId, String operator, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等，不带操作者）。 */
    default ArtifactResponse withdrawArtifact(String requestId, String name, int version) {
        return withdrawArtifact(requestId, "", name, version);
    }

    /** 撤回制品版本（requestId 幂等，身份含操作者）。 */
    ArtifactResponse withdrawArtifact(String requestId, String operator, String name, int version);

    /** 创建锁文件（requestId 幂等，不带操作者）。 */
    default LockFileResponse createLock(String requestId, LockRequest request) {
        return createLock(requestId, "", request);
    }

    /** 创建锁文件（requestId 幂等，身份含操作者）。 */
    LockFileResponse createLock(String requestId, String operator, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);
}
