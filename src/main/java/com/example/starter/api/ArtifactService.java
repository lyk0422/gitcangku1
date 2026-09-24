package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.ReresolveReportResponse;
import com.example.starter.api.dto.ReresolveRequest;

import java.util.List;

/**
 * 制品仓库业务服务：登记/撤回、锁定与历史查询，均保证写操作幂等。
 */
public interface ArtifactService {

    /** 登记新制品版本（requestId 幂等）。 */
    ArtifactResponse registerArtifact(String requestId, RegisterArtifactRequest request);

    /** 撤回制品版本（requestId 幂等）。 */
    ArtifactResponse withdrawArtifact(String requestId, String name, int version);

    /** 创建锁文件（requestId 幂等）。 */
    LockFileResponse createLock(String requestId, LockRequest request);

    /** 查询全部历史锁文件，按名称排序返回条目。 */
    List<LockFileResponse> listLocks();

    /** 按 ID 查询单个锁文件，不存在返回 null。 */
    LockFileResponse getLock(long id);

    /** 对已有锁文件执行重解析并固化不可变报告（reresolveKey 全局唯一、X-Request-Id 幂等）。 */
    ReresolveReportResponse reresolve(String requestId, long lockFileId, ReresolveRequest request);

    /** 按报告 ID 查询单条重解析报告，不存在抛 NOT_FOUND。 */
    ReresolveReportResponse getReresolveReport(long id);

    /** 按原锁文件查询其全部重解析报告，按报告 ID 升序。 */
    List<ReresolveReportResponse> listReresolveReports(long lockFileId);
}
