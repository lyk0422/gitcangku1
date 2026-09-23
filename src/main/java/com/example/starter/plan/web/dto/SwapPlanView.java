package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 交换单内单个计划的实际状态视图（稳定排序，按 scheduleKey 升序）。
 * 预览时 version/occupancies 为交换前实际状态；激活响应中为交换后状态。
 *
 * @param scheduleKey  计划业务键
 * @param version      计划当前版本
 * @param occupancies  计划当前占用段（左闭右开 UTC，稳定排序）
 */
public record SwapPlanView(String scheduleKey, int version, List<OccupancyView> occupancies) {
}
