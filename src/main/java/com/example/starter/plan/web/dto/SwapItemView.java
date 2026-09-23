package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 容量交换单中的单项视图（预览与证据共用，只读、稳定排序）。
 *
 * @param scheduleKey 计划业务键
 * @param version     计划版本：预览时为当前版本，激活证据中为交换后版本
 * @param before      交换前占用段（预览时为库内实际占用，证据中为不可变 BEFORE 快照）
 * @param after       交换后目标占用段（预览时为提交目标，证据中为不可变 AFTER 快照）
 */
public record SwapItemView(String scheduleKey, int version,
                           List<SwapSegmentView> before, List<SwapSegmentView> after) {
}
