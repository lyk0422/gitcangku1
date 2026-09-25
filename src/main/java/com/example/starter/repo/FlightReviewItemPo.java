package com.example.starter.repo;

/**
 * 航班批量审查逐航线明细记录（不可变）。
 *
 * @param reviewId 所属批量审查记录标识
 * @param flightId 被审查航班标识
 * @param result   单航线结果：APPROVED / REJECTED
 * @param reason   拒绝原因码（FlightRejectReason）；通过为 null
 * @param detail   拒绝细节；通过为 null
 */
public record FlightReviewItemPo(String reviewId, String flightId, String result,
                                 String reason, String detail) {
}
