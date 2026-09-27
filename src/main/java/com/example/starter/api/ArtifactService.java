package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DeprecationBatchRequest;
import com.example.starter.api.dto.DeprecationBatchResponse;
import com.example.starter.api.dto.DeprecationDiagnosisResponse;
import com.example.starter.api.dto.DeprecationEventView;
import com.example.starter.api.dto.DeprecationWindowView;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;

import java.time.Instant;
import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、弃用窗口、锁定与历史查询，均保证写操作幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /** 创建锁文件（requestId 幂等）；按解析时刻裁决弃用窗口，命中已生效窗口返回 422。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目与固化的弃用警告。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);

    /** 批量设置弃用窗口：先校验完整最终替代图，任一失败整次不写入（requestId 幂等）。 */
    DeprecationBatchResponse setDeprecations(String requestId, DeprecationBatchRequest request);

    /** 撤销弃用窗口，仅允许尚未生效窗口；撤销记录保留（requestId 幂等）。 */
    DeprecationWindowView revokeDeprecation(String requestId, String name, int version);

    /** 查询全部弃用窗口当前状态（含已撤销），按名称、版本升序；只读。 */
    List<DeprecationWindowView> listDeprecations();

    /** 按制品版本查询弃用窗口当前状态，不存在抛出 404；只读。 */
    DeprecationWindowView getDeprecation(String name, int version);

    /** 查询全部弃用窗口只追加历史事件，按提交顺序；只读。 */
    List<DeprecationEventView> listDeprecationEvents();

    /** 查询某制品版本的弃用窗口历史事件，按提交顺序；只读。 */
    List<DeprecationEventView> getDeprecationEvents(String name, int version);

    /**
     * 只读诊断预演：按 at（null 取当前时钟）对根闭包做弃用裁决，不创建锁文件、不改变状态。
     */
    DeprecationDiagnosisResponse diagnose(String rootName, int rootVersion, Instant at);
}
