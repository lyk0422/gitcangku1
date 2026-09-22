package com.example.starter.api.dto;

/**
 * 当前可用审核结论查询响应：
 * status=CURRENT 时 review 为最近一次且版本仍匹配的审核结果；
 * status=STALE 时表示无任何版本匹配的结论可用（含从未审核），review 为 null。
 */
public record CurrentReviewResponse(
        String status,
        ReviewResponse review,
        int currentRouteVersion,
        long currentAirspaceVersion) {

    public static CurrentReviewResponse current(ReviewResponse review) {
        return new CurrentReviewResponse("CURRENT", review, review.routeVersion(), review.airspaceVersion());
    }

    public static CurrentReviewResponse stale(int currentRouteVersion, long currentAirspaceVersion) {
        return new CurrentReviewResponse("STALE", null, currentRouteVersion, currentAirspaceVersion);
    }
}
