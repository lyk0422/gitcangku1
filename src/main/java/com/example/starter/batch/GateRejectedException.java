package com.example.starter.batch;

/**
 * 供应商准入门禁拒绝：当前滑动评分低于门槛。携带当前评分与门槛，响应 422。
 */
public class GateRejectedException extends RuntimeException {

    private final int currentScore;
    private final int threshold;

    public GateRejectedException(String supplierId, int currentScore, int threshold) {
        super("供应商 " + supplierId + " 当前评分 " + currentScore
                + " 低于准入门槛 " + threshold + "，新批次创建被拦截");
        this.currentScore = currentScore;
        this.threshold = threshold;
    }

    public int currentScore() {
        return currentScore;
    }

    public int threshold() {
        return threshold;
    }
}
