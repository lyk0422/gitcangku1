package com.example.starter.race.domain;

/**
 * 绕圈赛事每圈的固定点位：按 A、B、FINISH 顺序依次经过。
 */
public enum LapPoint {

    /** 圈内第一点位。 */
    A(1),
    /** 圈内第二点位。 */
    B(2),
    /** 圈内终点，末圈 FINISH 用于排名。 */
    FINISH(3);

    private final int sequence;

    LapPoint(int sequence) {
        this.sequence = sequence;
    }

    /** 圈内顺序，从1递增；跨圈比较时与圈号共同构成全序。 */
    public int sequence() {
        return sequence;
    }

    /**
     * 解析点名（大小写敏感，仅 A/B/FINISH）。
     *
     * @return 非法点名为 null
     */
    public static LapPoint parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LapPoint.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}