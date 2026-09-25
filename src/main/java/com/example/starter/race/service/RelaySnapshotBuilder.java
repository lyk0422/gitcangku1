package com.example.starter.race.service;

import com.example.starter.race.domain.RelayStandingCalculator;
import com.example.starter.race.domain.RelayTeamStatus;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RelayFinishRow;
import com.example.starter.race.persistence.RelayFoulRow;
import com.example.starter.race.persistence.RelayHandoffRow;
import com.example.starter.race.persistence.RelayMemberRow;
import com.example.starter.race.persistence.RelayRepository;
import com.example.starter.race.persistence.RelaySnapshotTeamRow;
import com.example.starter.race.persistence.RelayTeamRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接力读模型聚合：从队伍/棒次选手/交接/犯规/完赛表装配每队逐棒状态，
 * 并按总用时计算即时排名。即时排名查询与封榜一致快照共用同一装配逻辑。
 */
@Component
public class RelaySnapshotBuilder {

    private final RelayRepository relayRepository;

    public RelaySnapshotBuilder(RelayRepository relayRepository) {
        this.relayRepository = relayRepository;
    }

    /** 装配赛事下全部队伍的聚合状态（已按展示顺序排名）。 */
    public List<TeamState> buildStates(RaceRow race) {
        int legCount = race.legCount() == null ? 0 : race.legCount();

        Map<String, TeamStateBuilder> builders = new LinkedHashMap<>();
        for (RelayTeamRow team : relayRepository.findTeams(race.raceId())) {
            builders.put(team.teamKey(), new TeamStateBuilder(race.raceId(), team.teamKey(), legCount));
        }
        for (RelayMemberRow member : relayRepository.findAllMembers(race.raceId())) {
            TeamStateBuilder builder = builders.get(member.teamKey());
            if (builder != null) {
                builder.bibs[member.legNo() - 1] = member.bib();
            }
        }
        for (RelayHandoffRow handoff : relayRepository.findAllHandoffs(race.raceId())) {
            TeamStateBuilder builder = builders.get(handoff.teamKey());
            if (builder != null) {
                int idx = handoff.legNo() - 1;
                builder.elapsed[idx] = handoff.elapsedMillis();
                builder.handoff[idx] = handoff.handoffMillis();
                builder.completedAt[idx] = handoff.completedAt();
            }
        }
        Map<String, List<RelayFoulRow>> foulsByTeam = new LinkedHashMap<>();
        for (RelayFoulRow foul : relayRepository.findAllFouls(race.raceId())) {
            foulsByTeam.computeIfAbsent(foul.teamKey(), k -> new ArrayList<>()).add(foul);
        }
        for (RelayFinishRow finish : relayRepository.findAllFinishes(race.raceId())) {
            TeamStateBuilder builder = builders.get(finish.teamKey());
            if (builder != null) {
                builder.finish = finish;
            }
        }

        List<RelayStandingCalculator.RelayTeamAggregate> aggregates = new ArrayList<>();
        Map<String, TeamStateBuilder> byKey = builders;
        for (TeamStateBuilder builder : builders.values()) {
            List<RelayFoulRow> fouls = foulsByTeam.getOrDefault(builder.teamKey, List.of());
            builder.fouls = fouls;
            RelayTeamStatus status = resolveStatus(fouls.size(), builder.finish);
            builder.status = status;
            Long total = builder.finish == null ? null : builder.finish.totalElapsedMillis();
            Long finishedAt = builder.finish == null ? null : builder.finish.finishedAt();
            aggregates.add(new RelayStandingCalculator.RelayTeamAggregate(
                    builder.teamKey, status, fouls.size(), total, finishedAt));
        }

        List<RelayStandingCalculator.RelayRankEntry> rankedEntries =
                RelayStandingCalculator.compute(aggregates);

        // 严格按排名计算器给出的展示顺序装配，名次与列表顺序保持一致。
        List<TeamState> states = new ArrayList<>(builders.size());
        for (RelayStandingCalculator.RelayRankEntry rank : rankedEntries) {
            TeamStateBuilder builder = builders.get(rank.teamKey());
            states.add(builder.build(rank.rank(), rank.displayOrder()));
        }
        return states;
    }

    /** 将聚合状态转为封榜快照行。 */
    public List<RelaySnapshotTeamRow> toSnapshotRows(String raceId, List<TeamState> states) {
        List<RelaySnapshotTeamRow> rows = new ArrayList<>(states.size());
        for (TeamState state : states) {
            rows.add(new RelaySnapshotTeamRow(
                    raceId,
                    state.teamKey(),
                    state.rankNo(),
                    state.status(),
                    state.legCount(),
                    state.legBibs(),
                    state.legElapsedMillis(),
                    state.legHandoffMillis(),
                    state.legCompletedAt(),
                    state.foulLegs(),
                    state.totalFouls(),
                    state.totalElapsedMillis(),
                    state.finishedAt(),
                    state.displayOrder()));
        }
        return rows;
    }

    /**
     * 队伍成绩状态：累计2犯立即 DISQUALIFIED；否则末棒完赛为 RANKED；未完赛 RACING。
     */
    public static RelayTeamStatus resolveStatus(int totalFouls, RelayFinishRow finish) {
        if (totalFouls >= 2) {
            return RelayTeamStatus.DISQUALIFIED;
        }
        return finish == null ? RelayTeamStatus.RACING : RelayTeamStatus.RANKED;
    }

    /** 单队聚合读模型。 */
    public record TeamState(
            String teamKey,
            RelayTeamStatus status,
            int legCount,
            List<String> legBibs,
            List<Long> legElapsedMillis,
            List<Long> legHandoffMillis,
            List<Long> legCompletedAt,
            List<RelayFoulRow> fouls,
            List<Integer> foulLegs,
            int totalFouls,
            Long totalElapsedMillis,
            Long finishedAt,
            Integer rankNo,
            int displayOrder) {
    }

    private static final class TeamStateBuilder {
        private final String raceId;
        private final String teamKey;
        private final String[] bibs;
        private final Long[] elapsed;
        private final Long[] handoff;
        private final Long[] completedAt;
        private List<RelayFoulRow> fouls = List.of();
        private RelayFinishRow finish;
        private RelayTeamStatus status = RelayTeamStatus.RACING;

        private TeamStateBuilder(String raceId, String teamKey, int legCount) {
            this.raceId = raceId;
            this.teamKey = teamKey;
            this.bibs = new String[legCount];
            this.elapsed = new Long[legCount];
            this.handoff = new Long[legCount];
            this.completedAt = new Long[legCount];
        }

        private TeamState build(Integer rankNo, int displayOrder) {
            List<Integer> foulLegs = fouls.stream().map(RelayFoulRow::legNo).sorted().toList();
            return new TeamState(
                    teamKey,
                    status,
                    bibs.length,
                    List.of(bibs),
                    listOf(elapsed),
                    listOf(handoff),
                    listOf(completedAt),
                    List.copyOf(fouls),
                    foulLegs,
                    fouls.size(),
                    finish == null ? null : finish.totalElapsedMillis(),
                    finish == null ? null : finish.finishedAt(),
                    rankNo,
                    displayOrder);
        }

        private static List<Long> listOf(Long[] values) {
            List<Long> list = new ArrayList<>(values.length);
            for (Long value : values) {
                list.add(value);
            }
            return list;
        }
    }
}
