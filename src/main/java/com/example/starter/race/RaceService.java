package com.example.starter.race;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.race.dto.AddPenaltyRequest;
import com.example.starter.race.dto.CreateRaceRequest;
import com.example.starter.race.dto.ParticipantResponse;
import com.example.starter.race.dto.PenaltyResponse;
import com.example.starter.race.dto.RaceResponse;
import com.example.starter.race.dto.RegisterParticipantRequest;
import com.example.starter.race.dto.ReviseTimeRequest;
import com.example.starter.race.dto.RevokePenaltyRequest;
import com.example.starter.race.dto.SealRaceRequest;
import com.example.starter.race.dto.SnapshotResponse;
import com.example.starter.race.dto.StandingEntry;
import com.example.starter.race.dto.StandingsResponse;
import com.example.starter.race.repo.PenaltyRepository;
import com.example.starter.race.repo.RaceRepository;
import com.example.starter.race.repo.RequestLogRepository;
import com.example.starter.race.repo.SnapshotRepository;
import com.example.starter.race.repo.ParticipantRepository;
import com.example.starter.race.repo.PenaltyRow;
import com.example.starter.race.repo.ParticipantRow;
import com.example.starter.race.repo.RaceRow;
import com.example.starter.race.repo.RequestLogRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 赛事成绩封榜业务服务。
 *
 * <p>并发与幂等约定：同一赛事的写操作先锁定赛事行（FOR UPDATE）串行化；
 * 每个写请求携带全局唯一 requestId，成功后与业务变更同事务写入 request_log，
 * 同键同参重放原响应、同键异参返回409；失败请求随事务回滚不占键。
 */
@Service
public class RaceService {

    private final RaceRepository races;
    private final ParticipantRepository participants;
    private final PenaltyRepository penalties;
    private final SnapshotRepository snapshots;
    private final RequestLogRepository requestLogs;
    private final ObjectMapper objectMapper;

    public RaceService(RaceRepository races, ParticipantRepository participants,
                       PenaltyRepository penalties, SnapshotRepository snapshots,
                       RequestLogRepository requestLogs, ObjectMapper objectMapper) {
        this.races = races;
        this.participants = participants;
        this.penalties = penalties;
        this.snapshots = snapshots;
        this.requestLogs = requestLogs;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建赛事，初始版本1、状态OPEN。
     */
    @Transactional
    public String createRace(CreateRaceRequest req) {
        String fingerprint = "CREATE_RACE|" + req.raceId();
        String replay = findReplay(req.requestId(), fingerprint);
        if (replay != null) {
            return replay;
        }
        if (races.find(req.raceId()).isPresent()) {
            throw ApiException.conflict("RACE_EXISTS", "赛事已存在: " + req.raceId());
        }
        races.insert(req.raceId());
        return commitLog(req.requestId(), "CREATE_RACE", fingerprint,
                new RaceResponse(req.raceId(), 1L, RaceStatus.OPEN.name()));
    }

    /**
     * 登记选手，赛事版本加一。
     */
    @Transactional
    public String registerParticipant(String raceId, RegisterParticipantRequest req) {
        RaceRow race = lockRace(raceId);
        String fingerprint = "REGISTER|" + raceId + "|" + req.bib() + "|" + req.rawTimeMs();
        String replay = findReplay(req.requestId(), fingerprint);
        if (replay != null) {
            return replay;
        }
        ensureOpen(race);
        ensureVersion(race, req.expectedVersion());
        if (participants.find(raceId, req.bib()).isPresent()) {
            throw ApiException.conflict("PARTICIPANT_EXISTS", "参赛号已登记: " + req.bib());
        }
        participants.insert(raceId, req.bib(), req.rawTimeMs());
        long newVersion = bumpVersion(race);
        return commitLog(req.requestId(), "REGISTER", fingerprint,
                new ParticipantResponse(raceId, req.bib(), req.rawTimeMs(), newVersion));
    }

    /**
     * 修订选手原始完赛耗时，赛事版本加一。
     */
    @Transactional
    public String reviseTime(String raceId, String bib, ReviseTimeRequest req) {
        RaceRow race = lockRace(raceId);
        String fingerprint = "REVISE_TIME|" + raceId + "|" + bib + "|" + req.rawTimeMs();
        String replay = findReplay(req.requestId(), fingerprint);
        if (replay != null) {
            return replay;
        }
        ensureOpen(race);
        ensureVersion(race, req.expectedVersion());
        if (participants.find(raceId, bib).isEmpty()) {
            throw ApiException.notFound("PARTICIPANT_NOT_FOUND", "选手不存在: " + bib);
        }
        participants.updateRawTime(raceId, bib, req.rawTimeMs());
        long newVersion = bumpVersion(race);
        return commitLog(req.requestId(), "REVISE_TIME", fingerprint,
                new ParticipantResponse(raceId, bib, req.rawTimeMs(), newVersion));
    }

    /**
     * 新增处罚（加时或取消资格），赛事版本加一。
     */
    @Transactional
    public String addPenalty(String raceId, AddPenaltyRequest req) {
        RaceRow race = lockRace(raceId);
        String fingerprint = "ADD_PENALTY|" + raceId + "|" + req.penaltyId() + "|" + req.bib()
                + "|" + req.type() + "|" + req.amountMs();
        String replay = findReplay(req.requestId(), fingerprint);
        if (replay != null) {
            return replay;
        }
        ensureOpen(race);
        ensureVersion(race, req.expectedVersion());
        validatePenaltyPayload(req);
        if (participants.find(raceId, req.bib()).isEmpty()) {
            throw ApiException.notFound("PARTICIPANT_NOT_FOUND", "选手不存在: " + req.bib());
        }
        if (penalties.find(req.penaltyId()).isPresent()) {
            throw ApiException.conflict("PENALTY_EXISTS", "处罚ID已存在: " + req.penaltyId());
        }
        penalties.insert(req.penaltyId(), raceId, req.bib(), req.type().name(), req.amountMs());
        long newVersion = bumpVersion(race);
        return commitLog(req.requestId(), "ADD_PENALTY", fingerprint,
                new PenaltyResponse(req.penaltyId(), raceId, req.bib(), req.type().name(),
                        req.amountMs(), false, newVersion));
    }

    /**
     * 撤销处罚，赛事版本加一。
     */
    @Transactional
    public String revokePenalty(String raceId, String penaltyId, RevokePenaltyRequest req) {
        RaceRow race = lockRace(raceId);
        String fingerprint = "REVOKE_PENALTY|" + raceId + "|" + penaltyId;
        String replay = findReplay(req.requestId(), fingerprint);
        if (replay != null) {
            return replay;
        }
        ensureOpen(race);
        ensureVersion(race, req.expectedVersion());
        Optional<PenaltyRow> penalty = penalties.find(penaltyId);
        if (penalty.isEmpty() || !penalty.get().raceId().equals(raceId)) {
            throw ApiException.notFound("PENALTY_NOT_FOUND", "处罚不存在: " + penaltyId);
        }
        if (penalty.get().revoked()) {
            throw ApiException.conflict("PENALTY_ALREADY_REVOKED", "处罚已撤销: " + penaltyId);
        }
        penalties.revoke(penaltyId);
        long newVersion = bumpVersion(race);
        PenaltyRow p = penalty.get();
        return commitLog(req.requestId(), "REVOKE_PENALTY", fingerprint,
                new PenaltyResponse(penaltyId, raceId, p.bib(), p.type(), p.amountMs(), true,
                        newVersion));
    }

    /**
     * 封榜：校验版本后原子保存全体选手只读成绩快照并转为 SEALED。
     */
    @Transactional
    public String sealRace(String raceId, SealRaceRequest req) {
        RaceRow race = lockRace(raceId);
        String fingerprint = "SEAL|" + raceId;
        String replay = findReplay(req.requestId(), fingerprint);
        if (replay != null) {
            return replay;
        }
        ensureOpen(race);
        ensureVersion(race, req.expectedVersion());
        List<StandingEntry> snapshot = computeStandings(raceId);
        snapshots.insertAll(raceId, snapshot);
        races.markSealed(raceId);
        return commitLog(req.requestId(), "SEAL", fingerprint,
                new SnapshotResponse(raceId, race.version(), RaceStatus.SEALED.name(), snapshot));
    }

    /**
     * 查询即时成绩（按当前数据实时计算）。
     */
    @Transactional(readOnly = true)
    public StandingsResponse standings(String raceId) {
        RaceRow race = races.find(raceId)
                .orElseThrow(() -> ApiException.notFound("RACE_NOT_FOUND", "赛事不存在: " + raceId));
        return new StandingsResponse(raceId, race.version(), race.status(),
                computeStandings(raceId));
    }

    /**
     * 查询封榜快照；未封榜返回409。
     */
    @Transactional(readOnly = true)
    public SnapshotResponse snapshot(String raceId) {
        RaceRow race = races.find(raceId)
                .orElseThrow(() -> ApiException.notFound("RACE_NOT_FOUND", "赛事不存在: " + raceId));
        if (!RaceStatus.SEALED.name().equals(race.status())) {
            throw ApiException.conflict("RACE_NOT_SEALED", "赛事尚未封榜: " + raceId);
        }
        return new SnapshotResponse(raceId, race.version(), race.status(),
                snapshots.findByRace(raceId));
    }

    /**
     * 计算同赛事写操作串行化：锁定赛事行，不存在则404。
     */
    private RaceRow lockRace(String raceId) {
        return races.findForUpdate(raceId)
                .orElseThrow(() -> ApiException.notFound("RACE_NOT_FOUND", "赛事不存在: " + raceId));
    }

    private void ensureOpen(RaceRow race) {
        if (!RaceStatus.OPEN.name().equals(race.status())) {
            throw ApiException.conflict("RACE_SEALED", "赛事已封榜，禁止写入: " + race.raceId());
        }
    }

    private void ensureVersion(RaceRow race, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion != race.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "版本冲突: 期望=" + expectedVersion + ", 当前=" + race.version());
        }
    }

    private long bumpVersion(RaceRow race) {
        if (!races.incrementVersion(race.raceId(), race.version())) {
            throw ApiException.conflict("VERSION_CONFLICT", "版本冲突: 赛事已被并发修改");
        }
        return race.version() + 1;
    }

    private void validatePenaltyPayload(AddPenaltyRequest req) {
        if (req.type() == PenaltyType.TIME_ADD) {
            if (req.amountMs() == null || req.amountMs() < 1 || req.amountMs() > 3600000) {
                throw ApiException.badRequest("INVALID_PENALTY_AMOUNT",
                        "加时处罚时长必须为1~3600000毫秒");
            }
        } else if (req.amountMs() != null) {
            throw ApiException.badRequest("INVALID_PENALTY_AMOUNT",
                    "取消资格处罚不允许携带时长");
        }
    }

    /**
     * 幂等检查：同键同参返回已存响应，同键异参返回409。
     */
    private String findReplay(String requestId, String fingerprint) {
        Optional<RequestLogRow> existing = requestLogs.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        if (!existing.get().fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "请求ID已用于其他参数: " + requestId);
        }
        return existing.get().responseBody();
    }

    /**
     * 序列化响应并与业务变更同事务写入去重记录。
     */
    private String commitLog(String requestId, String action, String fingerprint, Object response) {
        String body;
        try {
            body = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
        requestLogs.insert(requestId, action, fingerprint, body);
        return body;
    }

    /**
     * 计算即时成绩：总耗时=原始耗时+全部未撤销加时；存在未撤销取消资格为DISQUALIFIED；
     * 计时缺失为UNTIMED；正常选手按总耗时升序，同耗时同名次，下一名次跳过并列人数，
     * 并列按参赛号字典序展示。
     */
    private List<StandingEntry> computeStandings(String raceId) {
        Map<String, List<PenaltyRow>> activeByBib = penalties.findActiveByRace(raceId).stream()
                .collect(Collectors.groupingBy(PenaltyRow::bib));

        record Ranked(String bib, long total) {
        }
        List<Ranked> ranked = new ArrayList<>();
        List<String> untimed = new ArrayList<>();
        List<String> disqualified = new ArrayList<>();

        for (ParticipantRow p : participants.findByRace(raceId)) {
            List<PenaltyRow> active = activeByBib.getOrDefault(p.bib(), List.of());
            boolean dq = active.stream().anyMatch(x -> PenaltyType.DISQUALIFY.name().equals(x.type()));
            if (dq) {
                disqualified.add(p.bib());
            } else if (p.rawTimeMs() == null) {
                untimed.add(p.bib());
            } else {
                long total = p.rawTimeMs() + active.stream()
                        .filter(x -> PenaltyType.TIME_ADD.name().equals(x.type()))
                        .mapToLong(PenaltyRow::amountMs).sum();
                ranked.add(new Ranked(p.bib(), total));
            }
        }

        ranked.sort(Comparator.comparingLong(Ranked::total).thenComparing(Ranked::bib));
        List<StandingEntry> result = new ArrayList<>();
        long prevTotal = Long.MIN_VALUE;
        int prevRank = 0;
        for (int i = 0; i < ranked.size(); i++) {
            Ranked r = ranked.get(i);
            int rank = (i > 0 && r.total() == prevTotal) ? prevRank : i + 1;
            prevTotal = r.total();
            prevRank = rank;
            result.add(new StandingEntry(r.bib(), StandingStatus.RANKED.name(), r.total(), rank));
        }
        untimed.stream().sorted().forEach(bib ->
                result.add(new StandingEntry(bib, StandingStatus.UNTIMED.name(), null, null)));
        disqualified.stream().sorted().forEach(bib ->
                result.add(new StandingEntry(bib, StandingStatus.DISQUALIFIED.name(), null, null)));
        return result;
    }
}
