package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MirrorFailoverResponse;
import com.example.starter.api.dto.MirrorView;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterMirrorsRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、镜像源登记与可用性、锁定、故障切换与历史查询，
 * 均保证写操作幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /** 为制品版本登记 1～3 个镜像源（requestId 幂等）。 */
    List<MirrorView> registerMirrors(String requestId, String name, int version,
                                     RegisterMirrorsRequest request);

    /** 标记镜像可用/不可用（requestId 幂等），仅影响后续新锁定与故障切换查询。 */
    MirrorView setMirrorAvailability(String requestId, String name, int version,
                                     String mirrorId, boolean available);

    /** 查询制品版本登记的全部镜像明细（含不可用），按优先级升序。 */
    List<MirrorView> listMirrors(String name, int version);

    /** 创建锁文件（requestId 幂等）。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);

    /**
     * 镜像故障切换：返回锁文件中该名称锁定版本登记的镜像里当前第一个可用镜像；
     * 名称不在锁文件解析集合返回 404，全部不可用返回 422。只读，不改写锁文件。
     */
    MirrorFailoverResponse failoverMirror(long lockFileId, String name);
}
