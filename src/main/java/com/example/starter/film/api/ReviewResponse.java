package com.example.starter.film.api;

import com.example.starter.film.domain.MasterStatus;

import java.time.Instant;
import java.util.List;

/**
 * 整片审核结果。
 *
 * @param masterNo  不可变母版编号
 * @param status    母版状态
 * @param segments  审核瞬间固定的每段版次与摘要，按序号升序
 * @param createdAt 审核通过时间，UTC
 * @param replayed  true 表示同一 requestId 的重复审核返回了已存在母版
 */
public record ReviewResponse(String masterNo, MasterStatus status, List<MasterSegmentView> segments,
                             Instant createdAt, boolean replayed) {
}
