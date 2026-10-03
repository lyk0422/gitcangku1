package com.example.starter.service.view;

import java.util.List;

/**
 * 电芯明细视图：基本信息、完整读数链与隔离原因。
 *
 * @param cell 电芯基本信息
 * @param readings 读数链，按测量时刻升序
 * @param isolation 最近一次隔离信息，未隔离为 null
 */
public record CellDetailView(CellView cell, List<ReadingView> readings, IsolationView isolation) {
}