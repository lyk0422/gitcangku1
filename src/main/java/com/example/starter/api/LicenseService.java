package com.example.starter.api;

import com.example.starter.api.dto.BindNoticeRequest;
import com.example.starter.api.dto.LicenseCheckView;
import com.example.starter.api.dto.LicensePolicyResponse;
import com.example.starter.api.dto.NarrowRegionsRequest;
import com.example.starter.api.dto.NoticeBindingResponse;
import com.example.starter.api.dto.NoticeTextResponse;
import com.example.starter.api.dto.RegisterNoticeTextRequest;
import com.example.starter.api.dto.RegisterPolicyRequest;
import com.example.starter.api.dto.ReleaseRequest;
import com.example.starter.api.dto.ReleaseSnapshotResponse;

import java.util.List;

/**
 * 许可证告知与发布门禁业务服务：策略/文本/绑定登记、门禁校验、原子发布与历史查询。
 *
 * <p>所有写操作以 X-Request-Id 为幂等键；发布的 noticeKey 即请求头，
 * 指纹含锁定图版本、地区、规范化制品集合与文本版本，同键同参重放首次完整响应，异参 409。
 */
public interface LicenseService {

    /** 登记许可证策略（requestId 幂等）。 */
    LicensePolicyResponse registerPolicy(String requestId, RegisterPolicyRequest request);

    /** 登记告知文本版本（requestId 幂等），初始为 DRAFT。 */
    NoticeTextResponse registerNoticeText(String requestId, RegisterNoticeTextRequest request);

    /** 批准告知文本版本：DRAFT -> APPROVED（requestId 幂等）。 */
    NoticeTextResponse approveNoticeText(String requestId, String noticeKey, int version);

    /** 撤销告知文本版本：APPROVED -> WITHDRAWN（requestId 幂等），仅影响后续发布。 */
    NoticeTextResponse withdrawNoticeText(String requestId, String noticeKey, int version);

    /** 缩窄已批准文本的地区覆盖（requestId 幂等），新集合须为当前集合子集。 */
    NoticeTextResponse narrowNoticeRegions(String requestId, String noticeKey, int version,
                                           NarrowRegionsRequest request);

    /** 登记告知绑定（requestId 幂等）。 */
    NoticeBindingResponse bindNotice(String requestId, BindNoticeRequest request);

    /**
     * 发布单图或批量锁定图（requestId 即 noticeKey，幂等）。
     * 任一图告知缺失、文本未批准或地区不覆盖则 422 并稳定返回命中路径，整批不发布。
     */
    ReleaseSnapshotResponse release(String requestId, ReleaseRequest request);

    /** 查询某锁定图的许可证命中路径与缺失告知（发布前预检视图）。 */
    LicenseCheckView checkLock(long lockFileId, List<String> regions);

    /** 查询某告知文本版本的当前地区覆盖。 */
    NoticeTextResponse getNoticeText(String noticeKey, int version);

    /** 查询全部历史发布批次。 */
    List<ReleaseSnapshotResponse> listReleases();

    /** 按 ID 查询单个历史发布快照。 */
    ReleaseSnapshotResponse getRelease(long releaseId);
}
