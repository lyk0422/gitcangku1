package com.example.starter.pass.clock;

import java.time.Instant;

/**
 * UTC 时间源，业务侧固化时间戳一律走该接口，测试可替换为可控时钟。
 */
public interface TimeSource {

    /** 当前 UTC 时刻。 */
    Instant now();
}
