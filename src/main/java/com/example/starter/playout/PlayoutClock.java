package com.example.starter.playout;

/**
 * 业务时钟：返回当前 UTC 纪元毫秒。直播覆盖的开始/结束判定经由此时钟，
 * 测试可替换为可控实现，避免依赖真实等待。
 */
public interface PlayoutClock {

    /** 当前时间，UTC 纪元毫秒。 */
    long nowMs();
}
