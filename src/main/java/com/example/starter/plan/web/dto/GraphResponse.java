package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 线路图视图：当前版本、站点集合与有向区段清单。version 为 0 表示尚未建图。
 */
public record GraphResponse(int version, List<String> stations, List<GraphSegmentView> segments) {
}
