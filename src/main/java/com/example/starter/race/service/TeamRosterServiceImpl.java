package com.example.starter.race.service;

import com.example.starter.race.api.LockRosterRequest;
import com.example.starter.race.api.RosterResponse;
import com.example.starter.race.api.TeamStandingResponse;
import com.example.starter.race.api.WithdrawMemberRequest;
import com.example.starter.race.api.WithdrawalResponse;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.domain.TeamScoreCalculator;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RaceRepository;
import com.example.starter.race.persistence.RosterLockEntryRow;
import com.example.starter.race.persistence.RosterLockRow;
import com.example.starter.race.persistence.RosterMembershipRow;
import com.example.starter.race.persistence.RosterRepository;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotRow;
import com.example.starter.race.persistence.SnapshotTeamMemberRow;
import com.example.starter.race.persistence.SnapshotTeamRow;
import com.example.starter.race.persistence.TeamRow;
import com.example.starter.race.persistence.WithdrawalRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@link TeamRosterService} 的事务实现。
 *
 * <p>并发与一致性要点：
 * <ul>
 *   <li>锁定名单先在 race 行上加写锁（{@code FOR UPDATE}），与封榜及其它赛事写互斥：
 *       封榜事务要么在锁定提交前读到旧版本、要么在其后读到完整新版本，
 *       榜单只能引用一个完整名单版本，不会读到插入一半的成员；</li>
 *   <li>锁定在同一事务内完成“归属校验 + 个人成绩快照固化 + 条件版本推进 + 版本写入”，
 *       任一名单项不合法则整体回滚，不生成半份名单、不改变已有成绩归属；</li>
 *   <li>成员跨队唯一性由 roster_membership(race_id,bib) 唯一约束兜底；</li>
 *   <li>退赛只追加 roster_withdrawal 并把归属置为不活跃，不删除任何锁定行、不推进赛事版本；
 *       封榜时刻已登记的退赛固化进封榜快照，封榜后登记的退赛不重算已封榜成绩。</li>
 * </ul>
 */
@Service
public class TeamRosterServiceImpl implements TeamRosterService {

    /** 实时查询未指定场次时，视全部已登记退赛均已生效。 */
    private static final int ALL_SESSIONS = Integer.MAX_VALUE;

    private final RaceRepository raceRepository;
    private final RosterRepository rosterRepository;
    private final IdempotencyTemplate idempotency;
    private final Clock clock;

    public TeamRosterServiceImpl(
            RaceRepository raceRepository,
            RosterRepository rosterRepository,
            IdempotencyTemplate idempotency,
            Clock clock) {
        this.raceRepository = raceRepository;
        this.rosterRepository = rosterRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ServiceResult lockRoster(String raceId, String teamId, LockRosterRequest request) {
        requireText(raceId, "赛事ID");
        requireText(teamId, "团队ID");
        List<String> requested = normalizeMembers(request.members());
        return idempotency.execute(
                request.requestId(),
                "LOCK_ROSTER",
                IdempotencyTemplate.orderedParams(
                        "raceId", raceId,
                        "teamId", teamId,
                        "expectedVersion", request.expectedVersion(),
                        "members", requested),
                () -> doLockRoster(raceId, teamId, request.expectedVersion(), requested));
    }

    private ServiceResult doLockRoster(
            String raceId, String teamId, int expectedVersion, List<String> requested) {
        // 与封榜互斥：整个锁定过程持有 race 行写锁。
        RaceRow race = requireOpenRace(raceId, expectedVersion);

        // 1. 个人成绩在锁定时刻的快照：先按当前全部计时/处罚/检查点数据计算。
        Map<String, ResultEntry> currentResults = computeCurrentResults(raceId);

        // 2. 全部校验先行，任何一项不合法都不写入、不推进版本（不生成半份名单）。
        Set<String> requestedSet = new HashSet<>(requested);
        Map<String, RosterMembershipRow> memberships = loadMembershipMap(raceId);
        Optional<TeamRow> team = rosterRepository.findTeam(raceId, teamId);
        Optional<RosterLockRow> latestLock = team.isEmpty()
                ? Optional.empty()
                : rosterRepository.findLatestRosterLock(raceId, teamId);
        Set<String> carriedBibs = new HashSet<>();
        if (latestLock.isPresent()) {
            for (RosterLockEntryRow prior :
                    rosterRepository.findRosterEntries(raceId, teamId, latestLock.get().rosterVersion())) {
                carriedBibs.add(prior.bib());
            }
        }

        for (String bib : requested) {
            ResultEntry entry = currentResults.get(bib);
            if (entry == null) {
                throw new NotFoundException("成员尚未在赛事中登记: " + bib);
            }
            RosterMembershipRow membership = memberships.get(bib);
            if (membership != null && !membership.teamId().equals(teamId)) {
                // 成员在同一赛事中已属另一团队，不得跨队。
                throw new ConflictException(
                        "成员已属于团队 " + membership.teamId() + "，不能加入团队 " + teamId + ": " + bib);
            }
            if (membership != null && !carriedBibs.contains(bib)) {
                // 归属在本队却不在上一版本中，理论上不会发生（归属只随锁定产生），防御性拒绝。
                throw new ConflictException("成员名单版本不一致: " + bib);
            }
        }
        if (latestLock.isPresent() && !requestedSet.containsAll(carriedBibs)) {
            // 已锁定后替换/删除成员：新版本必须是上一版本的超集（只增不换）。
            List<String> removed = carriedBibs.stream()
                    .filter(bib -> !requestedSet.contains(bib))
                    .sorted()
                    .toList();
            throw new ConflictException("已锁定名单不允许替换或移除成员: " + removed);
        }

        // 3. 校验通过后条件推进赛事版本，再固化完整版本。
        int newRaceVersion = expectedVersion + 1;
        bumpVersion(race, expectedVersion);
        long now = clock.millis();
        int newRosterVersion = latestLock.map(lock -> lock.rosterVersion() + 1).orElse(1);
        try {
            if (team.isEmpty()) {
                rosterRepository.insertTeam(new TeamRow(0L, raceId, teamId, now));
            }
            rosterRepository.insertRosterLock(new RosterLockRow(
                    raceId, teamId, newRosterVersion, newRaceVersion, now));
            List<RosterLockEntryRow> entryRows = new ArrayList<>(requested.size());
            List<RosterMembershipRow> newMemberships = new ArrayList<>();
            for (String bib : requested) {
                ResultEntry entry = currentResults.get(bib);
                entryRows.add(new RosterLockEntryRow(
                        raceId, teamId, newRosterVersion, bib,
                        entry.status(), entry.finishTimeMs(), entry.penaltyMs(),
                        entry.totalTimeMs(), entry.checkpointCount(),
                        entry.coveredCheckpointCount()));
                if (!memberships.containsKey(bib)) {
                    newMemberships.add(new RosterMembershipRow(
                            raceId, bib, teamId, newRosterVersion, true));
                }
            }
            rosterRepository.insertRosterEntries(entryRows);
            for (RosterMembershipRow membership : newMemberships) {
                rosterRepository.insertMembership(membership);
            }
        } catch (DuplicateKeyException ex) {
            // 并发兜底：团队重复创建或成员跨队唯一约束，均为409，事务随之上抛回滚。
            throw new ConflictException("名单锁定与并发请求冲突: " + teamId);
        }

        return ServiceResult.created(
                buildCurrentRoster(raceId, teamId, newRosterVersion, now));
    }

    @Override
    @Transactional
    public ServiceResult withdrawMember(
            String raceId, String teamId, String bib, WithdrawMemberRequest request) {
        requireText(raceId, "赛事ID");
        requireText(teamId, "团队ID");
        requireText(bib, "参赛号");
        return idempotency.execute(
                request.requestId(),
                "WITHDRAW_MEMBER",
                IdempotencyTemplate.orderedParams(
                        "raceId", raceId,
                        "teamId", teamId,
                        "bib", bib,
                        "effectiveSession", request.effectiveSession(),
                        "reason", request.reason()),
                () -> doWithdraw(raceId, teamId, bib, request));
    }

    private ServiceResult doWithdraw(
            String raceId, String teamId, String bib, WithdrawMemberRequest request) {
        // 退赛须与封榜串行：封榜先到则快照不含本退赛（已封榜积分不重算），本事务随后仍可登记。
        RaceRow race = raceRepository.findRaceForUpdate(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        if (rosterRepository.findTeam(raceId, teamId).isEmpty()) {
            throw new NotFoundException("团队不存在或从未锁定名单: " + teamId);
        }
        RosterMembershipRow membership = rosterRepository.findMembership(raceId, bib)
                .orElseThrow(() -> new NotFoundException("成员不在任何锁定名单中: " + bib));
        if (!membership.teamId().equals(teamId)) {
            throw new NotFoundException("成员不属于团队 " + teamId + ": " + bib);
        }
        if (!membership.active() || rosterRepository.findWithdrawal(raceId, bib).isPresent()) {
            throw new ConflictException("成员已登记退赛，不能重复退赛: " + bib);
        }
        long now = clock.millis();
        try {
            int updated = rosterRepository.insertWithdrawal(new WithdrawalRow(
                    raceId, bib, teamId, request.effectiveSession(), request.reason(), now));
            if (updated == 0) {
                throw new ConflictException("成员已登记退赛，不能重复退赛: " + bib);
            }
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("成员已登记退赛，不能重复退赛: " + bib);
        }
        // 退赛不推进赛事版本；封榜后同样允许登记，但不影响已封榜成绩。
        return ServiceResult.created(new WithdrawalResponse(
                bib, teamId, request.effectiveSession(), request.reason(), now));
    }

    @Override
    @Transactional(readOnly = true)
    public RosterResponse getCurrentRoster(String raceId, String teamId) {
        requireText(raceId, "赛事ID");
        requireText(teamId, "团队ID");
        raceRepository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        RosterLockRow latest = rosterRepository.findLatestRosterLock(raceId, teamId)
                .orElseThrow(() -> new NotFoundException("团队不存在或从未锁定名单: " + teamId));
        return buildCurrentRoster(raceId, teamId, latest.rosterVersion(), latest.lockedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public RosterResponse getRosterVersion(String raceId, String teamId, int rosterVersion) {
        requireText(raceId, "赛事ID");
        requireText(teamId, "团队ID");
        raceRepository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        if (rosterRepository.findTeam(raceId, teamId).isEmpty()) {
            throw new NotFoundException("团队不存在或从未锁定名单: " + teamId);
        }
        RosterLockRow lock = rosterRepository.findRosterLock(raceId, teamId, rosterVersion)
                .orElseThrow(() -> new NotFoundException(
                        "名单版本不存在: 团队 " + teamId + " 版本 " + rosterVersion));
        List<RosterLockEntryRow> entries =
                rosterRepository.findRosterEntries(raceId, teamId, rosterVersion);
        int latestVersion = rosterRepository.findLatestRosterLock(raceId, teamId)
                .orElseThrow().rosterVersion();
        // 历史版本原样返回：不附带退赛信息，不随后续名单变化改写。
        return TeamRosterResponseMapper.rosterVersion(
                raceId, lock, entries, latestVersion == rosterVersion);
    }

    @Override
    @Transactional(readOnly = true)
    public TeamStandingResponse getTeamResults(String raceId, Integer session) {
        RaceRow race = raceRepository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        if (session != null && session < 1) {
            throw new BadRequestException("场次号必须为正整数");
        }
        if (race.status() == RaceStatus.SEALED) {
            return buildSnapshotStanding(raceId);
        }
        int currentSession = session == null ? ALL_SESSIONS : session;
        List<TeamScoreCalculator.TeamScore> scores = computeLiveScores(raceId, currentSession);
        return TeamRosterResponseMapper.liveStanding(raceId, race.version(), scores);
    }

    @Override
    @Transactional(readOnly = true)
    public TeamStandingResponse getTeamSnapshot(String raceId) {
        RaceRow race = raceRepository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        if (race.status() != RaceStatus.SEALED) {
            throw new NotFoundException("赛事尚未封榜: " + raceId);
        }
        return buildSnapshotStanding(raceId);
    }

    /**
     * 封榜事务调用：把各团队当前最新完整名单版本及其成员固化成绩按封榜时刻退赛状态汇总，
     * 生成团队只读快照行。封榜后再登记的退赛不会触达这里，已封榜积分不重算。
     */
    public List<SnapshotTeamRow> buildTeamSnapshotRows(
            String raceId, List<SnapshotTeamMemberRow> memberSink) {
        // 封榜时刻已登记的退赛全部视为生效（封榜即终局结算）。
        List<TeamScoreCalculator.TeamScore> scores = computeLiveScores(raceId, ALL_SESSIONS);
        List<SnapshotTeamRow> teams = new ArrayList<>(scores.size());
        for (int order = 0; order < scores.size(); order++) {
            TeamScoreCalculator.TeamScore score = scores.get(order);
            teams.add(new SnapshotTeamRow(
                    raceId, score.teamId(), score.rosterVersion(), score.totalTimeMs(),
                    score.memberCount(), score.scoringCount(), order));
            for (TeamScoreCalculator.MemberScore member : score.members()) {
                memberSink.add(new SnapshotTeamMemberRow(
                        raceId, score.teamId(), member.bib(), member.status(),
                        member.finishTimeMs(), member.penaltyMs(), member.totalTimeMs(),
                        member.scoring(), member.withdrawn(), member.effectiveSession()));
            }
        }
        return teams;
    }

    /**
     * 实时团队计分：每队取其最新锁定版本（一个完整版本）与版本内固化的个人成绩，
     * 退赛按生效场次决定是否计入；不读取锁定之后的计时/处罚变化。
     */
    private List<TeamScoreCalculator.TeamScore> computeLiveScores(String raceId, int currentSession) {
        Map<String, WithdrawalRow> withdrawalByBib = new LinkedHashMap<>();
        for (WithdrawalRow withdrawal : rosterRepository.findWithdrawals(raceId)) {
            withdrawalByBib.put(withdrawal.bib(), withdrawal);
        }
        Map<String, List<RosterLockEntryRow>> entriesByTeam = new TreeMap<>();
        for (RosterLockEntryRow entry : rosterRepository.findLatestRosterEntries(raceId)) {
            entriesByTeam.computeIfAbsent(entry.teamId(), key -> new ArrayList<>()).add(entry);
        }
        List<TeamRosterViewAdapter> rosters = new ArrayList<>();
        for (Map.Entry<String, List<RosterLockEntryRow>> teamEntries : entriesByTeam.entrySet()) {
            String teamId = teamEntries.getKey();
            int latestVersion = rosterRepository.findLatestRosterLock(raceId, teamId)
                    .orElseThrow().rosterVersion();
            rosters.add(new TeamRosterViewAdapter(teamId, latestVersion, teamEntries.getValue()));
        }
        return TeamScoreCalculator.compute(rosters, withdrawalByBib, currentSession);
    }

    private TeamStandingResponse buildSnapshotStanding(String raceId) {
        SnapshotRow snapshot = raceRepository.findSnapshot(raceId)
                .orElseThrow(() -> new IllegalStateException("赛事已封榜但缺少快照: " + raceId));
        List<SnapshotTeamRow> teams = rosterRepository.findTeamSnapshotRows(raceId);
        Map<String, List<SnapshotTeamMemberRow>> membersByTeam = new LinkedHashMap<>();
        for (SnapshotTeamMemberRow member : rosterRepository.findTeamSnapshotMembers(raceId)) {
            membersByTeam.computeIfAbsent(member.teamId(), key -> new ArrayList<>()).add(member);
        }
        return TeamRosterResponseMapper.snapshotStanding(
                raceId, snapshot.version(), snapshot.sealedAt(), teams, membersByTeam);
    }

    private RosterResponse buildCurrentRoster(String raceId, String teamId,
                                              int rosterVersion, long lockedAt) {
        RosterLockRow lock = new RosterLockRow(
                raceId, teamId, rosterVersion, 0, lockedAt);
        List<RosterLockEntryRow> entries =
                rosterRepository.findRosterEntries(raceId, teamId, rosterVersion);
        Map<String, RosterMembershipRow> membershipByBib = new LinkedHashMap<>();
        for (RosterMembershipRow membership : rosterRepository.findMemberships(raceId)) {
            if (membership.teamId().equals(teamId)) {
                membershipByBib.put(membership.bib(), membership);
            }
        }
        Map<String, WithdrawalRow> withdrawalByBib = new LinkedHashMap<>();
        List<WithdrawalRow> teamWithdrawals = new ArrayList<>();
        for (WithdrawalRow withdrawal : rosterRepository.findWithdrawals(raceId)) {
            if (withdrawal.teamId().equals(teamId)) {
                withdrawalByBib.put(withdrawal.bib(), withdrawal);
                teamWithdrawals.add(withdrawal);
            }
        }
        return TeamRosterResponseMapper.currentRoster(
                raceId, lock, entries, membershipByBib, withdrawalByBib, teamWithdrawals);
    }

    private Map<String, ResultEntry> computeCurrentResults(String raceId) {
        List<RunnerRow> runners = raceRepository.findRunners(raceId);
        List<PenaltyRow> penalties = raceRepository.findPenalties(raceId);
        List<CheckpointRow> checkpoints = raceRepository.findCheckpoints(raceId);
        List<CheckpointTimingRow> timings = raceRepository.findAllTimings(raceId);
        List<ResultEntry> entries =
                ResultCalculator.compute(runners, penalties, checkpoints, timings);
        Map<String, ResultEntry> byBib = new LinkedHashMap<>();
        for (ResultEntry entry : entries) {
            byBib.put(entry.bib(), entry);
        }
        return byBib;
    }

    private Map<String, RosterMembershipRow> loadMembershipMap(String raceId) {
        Map<String, RosterMembershipRow> memberships = new LinkedHashMap<>();
        for (RosterMembershipRow membership : rosterRepository.findMemberships(raceId)) {
            memberships.put(membership.bib(), membership);
        }
        return memberships;
    }

    private RaceRow requireOpenRace(String raceId, int expectedVersion) {
        RaceRow race = raceRepository.findRaceForUpdate(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        if (race.status() == RaceStatus.SEALED) {
            throw new ConflictException("赛事已封榜，禁止锁定名单: " + raceId);
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
                throw new ConflictException("赛事已封榜，禁止锁定名单: " + race.raceId());
            }
            throw new ConflictException("版本冲突: expected=" + expectedVersion
                    + ", actual=" + refreshed.version());
        }
    }

    /** 去空白、保持请求顺序去重；请求内重复成员在调用方按409拒绝（此处保留首次出现顺序）。 */
    private static List<String> normalizeMembers(List<String> members) {
        List<String> normalized = new ArrayList<>(members.size());
        Set<String> seen = new HashSet<>();
        for (String member : members) {
            if (member == null || member.isBlank()) {
                throw new BadRequestException("成员参赛号不能为空");
            }
            String bib = member.trim();
            if (!seen.add(bib)) {
                throw new ConflictException("成员在同一份名单中重复出现: " + bib);
            }
            normalized.add(bib);
        }
        return normalized;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + "不能为空");
        }
    }

    /** 把仓储行适配为团队计分视图。 */
    private record TeamRosterViewAdapter(
            String teamId,
            int rosterVersion,
            List<RosterLockEntryRow> members
    ) implements TeamScoreCalculator.TeamRosterView {
    }
}
