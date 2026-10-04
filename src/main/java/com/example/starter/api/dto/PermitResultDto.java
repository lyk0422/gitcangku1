package com.example.starter.api.dto;

/**
 * 运动间隔许可结果。轨迹端点为创建时不可变快照，单位米（最多 3 位小数），
 * 航线后续更新不影响已许可轨迹。
 *
 * @param permitId     许可唯一标识（不可变）
 * @param routeId      引用的两点航线标识
 * @param routeVersion 申请时引用的航线版本
 * @param startSecond  有效起点（含），UTC epoch 整秒
 * @param endSecond    有效终点（不含），UTC epoch 整秒
 * @param status       ACTIVE 有效参与间隔校验；REVOKED 已撤销
 * @param ax           起点 X 快照（米）
 * @param ay           起点 Y 快照（米）
 * @param bx           终点 X 快照（米）
 * @param by           终点 Y 快照（米）
 */
public record PermitResultDto(
        String permitId,
        String routeId,
        int routeVersion,
        long startSecond,
        long endSecond,
        String status,
        double ax,
        double ay,
        double bx,
        double by) {
}