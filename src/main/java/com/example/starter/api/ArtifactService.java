package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DeclarePeersRequest;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PeerDeclarationResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、锁定与历史查询，均保证写操作幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /**
     * 为已存在且未撤回的制品版本登记 peer 依赖（requestId 幂等）。
     * 每版本最多 3 条，登记后不可改；操作者非空，成功仍推进仓库版本。
     */
    PeerDeclarationResponse declarePeers(String requestId, String name, int version,
                                         DeclarePeersRequest request);

    /** 创建锁文件（requestId 幂等）。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);
}
