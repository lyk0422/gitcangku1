package com.example.starter.domain;

import java.util.List;

/**
 * 航线持久化记录：routeId 唯一，version 从 1 开始，每次成功替换点列加一。
 */
public record RouteRecord(String routeId, int version, List<Point> points) {
}
