package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;

/**
 * 批次放行被未裁决离群值阻断（422）。响应携带实际待裁决数、要求值与差额，调用方无需猜测。
 */
public class OutlierBlockedException extends ApiException {

    private final int pendingOutliers;

    public OutlierBlockedException(int pendingOutliers) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "OUTLIERS_PENDING",
                "批次存在未裁决离群记录，放行被阻断");
        this.pendingOutliers = pendingOutliers;
    }

    public int getPendingOutliers() {
        return pendingOutliers;
    }

    /** 要求值：放行前允许的未裁决离群记录数（恒为 0）。 */
    public int getRequiredPending() {
        return 0;
    }

    /** 差额：仍需裁决的离群记录数。 */
    public int getRemaining() {
        return pendingOutliers;
    }
}
