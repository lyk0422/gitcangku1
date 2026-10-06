package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 线路图版本视图，含版本号、站点与有向区段。
 */
public record RouteGraphResponse(int version, List<String> stations,
                                 List<RouteSegmentView> segments) {
}
