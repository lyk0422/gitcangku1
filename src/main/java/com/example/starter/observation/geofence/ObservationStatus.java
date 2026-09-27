package com.example.starter.observation.geofence;

/**
 * 观测证据的围栏匹配结果与当前状态。
 * match_result 落库后不可变；status 仅允许经人工裁决由 GEOFENCE_REJECTED 改为 ACCEPTED。
 */
public enum ObservationStatus {

    /**
     * 接受：落在生效围栏内，或采集时刻站点无生效围栏；参与自动合并。
     */
    ACCEPTED,

    /**
     * 围栏拒绝：采集时刻落在生效围栏外；不参与自动合并，保留原始证据。
     */
    GEOFENCE_REJECTED
}
