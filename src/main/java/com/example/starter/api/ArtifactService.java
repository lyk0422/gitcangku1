package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LicenseResponse;
import com.example.starter.api.dto.LicenseViolationResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.SetPolicyRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、许可证登记、命名空间策略、锁定与历史查询，
 * 写操作均保证幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /**
     * 登记/修改制品版本许可证（requestId 幂等）。
     * license 为 null/空表示清除登记恢复 UNKNOWN；已撤回版本修改返回 409。
     */
    LicenseResponse setLicense(String requestId, String name, int version, String license);

    /** 创建或整体替换命名空间许可证策略（requestId 幂等，expectedVersion 乐观并发）。 */
    PolicyResponse setPolicy(String requestId, SetPolicyRequest request);

    /** 查询命名空间当前策略；未配置时返回 null。 */
    PolicyResponse getPolicy(String namespace);

    /** 创建锁文件（requestId 幂等）；解析成功后校验完整闭包的许可证策略。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /**
     * 违规诊断：按当前仓库与策略试算同一锁定请求，返回稳定排序的违规明细。
     * 只读操作，不生成锁文件、不占用 requestId。
     */
    List<LicenseViolationResponse> diagnoseLock(LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);
}
