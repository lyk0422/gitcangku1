package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.ConfigureInspectionRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.EquipmentBindingsResponse;
import com.example.starter.race.api.InspectionHistoryResponse;
import com.example.starter.race.api.InspectionRecordResponse;
import com.example.starter.race.api.InspectionStatusResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RunnerRaceStateResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StartRunnerRequest;
import com.example.starter.race.api.SubmitInspectionRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.MutableClockTestConfig;
import com.example.starter.race.support.MutableClockTestConfig.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 器材检录能力的 H2 数据库测试：检录有效期、起跑门禁、FAIL阻断与复检替换、
 * 器材绑定409与退赛/取消资格/完赛释放、首个分段计时门禁、查询与幂等边界。
 */
@SpringBootTest
@Import(MutableClockTestConfig.class)
class InspectionServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-inspection";

    @Autowired
    private RaceService raceService;

    @Autowired
    private MutableClock clock;

    /** 建赛、登记两名未完赛选手（v3）并配置强制检录10分钟（v4）。 */
    private void seedMandatoryRace() {
        clock.setInstant(Instant.parse("2026-09-25T00:00:00Z"));
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-a"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", null, 2, "req-b"));
        raceService.configureInspection(RACE,
                new ConfigureInspectionRequest(true, 10, 3, "req-cfg"));
    }

    private ServiceResult pass(String bib, String key, String serial, int version) {
        return raceService.submitInspection(RACE, bib,
                new SubmitInspectionRequest(key, serial, "PASS", version, "req-" + key));
    }

    private ServiceResult fail(String bib, String key, String serial, int version) {
        return raceService.submitInspection(RACE, bib,
                new SubmitInspectionRequest(key, serial, "FAIL", version, "req-" + key));
    }

    @Test
    void 无检录与FAIL与过期PASS均被起跑门禁422拒绝且复检PASS放行() {
        seedMandatoryRace();

        // 无任何检录 → 422，不写入起跑状态
        assertThatThrownBy(() -> raceService.startRunner(RACE, "a",
                new StartRunnerRequest(4, "req-start-a-0")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不存在");
        assertThat(raceService.getRunnerState(RACE, "a").state()).isEqualTo("REGISTERED");
        assertThat(raceService.getInspectionStatus(RACE, "a").effective()).isEqualTo("NONE");

        // FAIL 立即阻断（FAIL 为 v5，起跑须携带当前版本）
        ServiceResult failResult = fail("a", "k-fail", "S1", 4);
        assertThat(failResult.status()).isEqualTo(201);
        assertThat(raceService.getInspectionStatus(RACE, "a").effective()).isEqualTo("FAIL");
        assertThatThrownBy(() -> raceService.startRunner(RACE, "a",
                new StartRunnerRequest(5, "req-start-a-1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("FAIL");

        // 复检 PASS：放行；历史保留 FAIL 与 PASS 两条
        ServiceResult passResult = pass("a", "k-pass", "S1", 5);
        assertThat(passResult.status()).isEqualTo(201);
        assertThat(raceService.getInspectionStatus(RACE, "a").effective()).isEqualTo("PASS_VALID");
        ServiceResult started = raceService.startRunner(RACE, "a",
                new StartRunnerRequest(6, "req-start-a-2"));
        assertThat(started.status()).isEqualTo(200);
        assertThat(((RunnerRaceStateResponse) started.body()).state()).isEqualTo("STARTED");

        InspectionHistoryResponse history = raceService.getInspectionHistory(RACE, "a");
        assertThat(history.records()).extracting(InspectionRecordResponse::result)
                .containsExactly("FAIL", "PASS");
        assertThat(history.records()).extracting(InspectionRecordResponse::inspectionId)
                .containsExactly("k-fail", "k-pass");

        // 重复起跑 → 409
        assertThatThrownBy(() -> raceService.startRunner(RACE, "a",
                new StartRunnerRequest(7, "req-start-a-3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已起跑");

        // b 的 PASS 10分钟后过期：截止时刻含端点，超过即422，不写入
        pass("b", "k-b-pass", "S2", 7);
        clock.advanceMinutes(10);
        assertThat(raceService.getInspectionStatus(RACE, "b").effective())
                .isEqualTo("PASS_VALID");
        clock.advanceSeconds(1);
        assertThat(raceService.getInspectionStatus(RACE, "b").effective())
                .isEqualTo("EXPIRED");
        assertThatThrownBy(() -> raceService.startRunner(RACE, "b",
                new StartRunnerRequest(8, "req-start-b-late")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("过期");
        assertThat(raceService.getRunnerState(RACE, "b").state()).isEqualTo("REGISTERED");
    }

    @Test
    void 非强制赛事不受门禁影响且FAIL不阻断() {
        clock.setInstant(Instant.parse("2026-09-25T00:00:00Z"));
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-a"));

        // 未配置检录：直接起跑成功
        ServiceResult started = raceService.startRunner(RACE, "a",
                new StartRunnerRequest(2, "req-start"));
        assertThat(started.status()).isEqualTo(200);
        assertThat(raceService.getInspectionStatus(RACE, "a").effective())
                .isEqualTo("NOT_REQUIRED");

        // 显式配置为非强制：另一名选手即使 FAIL 也可起跑
        raceService.registerRunner(RACE, new RegisterRunnerRequest("b", null, 3, "req-b"));
        raceService.configureInspection(RACE,
                new ConfigureInspectionRequest(false, 30, 4, "req-cfg-off"));
        fail("b", "k-b-fail", "S9", 5);
        assertThat(raceService.getInspectionStatus(RACE, "b").effective())
                .isEqualTo("NOT_REQUIRED");
        ServiceResult bStarted = raceService.startRunner(RACE, "b",
                new StartRunnerRequest(6, "req-start-b"));
        assertThat(bStarted.status()).isEqualTo(200);
    }

    @Test
    void 同器材二次PASS返回409退赛后释放绑定() {
        seedMandatoryRace();
        pass("a", "k-a", "SERIAL-X", 4);
        assertThat(raceService.getEquipmentBindings(RACE).bindings())
                .extracting(b -> b.equipmentSerial() + ":" + b.bib())
                .containsExactly("SERIAL-X:a");

        // b 用同一器材 PASS → 409（a 的 PASS 已把版本推进到5），b 无绑定无历史成功
        assertThatThrownBy(() -> pass("b", "k-b", "SERIAL-X", 5))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已绑定另一名未完赛选手");
        assertThat(raceService.getInspectionHistory(RACE, "b").records()).isEmpty();
        assertThat(raceService.getEquipmentBindings(RACE).bindings()).hasSize(1);

        // a 退赛释放，b 复检 PASS 成功
        ServiceResult withdrawn = raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest("受伤", 5, "req-withdraw-a"));
        assertThat(withdrawn.status()).isEqualTo(200);
        assertThat(((RunnerRaceStateResponse) withdrawn.body()).state())
                .isEqualTo("WITHDRAWN");
        ServiceResult bPass = pass("b", "k-b", "SERIAL-X", 6);
        assertThat(bPass.status()).isEqualTo(201);
        assertThat(raceService.getEquipmentBindings(RACE).bindings())
                .extracting(b -> b.equipmentSerial() + ":" + b.bib())
                .containsExactly("SERIAL-X:b");

        // a 已退赛：起跑与再次检录均拒绝
        assertThatThrownBy(() -> raceService.startRunner(RACE, "a",
                new StartRunnerRequest(7, "req-start-a")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest("再次退赛", 7, "req-withdraw-a2")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 取消资格与完赛均释放器材绑定() {
        seedMandatoryRace();
        // a 占 SERIAL-DQ，被取消资格后 c 可绑定
        pass("a", "k-a-dq", "SERIAL-DQ", 4);
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "pen-dq", "a", "DISQUALIFY", null, 5, "req-dq"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("c", null, 6, "req-c"));
        ServiceResult cPass = pass("c", "k-c-dq", "SERIAL-DQ", 7);
        assertThat(cPass.status()).isEqualTo(201);

        // d 先以未完赛登记并绑定 SERIAL-FIN，补录完赛耗时（完赛）后 e 可绑定
        raceService.registerRunner(RACE, new RegisterRunnerRequest("d", null, 8, "req-d"));
        pass("d", "k-d-fin", "SERIAL-FIN", 9);
        raceService.reviseTime(RACE, new ReviseTimeRequest("d", 10_000L, 10, "req-fin-d"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("e", null, 11, "req-e"));
        ServiceResult ePass = pass("e", "k-e-fin", "SERIAL-FIN", 12);
        assertThat(ePass.status()).isEqualTo(201);

        EquipmentBindingsResponse bindings = raceService.getEquipmentBindings(RACE);
        assertThat(bindings.bindings()).extracting(b -> b.equipmentSerial() + ":" + b.bib())
                .containsExactlyInAnyOrder("SERIAL-DQ:c", "SERIAL-FIN:e");
    }

    @Test
    void 首个分段计时前同样执行检录门禁() {
        clock.setInstant(Instant.parse("2026-09-25T00:00:00Z"));
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", 5_000L, 1, "req-a"));
        raceService.configureInspection(RACE,
                new ConfigureInspectionRequest(true, 10, 2, "req-cfg"));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("p1", 1)),
                3, "req-cp"));

        // 未检录：首个分段计时 422 且无任何计时写入，选手仍未起跑
        assertThatThrownBy(() -> raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t1", "p1", 1_000L, 4, "req-t1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("不存在");
        assertThat(raceService.getRunnerState(RACE, "a").state()).isEqualTo("REGISTERED");

        // 检录 PASS 后首个分段计时被接受并隐式置为已起跑
        pass("a", "k-a", "S1", 4);
        ServiceResult timing = raceService.submitTiming(RACE, "a",
                new SubmitTimingRequest("t1", "p1", 1_000L, 5, "req-t1-real"));
        assertThat(timing.status()).isEqualTo(201);
        assertThat(raceService.getRunnerState(RACE, "a").state()).isEqualTo("STARTED");
    }

    @Test
    void 检录键同参重放异参冲突且失败不占键() {
        seedMandatoryRace();
        SubmitInspectionRequest first =
                new SubmitInspectionRequest("dup-key", "S1", "PASS", 4, "req-ins");
        ServiceResult r1 = raceService.submitInspection(RACE, "a", first);
        ServiceResult r2 = raceService.submitInspection(RACE, "a", first);
        assertThat(r2.status()).isEqualTo(r1.status());
        assertThat(raceService.getInspectionHistory(RACE, "a").records()).hasSize(1);

        // 同 inspectionKey 异参 → 409
        assertThatThrownBy(() -> raceService.submitInspection(RACE, "a",
                new SubmitInspectionRequest("dup-key", "S2", "PASS", 4, "req-ins-other")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("inspectionKey");
        // 换个选手同键也算异参
        assertThatThrownBy(() -> raceService.submitInspection(RACE, "b",
                new SubmitInspectionRequest("dup-key", "S1", "PASS", 4, "req-ins-b")))
                .isInstanceOf(ConflictException.class);

        // 业务失败（器材409）不占 inspectionKey：a 已占 S7，b 首次失败，释放后同键可成功
        pass("a", "k-a7", "S7", 5);
        assertThatThrownBy(() -> pass("b", "retry-key", "S7", 6))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findInspection("retry-key")).isEmpty();
        raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest(null, 6, "req-wd-a"));
        ServiceResult retry = pass("b", "retry-key", "S7", 7);
        assertThat(retry.status()).isEqualTo(201);
    }

    @Test
    void requestId失败不占键且起跑成功可同键重放() {
        seedMandatoryRace();
        // 起跑因无 PASS 失败（422），requestId 不被占用；检录后同键起跑成功
        assertThatThrownBy(() -> raceService.startRunner(RACE, "a",
                new StartRunnerRequest(4, "req-start-same")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(repository.findIdempotency("req-start-same")).isEmpty();
        pass("a", "k-a", "S1", 4);
        ServiceResult started = raceService.startRunner(RACE, "a",
                new StartRunnerRequest(5, "req-start-same"));
        ServiceResult replay = raceService.startRunner(RACE, "a",
                new StartRunnerRequest(5, "req-start-same"));
        assertThat(replay.status()).isEqualTo(started.status());
    }

    @Test
    void 登记时已申报完赛耗时的选手不占用器材绑定() {
        seedMandatoryRace();
        // a 修订补录完赛耗时（已完赛），其 PASS 不占用器材，b 可绑定同一序列号
        raceService.reviseTime(RACE, new ReviseTimeRequest("a", 9_000L, 4, "req-fin-a"));
        ServiceResult aPass = pass("a", "k-a-fin", "SERIAL-Z", 5);
        assertThat(aPass.status()).isEqualTo(201);
        assertThat(raceService.getEquipmentBindings(RACE).bindings())
                .noneMatch(b -> b.equipmentSerial().equals("SERIAL-Z"));
        ServiceResult bPass = pass("b", "k-b-z", "SERIAL-Z", 6);
        assertThat(bPass.status()).isEqualTo(201);
        assertThat(raceService.getEquipmentBindings(RACE).bindings())
                .extracting(b -> b.equipmentSerial() + ":" + b.bib())
                .containsExactly("SERIAL-Z:b");
    }

    @Test
    void 封榜后检录与起跑均409() {
        seedMandatoryRace();
        raceService.sealRace(RACE, new SealRaceRequest(4, "req-seal"));
        assertThatThrownBy(() -> raceService.configureInspection(RACE,
                new ConfigureInspectionRequest(true, 5, 5, "req-cfg2")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> pass("a", "k-a", "S1", 5))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.startRunner(RACE, "a",
                new StartRunnerRequest(5, "req-start")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 配置参数越界与未配置赛事提交检录返回对应错误() {
        clock.setInstant(Instant.parse("2026-09-25T00:00:00Z"));
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE, new RegisterRunnerRequest("a", null, 1, "req-a"));
        // 有效分钟数越界
        assertThatThrownBy(() -> raceService.configureInspection(RACE,
                new ConfigureInspectionRequest(true, 1441, 2, "req-cfg-bad")))
                .isInstanceOf(BadRequestException.class);
        // 未配置检录的赛事提交检录 → 422
        assertThatThrownBy(() -> pass("a", "k-a", "S1", 2))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("尚未配置");
    }
}
