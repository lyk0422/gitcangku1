package com.example.starter.domain;

import java.time.Instant;

/**
 * 航段申请。
 *
 * @param id                     主键
 * @param vesselCode             船舶代号
 * @param requiredGrade          船舶要求的引航员等级，不得高于被指派人资格等级
 * @param estStartUtc            预计开始时刻，UTC，含
 * @param estEndUtc              预计结束时刻，UTC，不含，须晚于开始时刻
 * @param boardingPoint          当前登轮点，登轮前可修改
 * @param originalBoardingPoint  首次申请的登轮点，修改后仍保留原值
 * @param status                 航段状态
 * @param cancelledAtUtc         取消时刻，UTC，未取消为 null
 * @param createdAtUtc           创建时刻，UTC
 */
public record Segment(long id, String vesselCode, int requiredGrade, Instant estStartUtc, Instant estEndUtc,
                      String boardingPoint, String originalBoardingPoint, SegmentStatus status,
                      Instant cancelledAtUtc, Instant createdAtUtc) {
}