package com.example.starter.exposure.domain;

/**
 * 曝光申请类型。
 * <ul>
 *     <li>NORMAL：普通申请，不参与轮换计数；访客进入轮换流程后禁止再发起（422）；</li>
 *     <li>ROTATION：轮换申请，按访客已确认轮换数对创意数取模选定创意并冻结。</li>
 * </ul>
 */
public enum ApplyType {
    NORMAL,
    ROTATION
}
