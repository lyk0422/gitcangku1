package com.example.starter.api;

import com.example.starter.api.dto.LicensePolicyRequest;
import com.example.starter.api.dto.LicensePolicyResponse;
import com.example.starter.api.dto.MissingNoticeView;
import com.example.starter.api.dto.NarrowRegionsRequest;
import com.example.starter.api.dto.NoticeCoverageView;
import com.example.starter.api.dto.NoticeTextRequest;
import com.example.starter.api.dto.NoticeTextResponse;
import com.example.starter.api.dto.PolicyHitView;
import com.example.starter.api.dto.PublishRequest;
import com.example.starter.api.dto.PublishResponse;

import java.util.List;

/**
 * 许可证告知业务服务：告知文本版本管理、许可证策略登记、
 * 锁定图发布门禁（整批原子）与历史发布快照查询。
 */
public interface LicenseService {

    /** 登记告知文本版本（初始 DRAFT，requestId 幂等）。 */
    NoticeTextResponse registerNoticeText(String requestId, NoticeTextRequest request);

    /** 批准告知文本版本（requestId 幂等）。 */
    NoticeTextResponse approveNoticeText(String requestId, String textKey, int version);

    /** 撤销告知文本版本，仅影响后续发布（requestId 幂等）。 */
    NoticeTextResponse withdrawNoticeText(String requestId, String textKey, int version);

    /** 缩窄告知文本地区覆盖，仅影响后续发布（requestId 幂等）。 */
    NoticeTextResponse narrowNoticeTextRegions(String requestId, String textKey, int version,
                                               NarrowRegionsRequest request);

    /** 查询告知文本版本的地区覆盖与状态。 */
    NoticeCoverageView getNoticeCoverage(String textKey, int version);

    /** 基于已确认锁定图基线或既有制品坐标登记许可证策略（requestId 幂等）。 */
    LicensePolicyResponse registerPolicy(String requestId, LicensePolicyRequest request);

    /** 查询全部许可证策略，按 ID 升序。 */
    List<LicensePolicyResponse> listPolicies();

    /** 查询锁定图最终依赖闭包内全部制品的策略命中路径。 */
    List<PolicyHitView> listPolicyHits(long lockFileId);

    /** 按目标地区预检锁定图：返回全部告知缺失/未批准/地区不覆盖项。 */
    List<MissingNoticeView> listMissingNotices(long lockFileId, List<String> targetRegions);

    /**
     * 批量发布锁定图：逐图校验最终依赖闭包的告知绑定、文本批准状态与地区覆盖，
     * 任一不满足则整批 422 不发布任何部分；noticeKey 幂等，异参 409，失败不占键。
     */
    PublishResponse publishLocks(PublishRequest request);

    /** 查询全部历史发布快照，按 ID 升序。 */
    List<PublishResponse> listPublishes();

    /** 按 ID 查询历史发布快照，不存在返回 404。 */
    PublishResponse getPublish(long id);
}
