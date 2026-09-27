package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 计划明细中的站停视图（草稿编辑态或历史原样返回），按 seq 升序稳定排序。
 *
 * @param seq          站停沿计划路径的序号，从 0 开始
 * @param trainNo      停靠列车编号
 * @param stationId    站点业务 ID
 * @param arrivalUtc   到达时刻（含），UTC；null 表示无到达（始发）
 * @param departureUtc 发车时刻（不含），UTC；null 表示无发车（终到）
 */
public record StationStopView(int seq, String trainNo, String stationId,
                              Instant arrivalUtc, Instant departureUtc) {
}
