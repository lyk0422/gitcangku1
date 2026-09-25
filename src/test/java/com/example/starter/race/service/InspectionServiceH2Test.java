package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.EquipmentBindingsResponse;
import com.example.starter.race.api.InspectionHistoryResponse;
import com.example.starter.race.api.InspectionResponse;
import com.example.starter.race.api.InspectionStatusResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.StartRunnerRequest;
import com.example.starter.race.api.SubmitInspectionRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.InspectionResult;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.MutableClock;
import com.example.starter.race.support.MutableClockTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 器材检录与起跑拦截的 H2 数据库测试：有效期、起跑门禁、复检替换、
 * 器材绑定冲突/释放、幂等重放及首个分段门禁。
 */
@SpringBootTest
@Import(MutableClockTestConfig.class)
class InspectionServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-insp";
    private static final int VALID_MINUTES = 1;

    @Autowired
    private RaceService raceService;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.setInstant(MutableClockTestConfig.INITIAL_INSTANT);
    }

    /** 建强制检录赛事（v1）并登记无成绩选手（v2）。 */
    private void setupMandatoryRaceWithRunner(String bib) {
        raceService.createRace(new CreateRaceRequest(
                RACE, true, VALID_MINUTES, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, null, 1, "req-reg-" + bib));
    }

    private SubmitInspectionRequest pass(String key, String serial, int expectedVersion,
            String requestId) {
        return new SubmitInspectionRequest(key, serial, "PASS", expectedVersion, requestId);
    }

    private SubmitInspectionRequest fail(String key, String serial, int expectedVersion,
            String requestId) {
        return new SubmitInspectionRequest(key, serial, "FAIL", expectedVersion, requestId);
    }

    @Test
    void 强制赛事_pass有效期内起跑成功并写入起跑记录() {
        setupMandatoryRaceWithRunner("a");
        long t0 = clock.millis();

        ServiceResult insp = raceService.submitInspection(RACE, "a",
                pass("insp-1", "bike-1", 2, "req-insp-1"));
        InspectionResponse body = (InspectionResponse) insp.body();
        assertThat(insp.status()).isEqualTo(201);
        assertThat(body.result()).isEqualTo(InspectionResult.PASS);
        assertThat(body.validUntil()).isEqualTo(t0 + 60_000L);

        // v3：检录，v4：起跑
        ServiceResult start = raceService.startRunner(RACE, "a",
                new StartRunnerRequest("start-1", 3, "req-start-1"));
        assertThat(start.status()).isEqualTo(201);
        assertThat(repository.findStartForRunner(RACE, "a")).isPresent();
    }

    @Test
    void pass过期后起跑422且不写计时_复检pass后可起跑() {
        setupMandatoryRaceWithRunner("a");
        raceService.submitInspection(RACE, "a", pass("insp-1", "bike-1", 2, "req-insp-1"));

        clock.advanceMillis(60_000L);
        assertThatThrownBy(() -> raceService.startRunner(RACE, "a",
                new StartRunnerRequest("start-1", 3, "req-start-1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("已过期");
        // 422 不推进版本、不写起跑
        assertThat(repository.findStartForRunner(RACE, "a")).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);

        // 复检 PASS（仍 v3），替换过期 PASS；起跑成功（v5）
        raceService.submitInspection(RACE, "a", pass("insp-2", "bike-1", 3, "req-insp-2"));
        ServiceResult start = raceService.startRunner(RACE, "a",
                new StartRunnerRequest("start-1", 4, "req-start-2"));
        assertThat(start.status()).isEqualTo(201);
        // 历史不可变：两条都保留
        assertThat(repository.findInspectionsForRunner(RACE, "a")).hasSize(2);
    }

    @Test
    void 最新fail立即阻断起跑_复检pass替换后放行且旧记录保留() {
        setupMandatoryRaceWithRunner("a");
        // PASS(v3) 后 FAIL(v4)，最新为 FAIL
        raceService.submitInspection(RACE, "a", pass("insp-p", "bike-1", 2, "req-insp-p"));
        raceService.submitInspection(RACE, "a", fail("insp-f", "bike-1", 3, "req-insp-f"));

        assertThatThrownBy(() -> raceService.startRunner(RACE, "a",
                new StartRunnerRequest("start-1", 4, "req-start-1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("FAIL");

        InspectionStatusResponse status = raceService.getInspectionStatus(RACE, "a");
        assertThat(status.result()).isEqualTo(InspectionResult.FAIL);
        assertThat(status.valid()).isFalse();

        // 复检 PASS(v5) 替换 FAIL
        raceService.submitInspection(RACE, "a", pass("insp-p2", "bike-1", 4, "req-insp-p2"));
        ServiceResult start = raceService.startRunner(RACE, "a",
                new StartRunnerRequest("start-1", 5, "req-start-2"));
        assertThat(start.status()).isEqualTo(201);
        // 不可变历史保留全部三条
        assertThat(repository.findInspectionsForRunner(RACE, "a")).hasSize(3);
        assertThat(raceService.getInspectionStatus(RACE, "a").valid()).isTrue();
    }

    @Test
    void 无任何检录起跑422() {
        setupMandatoryRaceWithRunner("a");
        assertThatThrownBy(() -> raceService.startRunner(RACE, "a",
                new StartRunnerRequest("start-1", 2, "req-start-1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("无检录记录");
        assertThat(repository.findStartForRunner(RACE, "a")).isEmpty();
    }

    @Test
    void 非强制赛事无检录可直接起跑且不查pass() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", null, 1, "req-reg-a"));
        ServiceResult start = raceService.startRunner(RACE, "a",
                new StartRunnerRequest("start-1", 2, "req-start-1"));
        assertThat(start.status()).isEqualTo(201);
    }

    @Test
    void 同一器材绑定两个未完赛选手第二次pass返回409() {
        setupMandatoryRaceWithRunner("a");
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", null, 2, "req-reg-b")); // v3

        raceService.submitInspection(RACE, "a", pass("insp-a", "bike-X", 3, "req-insp-a")); // v4
        assertThatThrownBy(() -> raceService.submitInspection(RACE, "b",
                pass("insp-b", "bike-X", 4, "req-insp-b")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已被未完赛选手绑定");
        // b 的检录历史仍记录本次失败前不写入；绑定清单只有 a
        EquipmentBindingsResponse bindings = raceService.getEquipmentBindings(RACE);
        assertThat(bindings.entries()).hasSize(1);
        assertThat(bindings.entries().getFirst().bib()).isEqualTo("a");
    }

    @Test
    void 首名选手退赛后释放器材_第二人pass成功() {
        setupMandatoryRaceWithRunner("a");
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", null, 2, "req-reg-b")); // v3
        raceService.submitInspection(RACE, "a", pass("insp-a", "bike-X", 3, "req-insp-a")); // v4

        // a 退赛（v5），释放绑定
        raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest(4, "req-withdraw-a"));
        assertThat(raceService.getEquipmentBindings(RACE).entries()).isEmpty();

        // b PASS 同一器材（v6）成功接管
        ServiceResult insp = raceService.submitInspection(RACE, "b",
                pass("insp-b", "bike-X", 5, "req-insp-b"));
        assertThat(insp.status()).isEqualTo(201);
        assertThat(raceService.getEquipmentBindings(RACE).entries().getFirst().bib())
                .isEqualTo("b");

        // a 已退赛不能再起跑
        assertThatThrownBy(() -> raceService.startRunner(RACE, "a",
                new StartRunnerRequest("start-a", 6, "req-start-a")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("退赛");
    }

    @Test
    void 取消资格与完赛释放器材绑定() {
        // a 取消资格释放
        setupMandatoryRaceWithRunner("a");
        raceService.submitInspection(RACE, "a", pass("insp-a", "bike-DQ", 2, "req-insp-a")); // v3
        raceService.addPenalty(RACE, new com.example.starter.race.api.AddPenaltyRequest(
                "pen-dq", "a", "DISQUALIFY", null, 3, "req-pen-dq")); // v4
        assertThat(raceService.getEquipmentBindings(RACE).entries()).isEmpty();

        // 新赛事：c 录入完赛耗时后释放
        String race2 = "race-insp-finish";
        raceService.createRace(new CreateRaceRequest(race2, true, VALID_MINUTES, "req-create2"));
        raceService.registerRunner(race2,
                new RegisterRunnerRequest("c", null, 1, "req-reg-c")); // v2
        raceService.submitInspection(race2, "c", pass("insp-c", "bike-F", 2, "req-insp-c")); // v3
        raceService.reviseTime(race2,
                new com.example.starter.race.api.ReviseTimeRequest("c", 5_000L, 3, "rev-c")); // v4
        assertThat(raceService.getEquipmentBindings(race2).entries()).isEmpty();
    }

    @Test
    void inspectionKey同参重放首次结果_异参409() {
        setupMandatoryRaceWithRunner("a");
        SubmitInspectionRequest first = pass("key-1", "bike-1", 2, "req-1");
        ServiceResult r1 = raceService.submitInspection(RACE, "a", first);
        ServiceResult r2 = raceService.submitInspection(RACE, "a", first);
        assertThat(r2.status()).isEqualTo(r1.status());
        assertThat(repository.findInspectionsForRunner(RACE, "a")).hasSize(1);

        // 同键异参（不同器材序列号）→ 409，且不新增历史
        assertThatThrownBy(() -> raceService.submitInspection(RACE, "a",
                pass("key-1", "bike-OTHER", 2, "req-2")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findInspectionsForRunner(RACE, "a")).hasSize(1);
    }

    @Test
    void 业务失败不占用inspectionKey_失败后同键可成功() {
        setupMandatoryRaceWithRunner("a");
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", null, 2, "req-reg-b")); // v3
        raceService.submitInspection(RACE, "a", pass("insp-a", "bike-X", 3, "req-insp-a")); // v4

        // b 用 key-reuse 抢占已绑定器材 → 409 回滚，不占键
        assertThatThrownBy(() -> raceService.submitInspection(RACE, "b",
                pass("key-reuse", "bike-X", 4, "req-fail")))
                .isInstanceOf(ConflictException.class);
        // a 退赛释放后，b 复用同一 inspectionKey 与 requestId 之外的键成功
        raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest(4, "req-withdraw-a")); // v5
        ServiceResult ok = raceService.submitInspection(RACE, "b",
                pass("key-reuse", "bike-X", 5, "req-ok"));
        assertThat(ok.status()).isEqualTo(201);
    }

    @Test
    void requestId同键同参重放_异参409_失败不占键() {
        setupMandatoryRaceWithRunner("a");
        SubmitInspectionRequest req = pass("insp-1", "bike-1", 2, "shared-rid");
        ServiceResult r1 = raceService.submitInspection(RACE, "a", req);
        ServiceResult replay = raceService.submitInspection(RACE, "a", req);
        assertThat(replay.status()).isEqualTo(r1.status());

        // 同 requestId 异参 → 409
        assertThatThrownBy(() -> raceService.submitInspection(RACE, "a",
                pass("insp-2", "bike-2", 2, "shared-rid")))
                .isInstanceOf(ConflictException.class);

        // 失败请求不占 requestId：用另一个会失败的请求（已绑定器材冲突），随后同 requestId 成功
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", null, 3, "req-reg-b")); // v4
        String ridFail = "rid-fail";
        assertThatThrownBy(() -> raceService.submitInspection(RACE, "b",
                pass("insp-b-fail", "bike-1", 4, ridFail)))
                .isInstanceOf(ConflictException.class);
        // 失败不占键：同 requestId 改用于 b 自己的新器材 PASS（版本仍4）成功
        ServiceResult reused = raceService.submitInspection(RACE, "b",
                pass("insp-b-ok", "bike-2", 4, ridFail));
        assertThat(reused.status()).isEqualTo(201);
    }

    @Test
    void 重复起跑冲突_退赛后不能起跑() {
        setupMandatoryRaceWithRunner("a");
        raceService.submitInspection(RACE, "a", pass("insp-1", "bike-1", 2, "req-insp-1")); // v3
        raceService.startRunner(RACE, "a",
                new StartRunnerRequest("start-1", 3, "req-start-1")); // v4
        assertThatThrownBy(() -> raceService.startRunner(RACE, "a",
                new StartRunnerRequest("start-2", 4, "req-start-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已起跑");
    }

    @Test
    void 首个分段计时同样受检录门禁_无pass返回422() {
        raceService.createRace(new CreateRaceRequest(RACE, true, VALID_MINUTES, "req-create"));
        // 有完赛耗时才能提交分段；登记 a=9000ms
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 9_000L, 1, "req-reg-a")); // v2
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("CP1", 1)),
                2, "req-cp")); // v3

        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-1", "CP1", 1_000L, 3, "req-t-1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("PASS");
        assertThat(repository.countTimings(RACE)).isZero();

        // PASS(v4) 后首个分段成功(v5)
        raceService.submitInspection(RACE, "a", pass("insp-1", "bike-1", 3, "req-insp-1"));
        ServiceResult timing = raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t-1", "CP1", 1_000L, 4, "req-t-1"));
        assertThat(timing.status()).isEqualTo(201);
        assertThat(repository.countTimings(RACE)).isEqualTo(1);
    }

    @Test
    void 查询检录历史_当前状态_器材绑定清单() {
        setupMandatoryRaceWithRunner("a");
        long t0 = clock.millis();
        raceService.submitInspection(RACE, "a", fail("insp-f", "bike-1", 2, "req-f")); // v3

        InspectionHistoryResponse history = raceService.getInspectionHistory(RACE, "a");
        assertThat(history.entries()).hasSize(1);
        assertThat(history.entries().getFirst().result()).isEqualTo(InspectionResult.FAIL);
        assertThat(history.entries().getFirst().validUntil()).isNull();

        clock.advanceMillis(10_000L);
        raceService.submitInspection(RACE, "a", pass("insp-p", "bike-9", 3, "req-p")); // v4
        InspectionStatusResponse status = raceService.getInspectionStatus(RACE, "a");
        assertThat(status.result()).isEqualTo(InspectionResult.PASS);
        assertThat(status.valid()).isTrue();
        assertThat(status.inspectedAt()).isEqualTo(t0 + 10_000L);
        assertThat(status.validUntil()).isEqualTo(t0 + 10_000L + 60_000L);
        assertThat(status.inspectionRequired()).isTrue();

        // FAIL 不建立绑定，只有 PASS 的 bike-9
        List<String> serials = raceService.getEquipmentBindings(RACE).entries().stream()
                .map(b -> b.equipmentSerial()).toList();
        assertThat(serials).containsExactly("bike-9");
    }

    @Test
    void 建赛参数校验_强制缺分钟数或非强制带分钟数报错() {
        assertThatThrownBy(() -> raceService.createRace(
                new CreateRaceRequest("race-bad1", true, null, "rid1")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> raceService.createRace(
                new CreateRaceRequest("race-bad2", false, 30, "rid2")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> raceService.createRace(
                new CreateRaceRequest("race-bad3", true, 0, "rid3")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 状态查询的now字段来自可注入时钟() {
        setupMandatoryRaceWithRunner("a");
        Instant target = Instant.parse("2026-09-25T05:30:00Z");
        clock.setInstant(target);
        InspectionStatusResponse status = raceService.getInspectionStatus(RACE, "a");
        assertThat(status.now()).isEqualTo(target.toEpochMilli());
        assertThat(status.result()).isNull();
        assertThat(status.valid()).isFalse();
    }
}
