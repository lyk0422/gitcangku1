package com.example.starter.api.dto;

/**
 * 占用明细。时段与高度为左闭右开区间；让渡信息关联走廊版本与处置结果。
 *
 * @param occupationId    占用标识
 * @param routeId         占用航线标识
 * @param routeVersion    占用审查的航线版本
 * @param kind            REGULAR / EMERGENCY
 * @param status          ACTIVE / YIELDED / DEPARTED
 * @param yieldable       是否可被强制让渡
 * @param startUtc        占用起点（含），epoch 毫秒（UTC）
 * @param endUtc          占用终点（不含），epoch 毫秒（UTC）
 * @param altMin          高度下限（含），米
 * @param altMax          高度上限（不含），米
 * @param reviewId        授权审查记录标识
 * @param airspaceVersion 占用审查时的全局空域版本
 * @param supersededBy    重新申请生成的新占用标识；未接替为 null
 * @param createdAt       创建时间，epoch 毫秒（UTC）
 * @param departedAt      起飞登记时间，epoch 毫秒（UTC）；未起飞为 null
 * @param yield           让渡记录；未被让渡为 null
 */
public record OccupationDetail(String occupationId, String routeId, int routeVersion,
                               String kind, String status, boolean yieldable,
                               long startUtc, long endUtc, int altMin, int altMax,
                               String reviewId, long airspaceVersion,
                               String supersededBy, long createdAt, Long departedAt,
                               YieldRecordDto yield) {
}
