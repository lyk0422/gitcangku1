package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 整体替换不确定度预算请求。components 为完整后态：先整体校验（非负、名称非空不重复），
 * 全部合法才在一个事务内提交；任一失败不改变放行状态与既有预算。
 *
 * @param measurementKey  测量业务键
 * @param expectedVersion 客户端持有的测量版本号；与当前最新不一致返回 410；null 表示不校验
 * @param components      完整分量集合（允许空列表，表示零分量预算）
 */
public record UpdateBudgetRequest(String measurementKey, Integer expectedVersion,
                                  List<ComponentRequest> components) {
}
