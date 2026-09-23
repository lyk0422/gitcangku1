package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 封锁窗口预览响应：切换单视图与当前全部相交 PUBLISHED 计划（含版本与占用），
 * 按业务键升序，占用按计划内序号返回。
 */
public record DisruptionPreviewResponse(DisruptionSwitchView disruption,
                                        List<DisruptionPlanSnapshotView> affectedPlans) {
}
