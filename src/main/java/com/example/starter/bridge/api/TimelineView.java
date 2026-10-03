package com.example.starter.bridge.api;

import java.time.Instant;
import java.util.List;

import com.example.starter.bridge.BridgeConfig;
import com.example.starter.bridge.BridgePermit;
import com.example.starter.bridge.PermitStatus;

/**
 * 桥梁占用时间轴：每条许可一段，含预计窗口与实际进出桥时刻。
 *
 * @param maxMassKg 桥梁最大总质量，单位千克
 * @param windowMinutes 单次通行预计窗口时长，单位分钟
 * @param entries 时间轴条目，按许可主键排序
 */
public record TimelineView(long maxMassKg, long windowMinutes, List<Entry> entries) {

    /**
     * 时间轴条目。
     *
     * @param permitId 许可主键
     * @param plateNo 车牌代号
     * @param status 许可状态
     * @param windowStart 预计窗口起点（左闭），UTC
     * @param windowEnd 预计窗口终点（右开），UTC
     * @param enteredAt 实际进桥时刻，UTC；未进桥为 null
     * @param exitedAt 实际出桥时刻，UTC；未出桥为 null
     */
    public record Entry(long permitId, String plateNo, PermitStatus status,
            Instant windowStart, Instant windowEnd, Instant enteredAt, Instant exitedAt) {
    }

    /**
     * 从配置与许可列表构建时间轴。
     */
    public static TimelineView of(BridgeConfig config, List<BridgePermit> permits) {
        List<Entry> entries = permits.stream()
                .map(p -> new Entry(p.id(), p.plateNo(), p.status(), p.windowStart(), p.windowEnd(),
                        p.enteredAt(), p.exitedAt()))
                .toList();
        return new TimelineView(config.maxMassKg(), config.windowMinutes(), entries);
    }
}