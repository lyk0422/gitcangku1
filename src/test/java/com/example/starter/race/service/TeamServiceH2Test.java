package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.CreateTeamRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.TeamMemberResponse;
import com.example.starter.race.api.TeamResponse;
import com.example.starter.race.api.TeamsResponse;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.TeamStatus;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 团队计分的 H2 数据库测试：建队冻结规则、实时计分、封榜同事务快照、
 * 400/404/409 失败分支、幂等重放与真实并发。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class TeamServiceH2Test extends AbstractRaceH2Test {

    @Autowired
    private RaceService raceService;

    private void createRace(String raceId) {
        raceService.createRace(new CreateRaceRequest(raceId, "create-" + raceId));
    }

    /** 登记一名计时缺失的选手并返回登记后的赛事版本。 */
    private int registerUntimed(String raceId, String bib, int expectedVersion) {
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest(bib, null, expectedVersion, "reg-" + raceId + "-" + bib));
        return expectedVersion + 1;
    }

    @Test
    void 建队实时计分处罚修订到封榜同版本快照全流程() {
        String raceId = "team-flow";
        createRace(raceId);
        int version = 1;
        for (String bib : List.of("a", "b", "c", "d", "e", "f", "g")) {
            version = registerUntimed(raceId, bib, version);
        }
        assertThat(version).isEqualTo(8);

        // 建队时全部选手 UNTIMED：团队初始 INCOMPLETE，版本加一。
        ServiceResult created = raceService.createTeam(raceId, new CreateTeamRequest(
                "T1", List.of("a", "b", "c"), 8, "team-t1"));
        assertThat(created.status()).isEqualTo(201);
        TeamResponse createdTeam = (TeamResponse) created.body();
        assertThat(createdTeam.status()).isEqualTo(TeamStatus.INCOMPLETE);
        assertThat(createdTeam.totalTimeMs()).isNull();
        assertThat(createdTeam.members()).extracting(TeamMemberResponse::bib)
                .containsExactly("a", "b", "c");

        raceService.createTeam(raceId, new CreateTeamRequest(
                "T2", List.of("d", "e", "f", "g"), 9, "team-t2"));

        // 补齐/修订计时后实时派生团队成绩，不另存积分累计。
        version = 10;
        for (var revise : List.of(
                new ReviseTimeRequest("a", 1000L, version, "rv-a"),
                new ReviseTimeRequest("b", 1100L, version + 1, "rv-b"),
                new ReviseTimeRequest("c", 1200L, version + 2, "rv-c"),
                new ReviseTimeRequest("d", 100L, version + 3, "rv-d"),
                new ReviseTimeRequest("e", 200L, version + 4, "rv-e"),
                new ReviseTimeRequest("f", 300L, version + 5, "rv-f"))) {
            raceService.reviseTime(raceId, revise);
        }
        version = 16;

        TeamsResponse live = raceService.getTeams(raceId);
        assertThat(live.status()).isEqualTo(RaceStatus.OPEN);
        assertThat(live.version()).isEqualTo(16);
        assertThat(live.teams()).extracting(TeamResponse::teamCode).containsExactly("T2", "T1");
        assertThat(live.teams()).extracting(TeamResponse::rank).containsExactly(1, 2);
        assertThat(live.teams()).extracting(TeamResponse::totalTimeMs).containsExactly(600L, 3300L);
        TeamResponse t2 = live.teams().getFirst();
        assertThat(t2.members()).extracting(TeamMemberResponse::scored)
                .containsExactly(true, true, true, false);
        assertThat(t2.members()).extracting(TeamMemberResponse::status)
                .containsExactly(EntryStatus.RANKED, EntryStatus.RANKED, EntryStatus.RANKED,
                        EntryStatus.UNTIMED);

        // 处罚生效立即影响未封榜团队：d 加时50 -> T2 合计650，仍为第1。
        raceService.addPenalty(raceId, new AddPenaltyRequest(
                "pen-d", "d", "ADD_TIME", 50L, version, "pen-d"));
        TeamsResponse afterPenalty = raceService.getTeams(raceId);
        assertThat(afterPenalty.teams()).extracting(TeamResponse::totalTimeMs)
                .containsExactly(650L, 3300L);
        TeamResponse t2AfterPenalty = afterPenalty.teams().getFirst();
        assertThat(t2AfterPenalty.members().getFirst().totalTimeMs()).isEqualTo(150L);
        // 个人名次也来自同一实时计算：d=150第1，a=1000第4。
        assertThat(t2AfterPenalty.members().getFirst().personalRank()).isEqualTo(1);
        TeamResponse t1AfterPenalty = afterPenalty.teams().get(1);
        assertThat(t1AfterPenalty.members().getFirst().personalRank()).isEqualTo(4);

        // 新登记的未计时选手无法再建队：赛事已有完赛计时，配置冻结。
        int frozenVersion = 17;
        frozenVersion = registerUntimed(raceId, "h", frozenVersion);
        frozenVersion = registerUntimed(raceId, "i", frozenVersion);
        frozenVersion = registerUntimed(raceId, "j", frozenVersion);
        assertThat(frozenVersion).isEqualTo(20);
        assertThatThrownBy(() -> raceService.createTeam(raceId, new CreateTeamRequest(
                "T3", List.of("h", "i", "j"), 20, "team-t3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("冻结");

        // 撤销处罚不会清除完赛计时，团队配置仍冻结。
        raceService.revokePenalty(raceId, "pen-d",
                new RevokePenaltyRequest(20, "revoke-pen-d"));
        assertThatThrownBy(() -> raceService.createTeam(raceId, new CreateTeamRequest(
                "T3", List.of("h", "i", "j"), 21, "team-t3-retry")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("冻结");
        // 撤销后团队成绩回到600。
        assertThat(raceService.getTeams(raceId).teams().getFirst().totalTimeMs()).isEqualTo(600L);

        // 封榜：团队与个人在同一事务同版本冻结。
        raceService.sealRace(raceId, new SealRaceRequest(21, "seal-flow"));
        TeamsResponse sealed = raceService.getTeams(raceId);
        assertThat(sealed.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(sealed.version()).isEqualTo(22);
        assertThat(sealed.sealedAt()).isNotNull();
        assertThat(sealed.teams()).extracting(TeamResponse::teamCode).containsExactly("T2", "T1");
        assertThat(sealed.teams()).extracting(TeamResponse::rank).containsExactly(1, 2);
        assertThat(sealed.teams()).extracting(TeamResponse::totalTimeMs).containsExactly(600L, 3300L);
        assertThat(repository.findTeamSnapshot(raceId)).isPresent();
        assertThat(repository.findTeamSnapshot(raceId).orElseThrow().version()).isEqualTo(22);

        // 封榜后不再随写入变化（写入本身也被409拒绝），查询只返回快照。
        assertThatThrownBy(() -> raceService.reviseTime(raceId,
                new ReviseTimeRequest("a", 900L, 22, "rv-a-after-seal")))
                .isInstanceOf(ConflictException.class);
        assertThat(raceService.getTeams(raceId).teams().get(1).totalTimeMs()).isEqualTo(3300L);
    }

    @Test
    void 已封榜但无团队的赛事查询返回空团队列表且不补造队伍() {
        String raceId = "team-empty";
        createRace(raceId);
        registerUntimed(raceId, "a", 1);
        raceService.sealRace(raceId, new SealRaceRequest(2, "seal-empty"));

        TeamsResponse response = raceService.getTeams(raceId);
        assertThat(response.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(response.version()).isEqualTo(3);
        assertThat(response.teams()).isEmpty();
        assertThat(repository.findTeamSnapshot(raceId)).isEmpty();
    }

    @Test
    void 参数非法返回400() {
        String raceId = "team-bad";
        createRace(raceId);
        registerUntimed(raceId, "a", 1);
        registerUntimed(raceId, "b", 2);
        registerUntimed(raceId, "c", 3);

        assertThatThrownBy(() -> raceService.createTeam(raceId, new CreateTeamRequest(
                "T1", List.of("a", "b"), 4, "bad-size")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("3~5");
        assertThatThrownBy(() -> raceService.createTeam(raceId, new CreateTeamRequest(
                "T1", List.of("a", "b", "b"), 4, "bad-dup")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("互不重复");
        // 400 失败不改版本：当前仍为4，后续同 expectedVersion=4 建队成功。
        raceService.createTeam(raceId, new CreateTeamRequest(
                "T1", List.of("a", "b", "c"), 4, "good-after-bad"));
        assertThat(repository.findRace(raceId).orElseThrow().version()).isEqualTo(5);
    }

    @Test
    void 成员未登记返回404且不改成员版本() {
        String raceId = "team-404";
        createRace(raceId);
        registerUntimed(raceId, "a", 1);
        registerUntimed(raceId, "b", 2);
        registerUntimed(raceId, "c", 3);

        assertThatThrownBy(() -> raceService.createTeam(raceId, new CreateTeamRequest(
                "T1", List.of("a", "b", "ghost"), 4, "missing-member")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("ghost");
        assertThat(repository.findTeam(raceId, "T1")).isEmpty();
        assertThat(repository.findRace(raceId).orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void 选手重复入队团队代码重复版本不符与已封榜均返回409() {
        String raceId = "team-409";
        createRace(raceId);
        int version = 1;
        for (String bib : List.of("a", "b", "c", "d", "e", "f", "g")) {
            version = registerUntimed(raceId, bib, version);
        }
        assertThat(version).isEqualTo(8);
        raceService.createTeam(raceId, new CreateTeamRequest(
                "T1", List.of("a", "b", "c"), version, "team-t1"));
        version++;
        assertThat(version).isEqualTo(9);

        // 同一选手最多属于一队（版本匹配但成员冲突）。
        assertThatThrownBy(() -> raceService.createTeam(raceId, new CreateTeamRequest(
                "T2", List.of("a", "b", "d"), version, "team-t2-conflict")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不能重复入队");
        // teamCode 赛事内唯一（成员互不冲突，仍因代码重复409）。
        assertThatThrownBy(() -> raceService.createTeam(raceId, new CreateTeamRequest(
                "T1", List.of("d", "e", "f"), version, "team-t1-dup-code")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("团队代码已存在");
        // 版本不符。
        assertThatThrownBy(() -> raceService.createTeam(raceId, new CreateTeamRequest(
                "T3", List.of("d", "e", "g"), 4, "team-t3-stale")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");
        // 失败请求均不推进版本，团队仍只有 T1。
        assertThat(repository.findRace(raceId).orElseThrow().version()).isEqualTo(9);
        assertThat(repository.findTeams(raceId)).hasSize(1);

        // 已封榜赛事禁止建队。
        raceService.sealRace(raceId, new SealRaceRequest(9, "seal-409"));
        assertThatThrownBy(() -> raceService.createTeam(raceId, new CreateTeamRequest(
                "T4", List.of("d", "e", "g"), 10, "team-t4-sealed")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 选手带完赛计时登记后团队配置立即冻结() {
        String raceId = "team-freeze-finish";
        createRace(raceId);
        // 直接登记带完赛耗时的选手：赛事即刻出现完赛计时。
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest("a", 1000L, 1, "reg-a-finished"));
        registerUntimed(raceId, "b", 2);
        registerUntimed(raceId, "c", 3);

        assertThatThrownBy(() -> raceService.createTeam(raceId, new CreateTeamRequest(
                "T1", List.of("a", "b", "c"), 4, "team-frozen")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("冻结");
        assertThat(repository.findTeams(raceId)).isEmpty();
    }

    @Test
    void 同键同参含集合换序重放首次结果异参409失败不占键() {
        String raceId = "team-idem";
        createRace(raceId);
        registerUntimed(raceId, "x", 1);
        registerUntimed(raceId, "y", 2);
        registerUntimed(raceId, "z", 3);
        registerUntimed(raceId, "w", 4);

        ServiceResult first = raceService.createTeam(raceId, new CreateTeamRequest(
                "T1", List.of("z", "x", "y"), 5, "idem-key"));
        assertThat(first.status()).isEqualTo(201);
        RaceRow afterFirst = repository.findRace(raceId).orElseThrow();
        assertThat(afterFirst.version()).isEqualTo(6);

        // 成员集合换序、同键同参：重放首次结果，版本不再变化。
        ServiceResult replayReordered = raceService.createTeam(raceId, new CreateTeamRequest(
                "T1", List.of("x", "y", "z"), 5, "idem-key"));
        assertThat(replayReordered.status()).isEqualTo(201);
        assertThat(repository.findRace(raceId).orElseThrow().version()).isEqualTo(6);
        assertThat(repository.findTeams(raceId)).hasSize(1);

        // 同键异参（换 teamCode 或换成员）：409，且不新增团队。
        assertThatThrownBy(() -> raceService.createTeam(raceId, new CreateTeamRequest(
                "OTHER", List.of("x", "y", "z"), 5, "idem-key")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findTeams(raceId)).hasSize(1);
    }

    @Test
    void 失败请求不占用requestId键() {
        String raceId = "team-nokey";
        createRace(raceId);
        registerUntimed(raceId, "a", 1);
        registerUntimed(raceId, "b", 2);
        registerUntimed(raceId, "c", 3);

        // 同键先因成员重复 400（事务回滚，占位行消失），再以同键提交合法请求成功。
        assertThatThrownBy(() -> raceService.createTeam(raceId, new CreateTeamRequest(
                "T1", List.of("a", "b", "b"), 4, "freed-key")))
                .isInstanceOf(BadRequestException.class);
        ServiceResult retried = raceService.createTeam(raceId, new CreateTeamRequest(
                "T1", List.of("a", "b", "c"), 4, "freed-key"));
        assertThat(retried.status()).isEqualTo(201);
        assertThat(repository.findTeams(raceId)).hasSize(1);
    }

    @Test
    void 补齐漏点立即影响未封榜团队() {
        String raceId = "team-checkpoint";
        createRace(raceId);
        registerUntimed(raceId, "a", 1);
        registerUntimed(raceId, "b", 2);
        registerUntimed(raceId, "c", 3);
        // 完赛计时出现前先建队（v4 -> v5）。
        raceService.createTeam(raceId, new CreateTeamRequest(
                "T1", List.of("a", "b", "c"), 4, "team-cp"));
        // 配置2个检查点（v5 -> v6）。
        raceService.configureCheckpoints(raceId, new ConfigureCheckpointsRequest(
                List.of(
                        new ConfigureCheckpointsRequest.CheckpointDefinition("p1", 1),
                        new ConfigureCheckpointsRequest.CheckpointDefinition("p2", 2)),
                5, "cfg-cp"));
        // 修订完赛耗时（v6 -> v9），此时无人覆盖检查点，团队因 MISSING_CHECKPOINT 仍 INCOMPLETE。
        raceService.reviseTime(raceId, new ReviseTimeRequest("a", 1000L, 6, "rv-a-cp"));
        raceService.reviseTime(raceId, new ReviseTimeRequest("b", 2000L, 7, "rv-b-cp"));
        raceService.reviseTime(raceId, new ReviseTimeRequest("c", 3000L, 8, "rv-c-cp"));
        TeamResponse before = raceService.getTeams(raceId).teams().getFirst();
        assertThat(before.status()).isEqualTo(TeamStatus.INCOMPLETE);
        assertThat(before.members()).extracting(TeamMemberResponse::status)
                .containsOnly(EntryStatus.MISSING_CHECKPOINT);

        // 为每人补齐 p1、p2 分段（v9 -> v15）。
        int expected = 9;
        for (String bib : List.of("a", "b", "c")) {
            raceService.submitTiming(raceId, bib, new SubmitTimingRequest(
                    "tm-" + bib + "-p1", "p1", 100L, expected++, "tm-" + bib + "-p1"));
            raceService.submitTiming(raceId, bib, new SubmitTimingRequest(
                    "tm-" + bib + "-p2", "p2", 200L, expected++, "tm-" + bib + "-p2"));
        }

        // 漏点补齐后团队立即 COMPLETE：合计个人总耗时 1000+2000+3000=6000。
        TeamResponse after = raceService.getTeams(raceId).teams().getFirst();
        assertThat(after.status()).isEqualTo(TeamStatus.COMPLETE);
        assertThat(after.rank()).isEqualTo(1);
        assertThat(after.totalTimeMs()).isEqualTo(6000L);
        assertThat(after.members()).extracting(TeamMemberResponse::scored)
                .containsOnly(true);
    }

    @Test
    void 并发同版本建队仅一个成功且版本只加一() throws Exception {
        String raceId = "team-concurrent";
        createRace(raceId);
        int version = 1;
        for (String bib : List.of("a", "b", "c", "d", "e", "f")) {
            version = registerUntimed(raceId, bib, version);
        }
        assertThat(version).isEqualTo(7);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<java.util.concurrent.Callable<Void>> tasks = List.of(
                    () -> {
                        start.await();
                        try {
                            raceService.createTeam(raceId, new CreateTeamRequest(
                                    "T1", List.of("a", "b", "c"), 7, "cc-t1"));
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    },
                    () -> {
                        start.await();
                        try {
                            raceService.createTeam(raceId, new CreateTeamRequest(
                                    "T2", List.of("d", "e", "f"), 7, "cc-t2"));
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    });
            List<Future<Void>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        assertThat(repository.findRace(raceId).orElseThrow().version()).isEqualTo(8);
        assertThat(repository.findTeams(raceId)).hasSize(1);
    }

    @Test
    void 同requestId并发建队只产生一个团队() throws Exception {
        String raceId = "team-concurrent-key";
        createRace(raceId);
        int version = 1;
        for (String bib : List.of("a", "b", "c")) {
            version = registerUntimed(raceId, bib, version);
        }

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        try {
            List<Future<ServiceResult>> futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            ServiceResult result = raceService.createTeam(raceId,
                                    new CreateTeamRequest("T1", List.of("a", "b", "c"),
                                            4, "same-team-key"));
                            success.incrementAndGet();
                            return result;
                        } catch (RuntimeException ex) {
                            errors.incrementAndGet();
                            throw ex;
                        }
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            for (Future<ServiceResult> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS).status()).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        assertThat(success.get()).isEqualTo(threads);
        assertThat(repository.findTeams(raceId)).hasSize(1);
        assertThat(repository.findRace(raceId).orElseThrow().version()).isEqualTo(5);
    }
}
