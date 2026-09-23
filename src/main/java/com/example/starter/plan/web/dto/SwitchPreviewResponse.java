package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 封锁窗口预览响应：切换单信息与当前全部相交 PUBLISHED 计划（含版本与占用），只读不写。
 */
public record SwitchPreviewResponse(SwitchView switchInfo, List<SwitchPlanView> affectedPlans) {
}
