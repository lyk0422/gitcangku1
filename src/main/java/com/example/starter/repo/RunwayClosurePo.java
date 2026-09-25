package com.example.starter.repo;

/**
 * 跑道关闭窗口记录（UTC 左闭右开 [startUtc, endUtc)）。
 *
 * @param closureId      关闭窗口唯一标识
 * @param runwayId       所属跑道标识
 * @param startUtc       关闭开始时刻，epoch 毫秒（UTC），左闭
 * @param endUtc         关闭结束时刻，epoch 毫秒（UTC），右开
 * @param allowEmergency 是否允许紧急例外
 * @param operator       登记操作者标识
 * @param closureKey     幂等键（指纹含跑道版本、规范化时段、例外标志与操作者）
 * @param runwayVersion  本次登记生效后的跑道版本
 * @param createdAt      登记时间，epoch 毫秒（UTC）
 */
public record RunwayClosurePo(String closureId, String runwayId, long startUtc, long endUtc,
                              boolean allowEmergency, String operator, String closureKey,
                              int runwayVersion, long createdAt) {

    /** 判断时刻 t 是否落在窗口内（左闭右开）。 */
    public boolean contains(long t) {
        return startUtc <= t && t < endUtc;
    }
}
