package com.example.starter.exposure.suppression;

import com.example.starter.exposure.web.SuppressionJoinRequest;
import com.example.starter.exposure.web.SuppressionReleaseRequest;
import com.example.starter.exposure.web.SuppressionResponse;

/**
 * 访客受众抑制名单业务服务。
 *
 * <p>抑制优先于公告与展示位的可投放规则。加入名单时原子取消该访客全部未确认
 * （RESERVED）曝光预占并释放两级额度；已确认曝光保留历史不回退。抑制生效期间
 * 新曝光预占返回 409 且不占额度；解除只允许后续新申请，不恢复已取消预占。</p>
 */
public interface SuppressionService {

    /**
     * 加入抑制名单。
     * <ul>
     *     <li>首次加入或解除后再次加入：开启新抑制周期，原子取消全部 RESERVED 预占；</li>
     *     <li>已在名单中重复加入（新 requestId）：按当前状态返回稳定结果，不重复取消；</li>
     *     <li>同 requestId 重放：重放首次成功结果（含首次取消数量）；</li>
     *     <li>同 requestId 换访客或换动作：409。</li>
     * </ul>
     */
    SuppressionResponse join(SuppressionJoinRequest request);

    /**
     * 解除抑制名单。仅影响后续申请，不恢复已取消预占；
     * 重复解除按当前状态返回稳定结果。
     */
    SuppressionResponse release(String visitorId, SuppressionReleaseRequest request);

    /** 查询抑制起止、取消的预占、额度变化周期与历史已确认曝光。 */
    SuppressionResponse getSuppression(String visitorId);
}
