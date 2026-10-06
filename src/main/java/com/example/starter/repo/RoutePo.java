package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.SegmentLink;

import java.util.List;

/**
 * 航线当前状态记录（点列与通信档案均为当前版本的内容）。
 *
 * @param routeId  航线唯一标识
 * @param version  当前航线版本（从 1 开始）
 * @param points   当前版本有序航点
 * @param segments 当前版本每段通信档案；null 表示无通信档案（旧式航线，不适用通信审查）
 */
public record RoutePo(String routeId, int version, List<Point> points, List<SegmentLink> segments) {
}
