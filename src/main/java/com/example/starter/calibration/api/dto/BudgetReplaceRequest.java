package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 整批替换不确定度预算请求。新预算为完整后态：先整体校验，全部通过后才在一个事务内
 * 追加为新预算版本；任一非法项则整体失败，不改变放行状态且不产生新版本。
 *
 * @param components            完整分量集合（1～100 项），名称不得重复，值非负
 * @param expectedBudgetVersion 客户端基于的预算版本号；与当前版本不一致返回 410；
 *                              为 null 时不做版本前置校验（强制最新）
 */
public record BudgetReplaceRequest(List<ComponentRequest> components, Long expectedBudgetVersion) {
}
