package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.BatchDeprecationRequest;
import com.example.starter.api.dto.BatchDeprecationResponse;
import com.example.starter.api.dto.DeprecationEventResponse;
import com.example.starter.api.dto.DeprecationRequest;
import com.example.starter.api.dto.DeprecationResponse;
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

    /** 登记单个弃用窗口（requestId 幂等）：替代版本必须同名、存在且未撤回，替代图不得成环。 */
    DeprecationResponse registerDeprecation(String requestId, DeprecationRequest request);

    /** 批量设置弃用窗口（requestId 幂等）：先校验完整替代图，任一失败整次不写入。 */
    BatchDeprecationResponse batchDeprecations(String requestId, BatchDeprecationRequest request);

    /** 撤销尚未生效的弃用窗口（requestId 幂等）；撤销记录保留，不隐藏历史。 */
    DeprecationResponse revokeDeprecation(String requestId, long deprecationId);

    /** 创建锁文件（requestId 幂等），按可注入时钟的当前时刻裁决弃用窗口。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 查询全部历史锁文件，条目与警告均按稳定排序返回。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null（由调用方转 404）。 */
    LockFileResponse getLock(long id);

    /** 查询全部有效（ACTIVE）弃用窗口，按制品、版本稳定排序；只读不改状态。 */
    List<DeprecationResponse> listActiveDeprecations();

    /** 按 ID 查询弃用窗口（含已撤销），不存在返回 null（由调用方转 404）。 */
    DeprecationResponse getDeprecation(long id);

    /** 查询弃用事件历史（含登记、批量调整与撤销），按时间、ID 稳定排序；只读不改状态。 */
    List<DeprecationEventResponse> listDeprecationHistory();
}
