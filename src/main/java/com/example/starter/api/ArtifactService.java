package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MirrorDetailView;
import com.example.starter.api.dto.MirrorResponse;
import com.example.starter.api.dto.MirrorView;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterMirrorRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、镜像源登记与可用性切换、锁定、故障切换与历史查询，
 * 均保证写操作幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /** 为制品版本登记镜像源（requestId 幂等）。 */
    MirrorResponse registerMirror(String requestId, String name, int version,
                                  RegisterMirrorRequest request);

    /** 切换镜像源可用性（requestId 幂等），available=false 标记不可用，true 恢复可用。 */
    MirrorResponse setMirrorAvailability(String requestId, String name, int version,
                                         String mirrorId, boolean available);

    /** 查询制品版本的镜像登记明细（含不可用），按优先级升序。 */
    List<MirrorDetailView> listMirrors(String name, int version);

    /** 创建锁文件（requestId 幂等）。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);

    /**
     * 镜像故障切换查询：返回锁文件中该名称锁定版本当前第一个可用镜像（只读，不改写锁文件）。
     * 名称不在锁文件中抛出 404；全部镜像不可用或未登记镜像抛出 422。
     */
    MirrorView resolveMirror(long lockId, String name);
}
