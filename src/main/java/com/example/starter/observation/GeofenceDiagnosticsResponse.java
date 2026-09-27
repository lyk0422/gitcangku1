package com.example.starter.observation;

/**
 * 站点提交诊断响应：各状态数量均为实际值，total = accepted + geofenceRejected + manuallyAccepted。
 *
 * @param siteId            站点标识
 * @param total             该站点提交总数（实际值）
 * @param accepted          自动接受的提交数（实际值）
 * @param geofenceRejected  仍为被拒状态的提交数（实际值）
 * @param manuallyAccepted  经人工接受的提交数（实际值）
 */
public record GeofenceDiagnosticsResponse(
        String siteId,
        long total,
        long accepted,
        long geofenceRejected,
        long manuallyAccepted) {
}
