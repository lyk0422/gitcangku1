package com.example.starter.blind.dto;

import java.util.List;

/**
 * 退役历史列表视图：按退役时间、批次编号升序，区间过滤为 UTC 毫秒左闭右开。
 *
 * @param items 退役批次摘要列表
 */
public record RetirementListView(
        List<RetirementView> items
) {
}
