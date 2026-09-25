package com.example.starter.api;

import com.example.starter.api.dto.AdvisoryRequest;
import com.example.starter.api.dto.AdvisoryResponse;
import com.example.starter.api.dto.ExceptionRequest;
import com.example.starter.api.dto.ExceptionResponse;
import com.example.starter.api.dto.PublishSnapshotResponse;
import com.example.starter.api.dto.VulnerabilityHitResponse;

import java.util.List;

/**
 * 制品漏洞豁免与锁定图发布门禁业务服务。
 *
 * <p>公告更新、豁免确认、撤销与发布均在串行化写事务内按提交顺序裁决；
 * 豁免双人确认以 exceptionKey 指纹幂等，同键成功重放首次结果，失败不占键。
 */
public interface SecurityService {

    /** 写入或更新漏洞公告（按 漏洞编号+受影响制品坐标 自然幂等）。 */
    AdvisoryResponse upsertAdvisory(AdvisoryRequest request);

    /** 列出全部漏洞公告，按漏洞编号与坐标排序。 */
    List<AdvisoryResponse> listAdvisories();

    /**
     * 创建或确认豁免：第一名审核人产生 PENDING，第二名不同审核人产生 CONFIRMED 双人快照。
     * 以 exceptionKey 指纹保证幂等；请求体 lockFileId 必须与作用域路径一致。
     */
    ExceptionResponse confirmException(String requestId, ExceptionRequest request, String reviewer);

    /** 撤销豁免；仅影响后续发布，不改写已成功发布的快照。 */
    ExceptionResponse revokeException(String requestId, long exceptionId, String reviewer);

    /** 查询某锁定图的全部豁免（含已撤销），按 ID 升序。 */
    List<ExceptionResponse> listExceptions(long lockFileId);

    /** 查询某锁定图当前命中的全部未过期公告及其豁免作用域状态。 */
    List<VulnerabilityHitResponse> listVulnerabilityHits(long lockFileId);

    /**
     * 发布锁定图：存在未过期 CRITICAL 命中且缺少有效双人豁免时整次 422，
     * 列出全部阻断项；成功则复制不可变发布快照（requestId 幂等）。
     */
    PublishSnapshotResponse publish(String requestId, long lockFileId);

    /** 查询某锁定图的全部历史发布快照，按发布时间升序。 */
    List<PublishSnapshotResponse> listPublishes(long lockFileId);
}
