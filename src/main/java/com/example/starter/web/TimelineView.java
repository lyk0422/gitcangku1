package com.example.starter.web;

import java.util.List;

/**
 * 花序时间线视图：窗口信息、花粉来源与操作及结果记录。
 *
 * @param inflorescence 花序与窗口信息
 * @param operations 操作及结果确认记录，按登记先后排序
 */
public record TimelineView(InflorescenceView inflorescence, List<OperationView> operations) {
}