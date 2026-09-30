package com.example.starter.tag.domain;

/**
 * 标签交接状态。
 */
public enum HandoffStatus {

    /** 持有站点已发起，等待接收站点确认。 */
    PENDING,

    /** 接收站点已确认，标签持有权转移完成。 */
    CONFIRMED,

    /** 发起站点在确认前取消，持有权保持在原站点。 */
    CANCELLED,

    /** 接收站点拒绝，记录拒绝原因，持有权保持在原站点。 */
    REJECTED
}
