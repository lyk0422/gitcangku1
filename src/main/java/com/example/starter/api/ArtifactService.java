package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.WithdrawRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、锁定与历史查询，均保证写操作幂等。
 *
 * <p>每个写请求都携带非空操作者；撤回与锁定在写事务内核对同一
 * expectedRepositoryVersion，保证与登记使用相同的版本一致性协议。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等；核对期望仓库版本）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version,
                                      WithdrawRequest request);

    /** 创建锁文件（requestId 幂等；核对期望仓库版本）。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);
}
