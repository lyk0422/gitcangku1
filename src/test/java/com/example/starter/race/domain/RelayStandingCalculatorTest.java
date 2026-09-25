package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RelayStandingCalculator} 纯逻辑单元测试：排名、并列、犯规标注与取消资格。
 */
class RelayStandingCalculatorTest {

    private static RelayStandingCalculator.FinishView finish(String teamKey, long totalMs) {
        return new RelayStandingCalculator.FinishView() {
            @Override
            public String teamKey() {
                return teamKey;
            }

            @Override
            public long totalMs() {
                return totalMs;
            }
        };
    }

    @Test
    void 完赛队伍按总用时升序排名且并列跳号() {
        List<RelayStanding> standings = RelayStandingCalculator.compute(
                List.of("A", "B", "C", "D"),
                List.of(finish("A", 3000), finish("B", 2000), finish("C", 2000), finish("D", 5000)),
                Map.of());

        assertThat(standings).extracting(RelayStanding::teamKey)
                .containsExactly("B", "C", "A", "D");
        assertThat(standings).extracting(RelayStanding::rank)
                .containsExactly(1, 1, 3, 4);
        assertThat(standings).allMatch(s -> s.status() == EntryStatus.RANKED);
    }

    @Test
    void 犯规一次仍排名并标注_犯规两次取消资格不排名() {
        List<RelayStanding> standings = RelayStandingCalculator.compute(
                List.of("A", "B", "C"),
                List.of(finish("A", 1000), finish("B", 2000), finish("C", 500)),
                Map.of("A", 1, "B", 2));

        assertThat(standings).extracting(RelayStanding::teamKey)
                .containsExactly("C", "A", "B");
        RelayStanding fouledOnce = standings.get(1);
        assertThat(fouledOnce.status()).isEqualTo(EntryStatus.RANKED);
        assertThat(fouledOnce.rank()).isEqualTo(2);
        assertThat(fouledOnce.foulCount()).isEqualTo(1);
        assertThat(fouledOnce.hasFouls()).isTrue();

        RelayStanding disqualified = standings.get(2);
        assertThat(disqualified.status()).isEqualTo(EntryStatus.DISQUALIFIED);
        assertThat(disqualified.rank()).isNull();
        assertThat(disqualified.totalMs()).isNull();
        assertThat(disqualified.foulCount()).isEqualTo(2);
    }

    @Test
    void 未完赛队伍不排名且犯规次数仍标注() {
        List<RelayStanding> standings = RelayStandingCalculator.compute(
                List.of("A", "B", "C"),
                List.of(finish("A", 1000)),
                Map.of("B", 1, "C", 2));

        assertThat(standings).extracting(RelayStanding::teamKey)
                .containsExactly("A", "B", "C");
        assertThat(standings.get(1).status()).isEqualTo(EntryStatus.UNTIMED);
        assertThat(standings.get(1).foulCount()).isEqualTo(1);
        assertThat(standings.get(2).status()).isEqualTo(EntryStatus.DISQUALIFIED);
        assertThat(standings.get(2).foulCount()).isEqualTo(2);
    }
}
