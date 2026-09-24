package com.example.starter.exposure.domain;

/**
 * 曝光申请裁决结果。
 * <ul>
 *     <li>RESERVED：通过静默与额度校验，已创建预占并占用两级额度；</li>
 *     <li>SUPPRESSED：落入访客静默时段，未创建预占、未占用额度，响应携带静默结束 UTC 时刻。</li>
 * </ul>
 */
public enum ApplyOutcome {
    RESERVED,
    SUPPRESSED
}
