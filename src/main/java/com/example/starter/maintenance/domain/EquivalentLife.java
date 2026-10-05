package com.example.starter.maintenance.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 负荷加权等效寿命计算：按采样时刻升序，相邻累计读数增量乘以本次读数声明的
 * 负荷权重（NORMAL=1、HEAVY=2）累计，首条以 0 为基准；全部为整数等效分钟。
 * 等效累计完全由当前读数序列推导，读数更正后重算时它与后一条间隔的贡献同步变化。
 */
public final class EquivalentLife {

    private EquivalentLife() {
    }

    /** 计算每条读数对应的等效累计（前缀和），返回列表与入参读数序列一一对应。 */
    public static List<Long> prefixTotals(List<Reading> readingsAsc) {
        List<Long> totals = new ArrayList<>(readingsAsc.size());
        long total = 0;
        long previousCumulative = 0;
        for (Reading reading : readingsAsc) {
            total += (reading.cumulativeMinutes() - previousCumulative)
                    * LoadWeight.factorOf(reading.loadWeight());
            totals.add(total);
            previousCumulative = reading.cumulativeMinutes();
        }
        return totals;
    }

    /** 最新一条读数的等效累计；无读数时为 0。 */
    public static long totalOf(List<Reading> readingsAsc) {
        long total = 0;
        long previousCumulative = 0;
        for (Reading reading : readingsAsc) {
            total += (reading.cumulativeMinutes() - previousCumulative)
                    * LoadWeight.factorOf(reading.loadWeight());
            previousCumulative = reading.cumulativeMinutes();
        }
        return total;
    }
}
