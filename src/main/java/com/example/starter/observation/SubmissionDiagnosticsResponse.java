package com.example.starter.observation;

/**
 * 站点提交诊断响应：数量为实际值；接受率按要求给出实际分子、分母及差额说明。
 *
 * @param siteId              站点标识
 * @param totalSubmissions    提交总数（实际值）
 * @param acceptedCount       围栏内自动接受的提交数（实际值）
 * @param geofenceRejectedCount 围栏外被拒的提交数（实际值）
 * @param adjudicatedAcceptedCount 被拒后经人工接受的提交数（实际值）
 * @param pendingRejectedCount 被拒且尚未人工裁决的提交数（实际值）
 */
public record SubmissionDiagnosticsResponse(
        String siteId,
        int totalSubmissions,
        int acceptedCount,
        int geofenceRejectedCount,
        int adjudicatedAcceptedCount,
        int pendingRejectedCount) {
}
