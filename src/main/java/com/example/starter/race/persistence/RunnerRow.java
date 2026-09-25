package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.ResultCalculator;

/**
 * runner 表行记录，并携带该选手当前生效的退赛登记（无则两个退赛字段为 null）。
 *
 * @param id                 自增主键
 * @param raceId             所属赛事ID
 * @param bib                参赛号，赛事内唯一
 * @param finishTimeMs       原始完赛耗时（毫秒，1~86400000）；null 表示计时缺失
 * @param createdAt          登记时间，Unix毫秒时间戳
 * @param updatedAt          最近一次计时修订时间，Unix毫秒时间戳
 * @param withdrawalStatus   生效中的退赛状态（DNS/DNF）；未退赛或已撤销为 null
 * @param lastCheckpointCode DNF 退赛时最后通过检查点代码；其余情况为 null
 */
public record RunnerRow(
        long id,
        String raceId,
        String bib,
        Long finishTimeMs,
        long createdAt,
        long updatedAt,
        EntryStatus withdrawalStatus,
        String lastCheckpointCode
) implements ResultCalculator.RunnerView {

    /** 不含退赛信息的构造器（仅登记/修订等不关心退赛的场景使用）。 */
    public RunnerRow(
            long id,
            String raceId,
            String bib,
            Long finishTimeMs,
            long createdAt,
            long updatedAt) {
        this(id, raceId, bib, finishTimeMs, createdAt, updatedAt, null, null);
    }

    @Override
    public EntryStatus withdrawalStatus() {
        return withdrawalStatus;
    }

    @Override
    public String lastCheckpointCode() {
        return lastCheckpointCode;
    }
}
