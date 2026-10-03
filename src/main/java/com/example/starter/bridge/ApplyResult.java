package com.example.starter.bridge;

/**
 * 签发结果：Approved 核准（含是否幂等重放）；Rejected 拒绝（含原因与依据）。
 */
public sealed interface ApplyResult {

    /**
     * 核准结果。
     *
     * @param permit 已签发许可
     * @param replayed 是否为同参幂等重放
     */
    record Approved(BridgePermit permit, boolean replayed) implements ApplyResult {
    }

    /**
     * 拒绝结果。
     *
     * @param reason 拒绝原因
     * @param detail 拒绝依据明细
     */
    record Rejected(RejectReason reason, String detail) implements ApplyResult {
    }
}