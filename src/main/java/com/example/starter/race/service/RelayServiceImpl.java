package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureRelayRequest;
import com.example.starter.race.api.FoulResponse;
import com.example.starter.race.api.HandoffResponse;
import com.example.starter.race.api.RegisterRelayTeamRequest;
import com.example.starter.race.api.RelayConfigResponse;
import com.example.starter.race.api.RelayLegDetailResponse;
import com.example.starter.race.api.RelayMemberRequest;
import com.example.starter.race.api.RelayMemberResponse;
import com.example.starter.race.api.RelayRankEntryResponse;
import com.example.starter.race.api.RelayStandingResponse;
import com.example.starter.race.api.RelayTeamDetailResponse;
import com.example.starter.race.api.RelayTeamResponse;
import com.example.starter.race.api.SubmitHandoffRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.RelayTeamStatus;
import com.example.starter.race.persistence.IdempotencyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RaceRepository;
import com.example.starter.race.persistence.RelayFinishRow;
import com.example.starter.race.persistence.RelayFoulRow;
import com.example.starter.race.persistence.RelayHandoffRow;
import com.example.starter.race.persistence.RelayMemberRow;
import com.example.starter.race.persistence.RelayRepository;
import com.example.starter.race.persistence.RelaySnapshotTeamRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * {@link RelayService} 的事务实现。
 *
 * <p>与个人赛一致：所有写操作先以 requestId 占位行保证幂等（同键同参重放、异参409、失败不占键），
 * 再以赛事 version 条件 UPDATE 串行化；交接从第2棒起按棒次顺序提交，交接区超时记犯规但仍推进，
 * 末棒完成在同事务固化完赛记录；累计2犯的队伍状态为 DISQUALIFIED。
 */
@Service
public class RelayServiceImpl implements RelayService {

    /** 同键并发时等待先行者事务结束的上限（毫秒）。 */
    private static final long INFLIGHT_WAIT_MAX_MS = 30_000L;

    private final RaceRepository raceRepository;
    private final RelayRepository relayRepository;
    private final RelaySnapshotBuilder snapshotBuilder;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public RelayServiceImpl(
            RaceRepository raceRepository,
            RelayRepository relayRepository,
            RelaySnapshotBuilder snapshotBuilder,
            Clock clock,
            ObjectMapper objectMapper) {
        this.raceRepository = raceRepository;
        this.relayRepository = relayRepository;
        this.snapshotBuilder = snapshotBuilder;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ServiceResult configureRelay(String raceId, ConfigureRelayRequest request) {
        return withIdempotency(request.requestId(), "CONFIGURE_RELAY",
                orderedParams(
                        "raceId", raceId,
                        "legCount", request.legCount(),
                        "handoffLimitMs", request.handoffLimitMs(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = raceRepository.findRace(raceId)
                            .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
                    if (race.status() == RaceStatus.SEALED) {
                        throw new ConflictException("赛事已封榜，禁止配置: " + raceId);
                    }
                    if (race.relayEnabled()) {
                        throw new ConflictException("赛事已配置接力，配置不可修改: " + raceId);
                    }
                    if (race.version() != request.expectedVersion()) {
                        throw new ConflictException("版本冲突: expected=" + request.expectedVersion()
                                + ", actual=" + race.version());
                    }
                    int updated = relayRepository.configureRelayIfOpenAtVersion(
                            raceId, request.legCount(), request.handoffLimitMs(),
                            request.expectedVersion());
                    if (updated == 0) {
                        throw new ConflictException("版本冲突或赛事已配置接力");
                    }
                    RaceRow refreshed = raceRepository.findRace(raceId).orElseThrow();
                    return ServiceResult.ok(toConfigResponse(refreshed));
                });
    }

    @Override
    @Transactional
    public ServiceResult registerTeam(String raceId, RegisterRelayTeamRequest request) {
        return withIdempotency(request.requestId(), "REGISTER_RELAY_TEAM",
                teamParams(
                        raceId, request.teamKey(), request.expectedVersion(),
                        "members", request.members().stream()
                                .map(m -> {
                                    TreeMap<String, Object> mParams = new TreeMap<>();
                                    mParams.put("legNo", m.legNo());
                                    mParams.put("bib", m.bib());
                                    return mParams;
                                })
                                .toList()),
                () -> {
                    RaceRow race = requireOpenRelayRace(raceId, request.expectedVersion());
                    validateMembers(race.legCount(), request.members());
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    try {
                        relayRepository.insertTeam(raceId, request.teamKey(), now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("接力队伍已存在: " + request.teamKey());
                    }
                    List<RelayMemberRow> rows = new ArrayList<>(request.members().size());
                    for (RelayMemberRequest member : request.members()) {
                        rows.add(new RelayMemberRow(
                                0L, raceId, request.teamKey(), member.legNo(), member.bib(), now));
                    }
                    relayRepository.insertMembers(rows);
                    List<RelayMemberResponse> members = request.members().stream()
                            .sorted(java.util.Comparator.comparingInt(RelayMemberRequest::legNo))
                            .map(m -> new RelayMemberResponse(m.legNo(), m.bib()))
                            .toList();
                    RaceRow refreshed = raceRepository.findRace(raceId).orElseThrow();
                    return ServiceResult.created(new RelayTeamResponse(
                            request.teamKey(), members, refreshed.version()));
                });
    }

    @Override
    @Transactional
    public ServiceResult submitHandoff(String raceId, SubmitHandoffRequest request) {
        return withIdempotency(request.requestId(), "SUBMIT_HANDOFF",
                orderedParams(
                        "raceId", raceId,
                        "teamKey", request.teamKey(),
                        "legNo", request.legNo(),
                        "elapsedMillis", request.elapsedMillis(),
                        "handoffMillis", request.handoffMillis(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRelayRace(raceId, request.expectedVersion());
                    int legCount = race.legCount();
                    int legNo = request.legNo();
                    if (legNo > legCount) {
                        throw new BadRequestException(
                                "交接棒次超过赛事棒次数: " + legNo + " > " + legCount);
                    }
                    relayRepository.findTeam(raceId, request.teamKey())
                            .orElseThrow(() -> new NotFoundException(
                                    "接力队伍不存在: " + request.teamKey()));
                    RelayMemberRow member = relayRepository
                            .findMembers(raceId, request.teamKey()).stream()
                            .filter(m -> m.legNo() == legNo)
                            .findFirst()
                            .orElseThrow(() -> new BadRequestException(
                                    "该队第" + legNo + "棒未登记选手"));
                    if (relayRepository.findHandoff(raceId, request.teamKey(), legNo).isPresent()) {
                        throw new ConflictException(
                                "该队第" + legNo + "棒已完成交接，交接不可重复提交");
                    }
                    List<RelayHandoffRow> priorHandoffs =
                            relayRepository.findHandoffs(raceId, request.teamKey());
                    int nextLeg = priorHandoffs.isEmpty() ? 2 : priorHandoffs.getLast().legNo() + 1;
                    if (legNo < nextLeg) {
                        throw new ConflictException(
                                "该队第" + legNo + "棒已完成交接，交接不可重复提交");
                    }
                    if (legNo > nextLeg) {
                        throw new UnprocessableEntityException(
                                "交接必须按棒次顺序提交，下一应提交棒次为 " + nextLeg);
                    }
                    RelayHandoffRow previous = legNo == 2 ? null
                            : priorHandoffs.stream()
                            .filter(h -> h.legNo() == legNo - 1)
                            .findFirst()
                            .orElseThrow(() -> new UnprocessableEntityException(
                                    "缺少上一棒次(" + (legNo - 1) + ")的交接记录"));
                    if (previous != null) {
                        if (request.elapsedMillis() <= previous.elapsedMillis()) {
                            throw new UnprocessableEntityException(
                                    "接棒选手累计耗时必须大于上一棒次已记录耗时 "
                                            + previous.elapsedMillis());
                        }
                    }
                    long now = clock.millis();
                    if (previous != null && now <= previous.completedAt()) {
                        throw new UnprocessableEntityException(
                                "交接完成时刻必须晚于上一棒次交接完成时刻 "
                                        + previous.completedAt());
                    }

                    boolean foul = request.handoffMillis() > race.handoffLimitMs();
                    bumpVersion(race, request.expectedVersion());
                    relayRepository.insertHandoff(new RelayHandoffRow(
                            0L, raceId, request.teamKey(), legNo, member.bib(),
                            request.elapsedMillis(), request.handoffMillis(), foul, now, now));
                    if (foul) {
                        // 唯一约束 (race, team, leg) 保证一队同一交接只记一次；犯规不可逆。
                        relayRepository.insertFoul(new RelayFoulRow(
                                0L, raceId, request.teamKey(), legNo,
                                request.handoffMillis(), race.handoffLimitMs(), now));
                    }
                    List<RelayFoulRow> teamFouls =
                            relayRepository.findFouls(raceId, request.teamKey());
                    boolean finished = legNo == legCount;
                    RelayFinishRow finish = null;
                    if (finished) {
                        List<Long> legElapsed = buildLegElapsed(raceId, request.teamKey(), legCount);
                        List<Integer> foulLegs = teamFouls.stream()
                                .map(RelayFoulRow::legNo).sorted().toList();
                        RelayTeamStatus status = teamFouls.size() >= 2
                                ? RelayTeamStatus.DISQUALIFIED
                                : RelayTeamStatus.RANKED;
                        finish = new RelayFinishRow(
                                raceId, request.teamKey(), legCount, legElapsed, foulLegs,
                                teamFouls.size(), request.elapsedMillis(), status, now, now);
                        relayRepository.insertFinish(finish);
                    }
                    RaceRow refreshed = raceRepository.findRace(raceId).orElseThrow();
                    RelayTeamStatus teamStatus = RelaySnapshotBuilder
                            .resolveStatus(teamFouls.size(), finished ? finish : null);
                    return ServiceResult.ok(new HandoffResponse(
                            request.teamKey(), legNo, member.bib(),
                            request.elapsedMillis(), request.handoffMillis(), foul, now,
                            finished, teamFouls.size(),
                            finished ? finish.totalElapsedMillis() : null,
                            teamStatus.name(), refreshed.version()));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public RelayTeamDetailResponse getTeamDetail(String raceId, String teamKey) {
        RaceRow race = requireRelayRace(raceId);
        if (relayRepository.findTeam(raceId, teamKey).isEmpty()) {
            throw new NotFoundException("接力队伍不存在: " + teamKey);
        }
        List<FoulResponse> fouls = relayRepository.findFouls(raceId, teamKey).stream()
                .map(RelayServiceImpl::toFoulResponse)
                .toList();

        if (race.status() == RaceStatus.SEALED) {
            RelaySnapshotTeamRow row = relayRepository.findSnapshotTeams(raceId).stream()
                    .filter(t -> t.teamKey().equals(teamKey))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "封榜快照缺少接力队伍: " + teamKey));
            return new RelayTeamDetailResponse(
                    teamKey, row.status().name(),
                    legsFromSnapshot(row), fouls, row.totalFouls(),
                    row.totalElapsedMillis(), row.finishedAt());
        }

        RelaySnapshotBuilder.TeamState state = requireState(race, teamKey);
        return new RelayTeamDetailResponse(
                teamKey, state.status().name(),
                legsFromState(state), fouls, state.totalFouls(),
                state.totalElapsedMillis(), state.finishedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public List<FoulResponse> getFouls(String raceId) {
        requireRelayRace(raceId);
        return relayRepository.findAllFouls(raceId).stream()
                .map(RelayServiceImpl::toFoulResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RelayStandingResponse getStanding(String raceId) {
        RaceRow race = requireRelayRace(raceId);
        if (race.status() == RaceStatus.SEALED) {
            List<RelayRankEntryResponse> teams = relayRepository.findSnapshotTeams(raceId).stream()
                    .map(r -> new RelayRankEntryResponse(
                            r.teamKey(), r.rankNo(), r.status().name(),
                            r.totalFouls() > 0, r.totalFouls(),
                            r.totalElapsedMillis(), r.finishedAt()))
                    .toList();
            return new RelayStandingResponse(
                    raceId, race.version(), RaceStatus.SEALED.name(), race.legCount(),
                    sealedAt(raceId), teams);
        }
        List<RelayRankEntryResponse> teams = snapshotBuilder.buildStates(race).stream()
                .map(s -> new RelayRankEntryResponse(
                        s.teamKey(), s.rankNo(), s.status().name(),
                        s.totalFouls() > 0, s.totalFouls(),
                        s.totalElapsedMillis(), s.finishedAt()))
                .toList();
        return new RelayStandingResponse(
                raceId, race.version(), RaceStatus.OPEN.name(), race.legCount(), null, teams);
    }

    @Override
    @Transactional(readOnly = true)
    public RelayConfigResponse toConfigResponse(String raceId) {
        return toConfigResponse(raceRepository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId)));
    }

    /** 封榜后读取封榜时刻（与个人赛共用 result_snapshot 头表）。 */
    private Long sealedAt(String raceId) {
        return raceRepository.findSnapshot(raceId)
                .map(s -> s.sealedAt())
                .orElse(null);
    }

    private RelaySnapshotBuilder.TeamState requireState(RaceRow race, String teamKey) {
        return snapshotBuilder.buildStates(race).stream()
                .filter(s -> s.teamKey().equals(teamKey))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("接力队伍不存在: " + teamKey));
    }

    private List<RelayLegDetailResponse> legsFromState(RelaySnapshotBuilder.TeamState state) {
        List<RelayLegDetailResponse> legs = new ArrayList<>(state.legCount());
        Set<Integer> foulLegs = new HashSet<>(state.foulLegs());
        for (int i = 0; i < state.legCount(); i++) {
            legs.add(new RelayLegDetailResponse(
                    i + 1,
                    state.legBibs().get(i),
                    state.legElapsedMillis().get(i),
                    state.legHandoffMillis().get(i),
                    state.legCompletedAt().get(i),
                    foulLegs.contains(i + 1)));
        }
        return legs;
    }

    private List<RelayLegDetailResponse> legsFromSnapshot(RelaySnapshotTeamRow row) {
        List<RelayLegDetailResponse> legs = new ArrayList<>(row.legCount());
        Set<Integer> foulLegs = new HashSet<>(row.foulLegs());
        for (int i = 0; i < row.legCount(); i++) {
            legs.add(new RelayLegDetailResponse(
                    i + 1,
                    row.legBibs().get(i),
                    row.legElapsedMillis().get(i),
                    row.legHandoffMillis().get(i),
                    row.legCompletedAt().get(i),
                    foulLegs.contains(i + 1)));
        }
        return legs;
    }

    private List<Long> buildLegElapsed(String raceId, String teamKey, int legCount) {
        Long[] elapsed = new Long[legCount];
        // 第1棒无交接提交渠道，其累计耗时不记录；第2..N棒取对应交接的累计耗时。
        for (RelayHandoffRow handoff : relayRepository.findHandoffs(raceId, teamKey)) {
            if (handoff.legNo() >= 1 && handoff.legNo() <= legCount) {
                elapsed[handoff.legNo() - 1] = handoff.elapsedMillis();
            }
        }
        List<Long> list = new ArrayList<>(legCount);
        for (Long value : elapsed) {
            list.add(value);
        }
        return list;
    }

    private static void validateMembers(int legCount, List<RelayMemberRequest> members) {
        Set<Integer> legs = new HashSet<>();
        for (RelayMemberRequest member : members) {
            if (member.legNo() < 1 || member.legNo() > legCount) {
                throw new BadRequestException(
                        "棒次超出范围: " + member.legNo() + "，赛事共 " + legCount + " 棒");
            }
            if (!legs.add(member.legNo())) {
                throw new BadRequestException("同一棒次只能登记一名选手: " + member.legNo());
            }
        }
        if (legs.size() != legCount) {
            throw new BadRequestException(
                    "必须为第1~" + legCount + "棒各登记一名选手，实际 " + legs.size() + " 棒");
        }
    }

    private RaceRow requireRelayRace(String raceId) {
        RaceRow race = raceRepository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        if (!race.relayEnabled()) {
            throw new NotFoundException("赛事不是接力赛: " + raceId);
        }
        return race;
    }

    private RaceRow requireOpenRelayRace(String raceId, int expectedVersion) {
        RaceRow race = requireRelayRace(raceId);
        if (race.status() == RaceStatus.SEALED) {
            throw new ConflictException("赛事已封榜，禁止交接写入: " + raceId);
        }
        if (race.version() != expectedVersion) {
            throw new ConflictException("版本冲突: expected=" + expectedVersion
                    + ", actual=" + race.version());
        }
        return race;
    }

    private void bumpVersion(RaceRow race, int expectedVersion) {
        int updated = raceRepository.bumpVersionIfOpen(race.raceId(), expectedVersion);
        if (updated == 0) {
            RaceRow refreshed = raceRepository.findRace(race.raceId()).orElseThrow();
            if (refreshed.status() == RaceStatus.SEALED) {
                throw new ConflictException("赛事已封榜，禁止写入: " + race.raceId());
            }
            throw new ConflictException("版本冲突: expected=" + expectedVersion
                    + ", actual=" + refreshed.version());
        }
    }

    private RelayConfigResponse toConfigResponse(RaceRow race) {
        return new RelayConfigResponse(
                race.raceId(), race.version(), race.status().name(),
                race.legCount(), race.handoffLimitMs(), race.createdAt());
    }

    private static FoulResponse toFoulResponse(RelayFoulRow foul) {
        return new FoulResponse(foul.teamKey(), foul.legNo(), foul.handoffMillis(),
                foul.limitMillis(), foul.createdAt());
    }

    /**
     * 幂等包装：同键同参重放原成功结果，同键异参409；
     * 业务异常随事务回滚，占位行消失，不占用 requestId。
     */
    private ServiceResult withIdempotency(
            String requestId,
            String operation,
            TreeMap<String, Object> params,
            Supplier<ServiceResult> action) {
        String digest = digest(operation, params);
        long deadline = clock.millis() + INFLIGHT_WAIT_MAX_MS;
        boolean acquired = false;
        while (!acquired) {
            try {
                raceRepository.insertIdempotencyPlaceholder(
                        requestId, operation, digest, clock.millis());
                acquired = true;
            } catch (DuplicateKeyException ex) {
                Optional<IdempotencyRow> existing =
                        raceRepository.findIdempotencyForUpdate(requestId);
                if (existing.isEmpty()) {
                    continue;
                }
                IdempotencyRow row = existing.get();
                if (row.responseStatus() == 0) {
                    if (clock.millis() >= deadline) {
                        throw new ConflictException("相同 requestId 的请求仍在处理中");
                    }
                    sleepBriefly();
                    continue;
                }
                if (!row.requestDigest().equals(digest)) {
                    throw new ConflictException(
                            "requestId 已用于不同参数的请求: " + requestId);
                }
                return new ServiceResult(parseReplayedBody(row.responseBody()), row.responseStatus());
            }
        }
        ServiceResult result = action.get();
        raceRepository.completeIdempotency(
                requestId, result.status(), writeJson(result.body()));
        return result;
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

    private static TreeMap<String, Object> teamParams(
            String raceId, String teamKey, int expectedVersion, Object membersKey, Object membersValue) {
        TreeMap<String, Object> params = orderedParams(
                "raceId", raceId,
                "teamKey", teamKey,
                "expectedVersion", expectedVersion);
        params.put((String) membersKey, membersValue);
        return params;
    }

    private String digest(String operation, Map<String, Object> params) {
        try {
            Map<String, Object> canonical = new TreeMap<>(params);
            canonical.put("__operation", operation);
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(canonical)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(messageDigest.digest(bytes));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("计算请求摘要失败", ex);
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("序列化响应失败", ex);
        }
    }

    private JsonNode parseReplayedBody(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("解析原响应失败", ex);
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(10L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ConflictException("等待同键请求完成时被中断");
        }
    }
}
