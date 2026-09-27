package com.example.starter.observation;

import java.math.BigDecimal;

/**
 * 十进制数值规范化：数据库定点数读出后去除尾零，保证响应与指纹中的数值表示稳定
 * （12.3400000 与 12.34 输出一致；零值统一为 0，避免 0E-7 形式）。
 */
final class Decimals {

    private Decimals() {
    }

    /**
     * 去除尾零并保证至少一位小数（31.0000000 → 31.0，0 → 0.0），
     * 避免科学计数法形式（如 3.1E+1）出现在响应中。null 输入原样返回（可空字段）。
     */
    static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 1 ? stripped.setScale(1) : stripped;
    }
}
