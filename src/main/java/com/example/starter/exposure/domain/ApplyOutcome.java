package com.example.starter.exposure.domain;

/**
 * 申请曝光的裁决结果。
 * <ul>
 *     <li>RESERVED：通过静默与额度判定，已创建预占并占用两级额度；</li>
 *     <li>SUPPRESSED：落在访客静默时段内被抑制，不创建预占、不占额度。</li>
 * </ul>
 */
public enum ApplyOutcome {
    RESERVED,
    SUPPRESSED
}
