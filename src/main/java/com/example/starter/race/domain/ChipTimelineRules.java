package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 计时芯片时间线与读数归属的纯逻辑，不涉及数据库与事务。
 *
 * <p>一名选手的芯片使用历史由“登记发放的初始芯片”与零到多次更换组成，
 * 每次更换在 {@code effectiveAt} 时刻生效。全部时间均为 UTC 毫秒瞬时，
 * 归属区间采用左闭右开：
 * <pre>
 *   [初始绑定时刻, 第1次生效时刻) -> 初始芯片
 *   [第1次生效时刻, 第2次生效时刻) -> 第1枚新芯片
 *   [最后一次生效时刻, +∞) -> 当前芯片
 * </pre>
 * 边界时刻本身归新生效的芯片。读数的“已接收时刻”取实际上报读取时刻
 * {@code readAtMs}，未上报时回退为服务端接收时刻 {@code createdAt}。
 */
public final class ChipTimelineRules {

    /** 开放区间的右端点哨兵：最后一枚芯片生效到无限远。 */
    public static final long OPEN_ENDED = Long.MAX_VALUE;

    private ChipTimelineRules() {
    }

    /** 一次芯片更换的生效边界：自 {@code effectiveAt}（含）起读数归 {@code chipCode}。 */
    public record Boundary(String chipCode, long effectiveAt) {
    }

    /** 一段左闭右开的芯片归属区间 {@code [startAt, endAtExclusive)}。 */
    public record Interval(String chipCode, long startAt, long endAtExclusive) {

        /** instant 是否落在本区间（左端含、右端不含）。 */
        public boolean contains(long instant) {
            return instant >= startAt && instant < endAtExclusive;
        }
    }

    /**
     * 构造芯片归属时间线。
     *
     * @param initialChipCode 选手登记时发放的芯片；无芯片（null）选手不能提交读数
     * @param initialStartAt  初始芯片绑定（选手登记）UTC 时刻
     * @param boundaries      历次更换边界，调用方保证按生效时刻升序且互不相同
     * @return 左闭右开区间列表，末段右端点为 {@link #OPEN_ENDED}
     */
    public static List<Interval> buildTimeline(
            String initialChipCode,
            long initialStartAt,
            List<Boundary> boundaries) {
        List<Boundary> ordered = new ArrayList<>(boundaries);
        ordered.sort(Comparator.comparingLong(Boundary::effectiveAt));
        List<Interval> intervals = new ArrayList<>(ordered.size() + 1);
        long start = initialStartAt;
        String chip = initialChipCode;
        for (Boundary boundary : ordered) {
            intervals.add(new Interval(chip, start, boundary.effectiveAt()));
            start = boundary.effectiveAt();
            chip = boundary.chipCode();
        }
        intervals.add(new Interval(chip, start, OPEN_ENDED));
        return intervals;
    }

    /**
     * 判定某 UTC 时刻读数应归属的芯片。
     *
     * @return 区间对应芯片；时刻早于初始绑定（时间线之外）返回 null（无法唯一归属）
     */
    public static String chipAt(List<Interval> timeline, long instant) {
        for (Interval interval : timeline) {
            if (interval.contains(instant)) {
                return interval.chipCode();
            }
        }
        return null;
    }

    /** 读数的已接收时刻：优先实际读取时刻，未上报时回退为服务端接收时刻。 */
    public static long receivedAt(Long readAtMs, long createdAt) {
        return readAtMs == null ? createdAt : readAtMs;
    }

    /**
     * 校验一条已接收读数能否在给定时间线下唯一归属到其登记芯片。
     *
     * @param timeline         选手芯片时间线
     * @param recordedChipCode 读数登记的实际读取芯片
     * @param readAtMs         实际上报读取时刻（UTC 毫秒）；null 表示未上报
     * @param createdAt        服务端接收时刻（UTC 毫秒）
     * @param checkpointCode   读数对应检查点代码（用于错误信息）
     * @return 无法唯一归属时的错误原因；可唯一归属返回 null
     */
    public static String attributionError(
            List<Interval> timeline,
            String recordedChipCode,
            Long readAtMs,
            long createdAt,
            String checkpointCode) {
        long receivedAt = receivedAt(readAtMs, createdAt);
        String expectedChip = chipAt(timeline, receivedAt);
        if (expectedChip == null) {
            return "读数时刻早于选手芯片绑定起始时刻，无法归属: checkpoint=" + checkpointCode;
        }
        if (recordedChipCode == null) {
            return "读数未记录芯片且选手已启用芯片管理，无法唯一归属: checkpoint=" + checkpointCode;
        }
        if (!expectedChip.equals(recordedChipCode)) {
            return "读数芯片与UTC生效区间不匹配: checkpoint=" + checkpointCode
                    + ", 读数芯片=" + recordedChipCode
                    + ", 该时刻应归属芯片=" + expectedChip;
        }
        return null;
    }
}
