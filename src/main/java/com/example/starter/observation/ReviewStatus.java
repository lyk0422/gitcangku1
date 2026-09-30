package com.example.starter.observation;

/**
 * 坐标观测复核状态。
 *
 * <ul>
 *     <li>{@link #AUTO_MERGED}：转换后坐标落在站点矩形边界内（含边界点），允许并已参加自动合并；</li>
 *     <li>{@link #PENDING_REVIEW}：转换后坐标落在边界外，保留观测待人工复核，不参加自动合并。</li>
 * </ul>
 */
public enum ReviewStatus {

    /**
     * 站内容许并已自动合并。
     */
    AUTO_MERGED,

    /**
     * 站外有效观测，待人工复核且不自动合并。
     */
    PENDING_REVIEW
}
