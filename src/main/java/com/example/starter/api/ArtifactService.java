package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、锁定与历史查询，均保证写操作幂等。
 *
 * <p>幂等身份包含操作、操作者、资源及全部参数和期望版本；缺省操作者为 anonymous。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等，操作者 anonymous）。 */
    default ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request) {
        return registerArtifact(requestId, "anonymous", request);
    }

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, String operator, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等，操作者 anonymous，不校验期望仓库版本）。 */
    default ArtifactResponse withdrawArtifact(String requestId, String name, int version) {
        return withdrawArtifact(requestId, "anonymous", name, version, null);
    }

    /**
     * 撤回制品版本（requestId 幂等）。
     *
     * @param expectedRepositoryVersion 可选：携带时与当前仓库版本不一致返回 409
     */
    ArtifactResponse withdrawArtifact(String requestId, String operator, String name, int version,
                                      Long expectedRepositoryVersion);

    /** 创建锁文件（旧单根扁平规则，requestId 幂等，操作者 anonymous）。 */
    default LockFileResponse createLock(String requestId, LockRequest request) {
        return createLock(requestId, "anonymous", request);
    }

    /** 创建锁文件（旧单根扁平规则：全部依赖按 PUBLIC 处理，requestId 幂等）。 */
    LockFileResponse createLock(String requestId, String operator, LockRequest request);

    /** 创建带私有作用域的锁文件（requestId 幂等，操作者 anonymous）。 */
    default LockFileResponse createScopedLock(String requestId, LockRequest request) {
        return createScopedLock(requestId, "anonymous", request);
    }

    /** 创建带私有作用域的锁文件（requestId 幂等）。 */
    LockFileResponse createScopedLock(String requestId, String operator, LockRequest request);

    /** 查询全部历史锁文件，条目按（作用域路径，名称）排序返回。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);
}
