package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DeprecationBatchRequest;
import com.example.starter.api.dto.DeprecationWindowResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、弃用窗口、锁定与历史查询，均保证写操作幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /** 批量登记弃用窗口：先校验完整替代图，任一失败整批不写入（requestId 幂等）。 */
    List<DeprecationWindowResponse> registerDeprecations(String requestId, DeprecationBatchRequest request);

    /** 撤销尚未生效的弃用窗口，记录保留（requestId 幂等）。 */
    DeprecationWindowResponse revokeDeprecation(String requestId, String name, int version);

    /** 查询全部弃用窗口（含已撤销历史），按登记先后排序；查询不修改状态。 */
    List<DeprecationWindowResponse> listDeprecations();

    /** 创建锁文件（requestId 幂等）。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 按重解析时刻重新裁决并生成新锁文件，历史锁不改写（requestId 幂等）。 */
    LockFileResponse reResolveLock(String requestId, long lockId);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);
}
