package com.example.starter.observation;

import java.util.List;

/**
 * 批量离线观测提交响应：携带各明细结果与实际数量统计（total = accepted + geofenceRejected）。
 *
 * @param requestId        批量请求标识
 * @param total            本批明细总数（实际值）
 * @param accepted         被接受并参与自动合并的明细数（实际值）
 * @param geofenceRejected 落在围栏外被拒的明细数（实际值）
 * @param items            各明细结果，按提交顺序排列
 */
public record BatchGeofenceSubmissionResponse(
        String requestId,
        int total,
        int accepted,
        int geofenceRejected,
        List<GeofenceSubmissionResponse> items) {
}
