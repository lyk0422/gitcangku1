package com.example.starter.timber;

/**
 * 批次移交申请单：签收后持有人变更，拒绝时保留申请与拒绝原因。
 *
 * @param id 主键
 * @param batchId 被移交批次ID
 * @param fromHolder 移交方持有人
 * @param toHolder 接收方持有人
 * @param status 状态：PENDING待签收，ACCEPTED已签收，REJECTED已拒绝
 * @param rejectReason 拒绝原因，仅REJECTED时有值，其余为null
 * @param requestId 移交申请幂等请求键
 */
public record TransferOrder(long id, long batchId, String fromHolder, String toHolder,
                            String status, String rejectReason, String requestId) {

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
}