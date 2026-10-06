package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.ScopedLockFileResponse;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、锁定与历史查询，均保证写操作幂等。
 *
 * <p>幂等身份包含操作、操作者、资源及全部参数和期望版本，结构化比较；
 * 操作者为 null 或空白时按 "anonymous" 处理。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等，匿名操作者）。 */
    default ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request) {
        return registerArtifact(requestId, null, request);
    }

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, String operator, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等，匿名操作者，不校验期望仓库版本）。 */
    default ArtifactResponse withdrawArtifact(String requestId, String name, int version) {
        return withdrawArtifact(requestId, null, name, version, null);
    }

    /** 撤回制品版本（requestId 幂等，可携带期望仓库版本）。 */
    ArtifactResponse withdrawArtifact(String requestId, String operator, String name, int version,
                                      Long expectedRepositoryVersion);

    /** 创建锁文件（requestId 幂等，匿名操作者；旧单根入口，全图同名只能一版）。 */
    default LockFileResponse createLock(String requestId, LockRequest request) {
        return createLock(requestId, null, request);
    }

    /** 创建锁文件（requestId 幂等；旧单根入口，全图同名只能一版）。 */
    LockFileResponse createLock(String requestId, String operator, LockRequest request);

    /** 创建作用域锁文件（requestId 幂等，匿名操作者；新锁入口，启用 PRIVATE 作用域）。 */
    default ScopedLockFileResponse createScopedLock(String requestId, LockRequest request) {
        return createScopedLock(requestId, null, request);
    }

    /** 创建作用域锁文件（requestId 幂等；新锁入口，启用 PRIVATE 作用域）。 */
    ScopedLockFileResponse createScopedLock(String requestId, String operator, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);

    /** 查询全部历史作用域锁文件。 */
    List<ScopedLockFileResponse> listScopedLocks();

    /** 按 ID 查询单个作用域锁文件，不存在返回 null。 */
    ScopedLockFileResponse getScopedLock(long id);
}
