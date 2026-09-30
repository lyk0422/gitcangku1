package com.example.starter.support;

import java.time.LocalDateTime;

/**
 * 业务时钟，默认取 Asia/Shanghai 当前时刻；测试可替换为可控时钟固定签发/作废/放行时刻。
 */
public interface BusinessClock {

    /**
     * 当前业务时刻（Asia/Shanghai）。
     */
    LocalDateTime now();
}
