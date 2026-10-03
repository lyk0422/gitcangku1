package com.example.starter.calibration.api;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * 批次放行被阻断（422）：存在未裁决离群记录。details 携带实际值、要求值与差额。
 */
public class OutliersPendingException extends ApiException {

    private final Map<String, Object> details;

    public OutliersPendingException(int pendingCount) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "OUTLIERS_PENDING",
                "批次存在未裁决离群记录，放行被阻断");
        this.details = Map.of(
                "actual", pendingCount,
                "required", 0,
                "shortfall", pendingCount);
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}