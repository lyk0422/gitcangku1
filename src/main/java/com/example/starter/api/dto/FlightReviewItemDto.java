package com.example.starter.api.dto;

/**
 * 批量审查中单航线结果明细。
 *
 * @param flightId 被审查航班标识
 * @param result   单航线结果：APPROVED / REJECTED
 * @param reason   拒绝原因码；通过为 null
 * @param detail   拒绝细节；通过为 null
 */
public record FlightReviewItemDto(String flightId, String result, String reason, String detail) {
}
