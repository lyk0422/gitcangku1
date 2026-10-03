package com.example.starter.model;

import java.time.Instant;

/**
 * 航段行记录，时间均为 UTC。
 *
 * @param id            主键
 * @param vesselCode    船舶代号
 * @param requiredLevel 船舶要求的引航员资格等级
 * @param estStartUtc   预计开始时间（UTC）
 * @param estEndUtc     预计结束时间（UTC）
 * @param boardingPoint 当前登轮点
 * @param status        航段状态
 * @param createdAtUtc  创建时间（UTC）
 * @param cancelledAtUtc 取消时间（UTC），未取消为 null
 */
public record SegmentRow(long id, String vesselCode, int requiredLevel, Instant estStartUtc,
                         Instant estEndUtc, String boardingPoint, SegmentStatus status,
                         Instant createdAtUtc, Instant cancelledAtUtc) {
}