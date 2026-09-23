package com.example.starter.consent;

import java.time.Instant;

/**
 * 授权与委托有效期判定使用的时间源，统一返回 UTC 时刻。
 * 生产环境使用系统时钟；测试可替换为可控时钟，以验证到期边界而无需休眠。
 */
public interface TimeSource {

    /**
     * 当前评估时刻（UTC）。
     */
    Instant now();
}
