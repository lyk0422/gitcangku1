package com.example.starter.race.service;

import com.example.starter.race.api.RelayConfigRequest;
import com.example.starter.race.api.RelayConfigResponse;
import com.example.starter.race.api.RelayFoulListResponse;
import com.example.starter.race.api.RelayHandoffRequest;
import com.example.starter.race.api.RelayHandoffResponse;
import com.example.starter.race.api.RelayStandingResponse;
import com.example.starter.race.api.RelayTeamDetailResponse;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.RelayStanding;
import com.example.starter.race.domain.RelayStandingCalculator;
import com.example.starter.race.persistence.RaceRepository;
import com.example.starter.race.persistence.RelayConfigRow;
import com.example.starter.race.persistence.RelayFinishRow;
import com.example.starter.race.persistence.RelayFoulRow;
import com.example.starter.race.persistence.RelayHandoffRow;
import com.example.starter.race.persistence.RelaySnapshotLegRow;
import com.example.starter.race.persistence.RelaySnapshotRow;
import com.example.starter.race.persistence.RelaySnapshotTeamRow;
import com.example.starter.race.persistence.RelayTeamLegRow;
import com.example.starter.race.persistence.RaceRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@link RelayService} 的事务实现。
 *
 * <p>并发与一致性要点与个人计时一致：
 * <ul>
 *   <li>交接提交先执行“状态=OPEN 且 version=expectedVersion”的条件 UPDATE，
 *       行锁串行化同赛事并发写，版本不匹配/已封榜均为0行并返回409；</li>
 *   <li>每队每交接棒次由 relay_handoff 主键保证仅一条，同队同一交接至多一条犯规记录；</li>
 *   <li>犯规判定与交接写入同事务提交，犯规记录不可逆；</li>
 *   <li>requestId 幂等由 {@link IdempotencyExecutor} 保证：同键同参重放、异参409、失败不占键。</li>
 * </ul>
 */
@Service
public class RelayServiceImpl implements RelayService {

    private final RaceRepository repository;
    private final Clock clock;
    private final IdempotencyExecutor idempotencyExecutor;

    public RelayServiceImpl(
            RaceRepository repository, Clock clock, IdempotencyExecutor idempotencyExecutor) {
        this.repository = repository;
        this.clock = clock;
        this.idempotencyExecutor = idempotencyExecutor;
    }

    @Override
    @Transactional
    public ServiceResult configureRelay(String raceId, RelayConfigRequest request) {
        return idempotencyExecutor.execute(request.requestId(), "CONFIGURE_RELAY",
                orderedParams(
                        "raceId", raceId,
                        "legCount", request.legCount(),
                        "exchangeLimitMs", request.exchangeLimitMs(),
                        "teams", request.teams(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    if (repository.findRelayConfig(raceId).isPresent()) {
                        throw new ConflictException("接力配置已存在，不可修改: " + raceId);
                    }
                    validateTeams(request);
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    repository.insertRelayConfig(
                            raceId, request.legCount(), request.exchangeLimitMs(), now);
                    List<String> teamKeys = new ArrayList<>(request.teams().size());
                    for (RelayConfigRequest.TeamRunners team : request.teams()) {
                        repository.insertRelayTeamLegs(raceId, team.teamKey(), team.runners());
                        teamKeys.add(team.teamKey());
                    }
                    return ServiceResult.created(new RelayConfigResponse(
                            raceId,
                            request.expectedVersion() + 1,
                            request.legCount(),
                            request.exchangeLimitMs(),
                            teamKeys));
                });
    }

    @Override
    @Transactional
    public ServiceResult submitHandoff(String raceId, RelayHandoffRequest request) {
        return idempotencyExecutor.execute(request.requestId(), "SUBMIT_HANDOFF",
                orderedParams(
                        "raceId", raceId,
                        "teamKey", request.teamKey(),
                        "leg", request.leg(),
                        "receiver", request.receiver(),
                        "elapsedMillis", request.elapsedMillis(),
                        "zoneMillis", request.zoneMillis(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    RelayConfigRow config = repository.findRelayConfig(raceId)
                            .orElseThrow(() -> new ConflictException("非接力赛事，不接受交接提交: " + raceId));
                    int leg = request.leg();
                    if (leg > config.legCount()) {
                        throw new UnprocessableEntityException(
                                "交接棒次超出配置棒次数: leg=" + leg + ", legCount=" + config.legCount());
                    }
                    RelayTeamLegRow registered = repository
                            .findRelayTeamLeg(raceId, request.teamKey(), leg)
                            .orElseThrow(() -> new NotFoundException(
                                    "队伍未登记或棒次不存在: " + request.teamKey()));
                    if (!registered.runner().equals(request.receiver())) {
                        throw new UnprocessableEntityException(
                                "接棒选手与登记不一致: 登记=" + registered.runner()
                                        + ", 提交=" + request.receiver());
                    }
                    if (repository.findRelayHandoff(raceId, request.teamKey(), leg).isPresent()) {
                        throw new ConflictException(
                                "该队伍此交接已提交: team=" + request.teamKey() + ", leg=" + leg);
                    }
                    long now = clock.millis();
                    if (leg > 2) {
                        RelayHandoffRow previous = repository
                                .findRelayHandoff(raceId, request.teamKey(), leg - 1)
                                .orElseThrow(() -> new UnprocessableEntityException(
                                        "上一棒次尚未交接，不能提交本棒次: leg=" + (leg - 1)));
                        validateTimingOrder(request, previous, now);
                    }
                    boolean foul = request.zoneMillis() > config.exchangeLimitMs();
                    bumpVersion(race, request.expectedVersion());
                    try {
                        repository.insertRelayHandoff(new RelayHandoffRow(
                                raceId, request.teamKey(), leg, request.receiver(),
                                request.elapsedMillis(), request.zoneMillis(), foul, now));
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException(
                                "该队伍此交接已提交: team=" + request.teamKey() + ", leg=" + leg);
                    }
                    if (foul) {
                        repository.insertRelayFoul(new RelayFoulRow(
                                raceId, request.teamKey(), leg,
                                request.zoneMillis(), config.exchangeLimitMs(), now));
                    }
                    int teamFoulCount = repository.countRelayFouls(raceId, request.teamKey());
                    boolean finished = leg == config.legCount();
                    Long totalMillis = null;
                    EntryStatus teamStatus = null;
                    if (finished) {
                        totalMillis = request.elapsedMillis();
                        teamStatus = teamFoulCount >= RelayStandingCalculator.DISQUALIFY_FOUL_COUNT
                                ? EntryStatus.DISQUALIFIED
                                : EntryStatus.RANKED;
                        repository.insertRelayFinish(new RelayFinishRow(
                                raceId, request.teamKey(), totalMillis, teamFoulCount,
                                teamStatus, now));
                    }
                    return ServiceResult.created(new RelayHandoffResponse(
                            raceId,
                            request.expectedVersion() + 1,
                            request.teamKey(),
                            leg,
                            request.receiver(),
                            request.elapsedMillis(),
                            request.zoneMillis(),
                            foul,
                            teamFoulCount,
                            finished,
                            totalMillis,
                            teamStatus));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public RelayStandingResponse getRelayStanding(String raceId) {
        RaceRow race = requireRelayRace(raceId);
        if (race.status() == RaceStatus.SEALED) {
            RelaySnapshotRow snapshot = repository.findRelaySnapshot(raceId)
                    .orElseThrow(() -> new IllegalStateException(
                            "赛事已封榜但缺少接力快照: " + raceId));
            return ResponseMapper.relaySnapshotStanding(snapshot);
        }
        return ResponseMapper.relayLiveStanding(race, computeStandings(raceId));
    }

    @Override
    @Transactional(readOnly = true)
    public RelayStandingResponse getRelaySnapshot(String raceId) {
        requireRelayRace(raceId);
        RelaySnapshotRow snapshot = repository.findRelaySnapshot(raceId)
                .orElseThrow(() -> new NotFoundException("赛事尚未封榜: " + raceId));
        return ResponseMapper.relaySnapshotStanding(snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public RelayTeamDetailResponse getTeamDetail(String raceId, String teamKey) {
        RelayConfigRow config = requireRelayRaceConfig(raceId);
        Map<Integer, RelayTeamLegRow> roster = rosterByLeg(raceId, teamKey);
        if (roster.isEmpty()) {
            throw new NotFoundException("队伍未登记: " + teamKey);
        }
        Map<Integer, RelayHandoffRow> handoffs = handoffsByLeg(raceId, teamKey);
        RelayFinishRow finish = repository.findRelayFinish(raceId, teamKey).orElse(null);
        int foulCount = repository.countRelayFouls(raceId, teamKey);
        return new RelayTeamDetailResponse(
                raceId,
                teamKey,
                finish == null ? EntryStatus.UNTIMED : finish.status(),
                finish == null ? null : finish.totalMs(),
                foulCount,
                buildLegDetails(config, roster, handoffs, finish));
    }

    @Override
    @Transactional(readOnly = true)
    public RelayFoulListResponse getFouls(String raceId) {
        requireRelayRace(raceId);
        List<RelayFoulListResponse.FoulEntry> fouls = repository.findRelayFouls(raceId).stream()
                .map(row -> new RelayFoulListResponse.FoulEntry(
                        row.teamKey(), row.legNo(), row.zoneMs(), row.limitMs(), row.createdAt()))
                .toList();
        return new RelayFoulListResponse(raceId, fouls);
    }

    /**
     * 计算即时接力排名：全部登记队伍 + 已生成完赛记录。
     * 封榜时在同一事务内复用，保证快照与排名口径一致。
     */
    List<RelayStanding> computeStandings(String raceId) {
        List<String> teamKeys = repository.findRelayTeamLegs(raceId).stream()
                .map(RelayTeamLegRow::teamKey)
                .distinct()
                .toList();
        List<RelayStandingCalculator.FinishView> finishes =
                new ArrayList<>(repository.findRelayFinishes(raceId));
        Map<String, Integer> foulCounts = new LinkedHashMap<>();
        for (RelayFoulRow foul : repository.findRelayFouls(raceId)) {
            foulCounts.merge(foul.teamKey(), 1, Integer::sum);
        }
        return RelayStandingCalculator.compute(teamKeys, finishes, foulCounts);
    }

    /**
     * 构建队伍逐棒明细：棒次 k 的累计用时取第 k+1 棒交接记录（末棒取完赛总用时），
     * 分段用时为相邻棒次累计用时之差；交接区用时与犯规归属被接起的棒次（k>=2）。
     */
    private List<RelayTeamDetailResponse.LegDetail> buildLegDetails(
            RelayConfigRow config,
            Map<Integer, RelayTeamLegRow> roster,
            Map<Integer, RelayHandoffRow> handoffs,
            RelayFinishRow finish) {
        List<RelayTeamDetailResponse.LegDetail> legs = new ArrayList<>(config.legCount());
        Long previousElapsed = null;
        for (int leg = 1; leg <= config.legCount(); leg++) {
            RelayTeamLegRow registered = roster.get(leg);
            String runner = registered == null ? null : registered.runner();
            Long elapsed;
            if (leg < config.legCount()) {
                RelayHandoffRow next = handoffs.get(leg + 1);
                elapsed = next == null ? null : next.elapsedMs();
            } else {
                elapsed = finish == null ? null : finish.totalMs();
            }
            Long split = null;
            if (elapsed != null && previousElapsed != null) {
                split = elapsed - previousElapsed;
            } else if (elapsed != null && leg == 1) {
                split = elapsed;
            }
            RelayHandoffRow incoming = handoffs.get(leg);
            legs.add(new RelayTeamDetailResponse.LegDetail(
                    leg,
                    runner,
                    elapsed,
                    split,
                    leg >= 2 && incoming != null ? incoming.zoneMs() : null,
                    leg >= 2 && incoming != null && incoming.foul()));
            previousElapsed = elapsed;
        }
        return legs;
    }

    private Map<Integer, RelayTeamLegRow> rosterByLeg(String raceId, String teamKey) {
        Map<Integer, RelayTeamLegRow> roster = new LinkedHashMap<>();
        for (RelayTeamLegRow row : repository.findRelayTeamLegs(raceId)) {
            if (row.teamKey().equals(teamKey)) {
                roster.put(row.legNo(), row);
            }
        }
        return roster;
    }

    private Map<Integer, RelayHandoffRow> handoffsByLeg(String raceId, String teamKey) {
        Map<Integer, RelayHandoffRow> handoffs = new LinkedHashMap<>();
        for (RelayHandoffRow row : repository.findRelayHandoffs(raceId, teamKey)) {
            handoffs.put(row.legNo(), row);
        }
        return handoffs;
    }

    private static void validateTeams(RelayConfigRequest request) {
        Set<String> seen = new HashSet<>();
        for (RelayConfigRequest.TeamRunners team : request.teams()) {
            if (!seen.add(team.teamKey())) {
                throw new BadRequestException("队伍标识重复: " + team.teamKey());
            }
            if (team.runners().size() != request.legCount()) {
                throw new BadRequestException("队伍 " + team.teamKey() + " 的选手数量须等于棒次数 "
                        + request.legCount());
            }
        }
    }

    /**
     * 交接时序校验：接棒选手累计用时必须大于上一棒次记录值，
     * 且本次交接完成的服务端时刻必须晚于上一棒次交接完成时刻，否则422。
     */
    private static void validateTimingOrder(
            RelayHandoffRequest request, RelayHandoffRow previous, long now) {
        if (request.elapsedMillis() <= previous.elapsedMs()) {
            throw new UnprocessableEntityException(
                    "接棒选手 elapsedMillis 必须大于上一棒次记录值: 上一棒=" + previous.elapsedMs()
                            + ", 本次=" + request.elapsedMillis());
        }
        if (now <= previous.serverCompletedAt()) {
            throw new UnprocessableEntityException(
                    "本次交接服务端时刻必须晚于上一棒次交接完成时刻");
        }
    }

    private RaceRow requireRelayRace(String raceId) {
        RaceRow race = repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        if (repository.findRelayConfig(raceId).isEmpty()) {
            throw new ConflictException("非接力赛事: " + raceId);
        }
        return race;
    }

    private RelayConfigRow requireRelayRaceConfig(String raceId) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        return repository.findRelayConfig(raceId)
                .orElseThrow(() -> new ConflictException("非接力赛事: " + raceId));
    }

    private RaceRow requireOpenRace(String raceId, Integer expectedVersion) {
        RaceRow race = repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        if (race.status() == RaceStatus.SEALED) {
            throw new ConflictException("赛事已封榜，禁止写入: " + raceId);
        }
        if (race.version() != expectedVersion) {
            throw new ConflictException("版本冲突: expected=" + expectedVersion
                    + ", actual=" + race.version());
        }
        return race;
    }

    private void bumpVersion(RaceRow race, int expectedVersion) {
        int updated = repository.bumpVersionIfOpen(race.raceId(), expectedVersion);
        if (updated == 0) {
            // 行锁释放后发现赛事已封榜或版本已被推进。
            RaceRow refreshed = repository.findRace(race.raceId()).orElseThrow();
            if (refreshed.status() == RaceStatus.SEALED) {
                throw new ConflictException("赛事已封榜，禁止写入: " + race.raceId());
            }
            throw new ConflictException("版本冲突: expected=" + expectedVersion
                    + ", actual=" + refreshed.version());
        }
    }

    private static TreeMap<String, Object> orderedParams(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("参数必须为键值对");
        }
        TreeMap<String, Object> params = new TreeMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    /**
     * 在封榜事务内（条件 UPDATE 已持有赛事行锁）构建接力快照：
     * 固化各队逐棒明细、犯规标记与最终名次。
     */
    RelaySnapshotRow buildRelaySnapshot(String raceId, int version, long sealedAt) {
        RelayConfigRow config = repository.findRelayConfig(raceId).orElseThrow();
        List<RelayStanding> standings = computeStandings(raceId);
        List<RelaySnapshotTeamRow> teams = new ArrayList<>(standings.size());
        List<RelaySnapshotLegRow> legs = new ArrayList<>();
        int order = 0;
        for (RelayStanding standing : standings) {
            teams.add(new RelaySnapshotTeamRow(
                    raceId,
                    standing.teamKey(),
                    standing.rank(),
                    standing.status(),
                    standing.totalMs(),
                    standing.foulCount(),
                    order++));
            Map<Integer, RelayTeamLegRow> roster = rosterByLeg(raceId, standing.teamKey());
            Map<Integer, RelayHandoffRow> handoffs = handoffsByLeg(raceId, standing.teamKey());
            RelayFinishRow finish = repository.findRelayFinish(raceId, standing.teamKey())
                    .orElse(null);
            for (RelayTeamDetailResponse.LegDetail detail
                    : buildLegDetails(config, roster, handoffs, finish)) {
                legs.add(new RelaySnapshotLegRow(
                        raceId,
                        standing.teamKey(),
                        detail.leg(),
                        detail.runner(),
                        detail.elapsedMillis(),
                        detail.splitMillis(),
                        detail.zoneMillis(),
                        detail.foul()));
            }
        }
        return new RelaySnapshotRow(raceId, version, sealedAt, teams, legs);
    }
}
