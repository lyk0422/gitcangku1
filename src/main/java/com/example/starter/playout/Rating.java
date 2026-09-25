package com.example.starter.playout;

/**
 * 内容分级：G 低于 PG 低于 MATURE。素材登记时可不声明分级，
 * 未声明的历史素材在分级校验中按最高分级 MATURE 处理。
 */
public enum Rating {
    G(0),
    PG(1),
    MATURE(2);

    private final int level;

    Rating(int level) {
        this.level = level;
    }

    /** 分级高低，数值越大限制越严格。 */
    public int level() {
        return level;
    }

    /** 是否超过给定最高允许分级。 */
    public boolean exceeds(Rating maxAllowed) {
        return this.level > maxAllowed.level;
    }

    /** 有效分级：未声明（null）按 MATURE 处理，避免绕过管控。 */
    public static Rating effective(Rating declared) {
        return declared == null ? MATURE : declared;
    }
}
