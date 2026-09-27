package com.example.starter.observation.geofence;

import java.util.List;

/**
 * 地理围栏语义校验异常：经纬度范围、围栏边界、生效区间、批量数量等非法时抛出，
 * 由全局处理器转为 422 响应；抛出前不写库，整批回滚且 requestId 不占键。
 */
public class GeofenceValidationException extends RuntimeException {

    private final List<ValidationDetail> details;

    public GeofenceValidationException(String message, List<ValidationDetail> details) {
        super(message);
        this.details = List.copyOf(details);
    }

    public List<ValidationDetail> details() {
        return details;
    }
}
