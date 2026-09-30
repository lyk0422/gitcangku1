package com.example.starter.exposure.suppression;

import com.example.starter.exposure.web.SuppressionActionRequest;
import com.example.starter.exposure.web.SuppressionResponse;

/**
 * 访客受众抑制名单业务服务。
 *
 * <p>抑制优先于公告与展示位的可投放规则：生效期间新曝光预占一律 409。
 * 加入名单原子取消访客全部未确认预占并释放额度，已确认曝光保留；
 * 解除只允许后续申请，不恢复已取消预占。</p>
 */
public interface SuppressionService {

    /** 加入抑制名单：原子取消该访客全部未确认预占并释放对应额度。 */
    SuppressionResponse suppress(String visitorId, SuppressionActionRequest request);

    /** 解除抑制：仅放行后续申请，不恢复已取消预占。 */
    SuppressionResponse release(String visitorId, SuppressionActionRequest request);

    /** 查询抑制起止、最近一批取消的预占明细与额度变化。 */
    SuppressionResponse getSuppression(String visitorId);
}
