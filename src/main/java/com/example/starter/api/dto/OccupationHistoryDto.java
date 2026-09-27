package com.example.starter.api.dto;

import java.util.List;

/**
 * 占用历史：占用当前状态与全部不可变事件（创建、让渡、起飞、重新申请关联）。
 *
 * @param occupationId 占用标识
 * @param status       当前状态：ACTIVE / YIELDED / DEPARTED
 * @param events       历史事件，按发生时间、事件类型升序
 */
public record OccupationHistoryDto(String occupationId, String status,
                                   List<OccupationHistoryEvent> events) {
}
