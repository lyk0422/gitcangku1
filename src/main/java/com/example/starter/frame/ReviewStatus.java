package com.example.starter.frame;

/**
 * 坐标观测的复核状态：由转换后目标坐标相对站点矩形（X 0~10、Y 0~30，边界点视为站内）的位置决定。
 */
public enum ReviewStatus {

    /**
     * 目标坐标落在站点矩形内（含边界）：允许并已参加自动合并。
     */
    AUTO_MERGED,

    /**
     * 目标坐标落在站点矩形外：观测有效但保留为待复核，不参加自动合并。
     */
    PENDING_REVIEW
}
