package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 站台长度调整响应：携带调整前后长度及本次被标记 PLATFORM_RISK 的计划列表。
 *
 * @param code                  站台代码
 * @param previousLength        调整前有效长度（辆）
 * @param effectiveLength       调整后有效长度（辆）
 * @param affectedScheduleKeys  本次下调新标记风险的计划业务键（已存在未解除风险的计划不重复列出）
 */
public record PlatformLengthResponse(String code, int previousLength, int effectiveLength,
                                     List<String> affectedScheduleKeys) {
}
