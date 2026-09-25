package com.example.starter.api.dto;

/**
 * 航班最近一次审查原因查询结果。
 *
 * @param reviewId 所属批量审查记录标识
 * @param flightId 航班标识
 * @param result   单航线结果：APPROVED / REJECTED
 * @param reason   拒绝原因码；通过为 null
 * @param detail   拒绝细节；通过为 null
 */
public record FlightReviewReasonDto(String reviewId, String flightId, String result,
                                    String reason, String detail) {
}
