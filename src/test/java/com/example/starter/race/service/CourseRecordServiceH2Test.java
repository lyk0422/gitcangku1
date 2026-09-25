package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ClaimRecordRequest;
import com.example.starter.race.api.CourseRecordResponse;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RecordHistoryResponse;
import com.example.starter.race.api.RegisterCourseRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 赛道纪录认定的 H2 数据库测试：前置条件、原子替换、历史链保留与幂等边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class CourseRecordServiceH2Test extends AbstractRaceH2Test {

    private static final String COURSE = "course-rec";

    @Autowired
    private RaceService raceService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @BeforeEach
    void registerDefaultCourse() {
        raceService.registerCourse(new RegisterCourseRequest(COURSE, "req-course"));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 登记赛事、登记一名带完赛计时的选手并封榜。 */
    private void seedSealedRace(String raceId, String bib, long timeMs) {
        raceService.createRace(new CreateRaceRequest(raceId, COURSE, "req-create-" + raceId));
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest(bib, timeMs, 1, "req-reg-" + raceId));
        raceService.sealRace(raceId, new SealRaceRequest(2, "req-seal-" + raceId));
    }

    private ServiceResult claim(String courseKey, String claimKey, String raceId, String bib,
            String requestId) {
        return raceService.claimRecord(courseKey,
                new ClaimRecordRequest(claimKey, raceId, bib, requestId));
    }

    @Test
    void 主流程_无纪录时合法完赛计时认定成功且可查询() {
        seedSealedRace("race-1", "a", 1000L);

        ServiceResult result = claim(COURSE, "claim-1", "race-1", "a", "req-claim-1");

        assertThat(result.status()).isEqualTo(201);
        CourseRecordResponse record = (CourseRecordResponse) result.body();
        assertThat(record.courseKey()).isEqualTo(COURSE);
        assertThat(record.raceId()).isEqualTo("race-1");
        assertThat(record.bib()).isEqualTo("a");
        assertThat(record.timeMs()).isEqualTo(1000L);
        assertThat(record.recordClaimKey()).isEqualTo("claim-1");
        assertThat(record.createdAt())
                .isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());

        CourseRecordResponse current = raceService.getCurrentRecord(COURSE);
        assertThat(current).isEqualTo(record);

        RecordHistoryResponse history = raceService.getRecordHistory(COURSE);
        assertThat(history.courseKey()).isEqualTo(COURSE);
        assertThat(history.records()).containsExactly(record);
    }

    @Test
    void 更优认定原子替换当前纪录且旧纪录保留在历史链() {
        seedSealedRace("race-1", "a", 1000L);
        seedSealedRace("race-2", "b", 900L);
        claim(COURSE, "claim-1", "race-1", "a", "req-claim-1");

        ServiceResult second = claim(COURSE, "claim-2", "race-2", "b", "req-claim-2");

        assertThat(second.status()).isEqualTo(201);
        CourseRecordResponse current = raceService.getCurrentRecord(COURSE);
        assertThat(current.timeMs()).isEqualTo(900L);
        assertThat(current.raceId()).isEqualTo("race-2");

        RecordHistoryResponse history = raceService.getRecordHistory(COURSE);
        assertThat(history.records()).hasSize(2);
        assertThat(history.records()).extracting(CourseRecordResponse::timeMs)
                .containsExactly(1000L, 900L);
        assertThat(history.records()).extracting(CourseRecordResponse::raceId)
                .containsExactly("race-1", "race-2");
        // 历史链最后一条即当前纪录
        assertThat(history.records().getLast()).isEqualTo(current);
    }

    @Test
    void 不严格更优的认定返回422并携带实际当前纪录() {
        seedSealedRace("race-1", "a", 900L);
        seedSealedRace("race-2", "b", 900L);
        seedSealedRace("race-3", "c", 950L);
        claim(COURSE, "claim-1", "race-1", "a", "req-claim-1");

        // 相等不算严格更优
        assertThatThrownBy(() -> claim(COURSE, "claim-2", "race-2", "b", "req-claim-2"))
                .isInstanceOfSatisfying(RecordNotBetterException.class, ex -> {
                    assertThat(ex.currentRecord().timeMs()).isEqualTo(900L);
                    assertThat(ex.currentRecord().raceId()).isEqualTo("race-1");
                });
        // 更差同样拒绝
        assertThatThrownBy(() -> claim(COURSE, "claim-3", "race-3", "c", "req-claim-3"))
                .isInstanceOf(RecordNotBetterException.class);

        // 失败的认定不改变当前纪录与历史链
        assertThat(raceService.getCurrentRecord(COURSE).timeMs()).isEqualTo(900L);
        assertThat(raceService.getRecordHistory(COURSE).records()).hasSize(1);
    }

    @Test
    void 认定前置条件_未登记赛道与未封榜赛事与跨赛道赛事() {
        seedSealedRace("race-1", "a", 1000L);
        raceService.registerCourse(new RegisterCourseRequest("course-other", "req-course-2"));
        raceService.createRace(new CreateRaceRequest("race-open", COURSE, "req-create-open"));
        raceService.registerRunner("race-open",
                new RegisterRunnerRequest("a", 800L, 1, "req-reg-open"));

        // 赛道未登记
        assertThatThrownBy(() -> claim("ghost-course", "c1", "race-1", "a", "req-c1"))
                .isInstanceOf(NotFoundException.class);
        // 赛事不存在
        assertThatThrownBy(() -> claim(COURSE, "c2", "ghost-race", "a", "req-c2"))
                .isInstanceOf(NotFoundException.class);
        // 赛事未封榜
        assertThatThrownBy(() -> claim(COURSE, "c3", "race-open", "a", "req-c3"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        // 赛事不属于该赛道
        assertThatThrownBy(() -> claim("course-other", "c4", "race-1", "a", "req-c4"))
                .isInstanceOf(BadRequestException.class);
        // 创建赛事引用未登记赛道
        assertThatThrownBy(() -> raceService.createRace(
                new CreateRaceRequest("race-x", "ghost-course", "req-create-x")))
                .isInstanceOf(NotFoundException.class);
        // 查询未登记赛道
        assertThatThrownBy(() -> raceService.getCurrentRecord("ghost-course"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.getRecordHistory("ghost-course"))
                .isInstanceOf(NotFoundException.class);
        // 已登记但尚无纪录
        assertThatThrownBy(() -> raceService.getCurrentRecord("course-other"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("尚无纪录");
        assertThat(raceService.getRecordHistory("course-other").records()).isEmpty();
    }

    @Test
    void 认定前置条件_选手不在快照或已取消资格或无有效计时() {
        // race-1: a 完赛 1000ms，b 取消资格，c 计时缺失
        raceService.createRace(new CreateRaceRequest("race-1", COURSE, "req-create-1"));
        raceService.registerRunner("race-1",
                new RegisterRunnerRequest("a", 1000L, 1, "req-reg-a"));
        raceService.registerRunner("race-1",
                new RegisterRunnerRequest("b", 500L, 2, "req-reg-b"));
        raceService.registerRunner("race-1",
                new RegisterRunnerRequest("c", null, 3, "req-reg-c"));
        raceService.addPenalty("race-1",
                new AddPenaltyRequest("p-dq", "b", "DISQUALIFY", null, 4, "req-pen-dq"));
        raceService.sealRace("race-1", new SealRaceRequest(5, "req-seal-1"));

        // 选手不在封榜快照中
        assertThatThrownBy(() -> claim(COURSE, "c1", "race-1", "ghost", "req-c1"))
                .isInstanceOf(NotFoundException.class);
        // 已取消资格
        assertThatThrownBy(() -> claim(COURSE, "c2", "race-1", "b", "req-c2"))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("取消资格");
        // 计时缺失（UNTIMED）
        assertThatThrownBy(() -> claim(COURSE, "c3", "race-1", "c", "req-c3"))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("计时");

        // 全部失败，赛道仍无纪录
        assertThat(raceService.getRecordHistory(COURSE).records()).isEmpty();
    }

    @Test
    void 认定计时取封榜快照最终总耗时含生效加时() {
        raceService.createRace(new CreateRaceRequest("race-1", COURSE, "req-create-1"));
        raceService.registerRunner("race-1",
                new RegisterRunnerRequest("a", 1000L, 1, "req-reg-a"));
        raceService.addPenalty("race-1",
                new AddPenaltyRequest("p-add", "a", "ADD_TIME", 250L, 2, "req-pen-add"));
        raceService.sealRace("race-1", new SealRaceRequest(3, "req-seal-1"));

        ServiceResult result = claim(COURSE, "claim-1", "race-1", "a", "req-claim-1");

        CourseRecordResponse record = (CourseRecordResponse) result.body();
        assertThat(record.timeMs()).isEqualTo(1250L);
    }

    @Test
    void 同一recordClaimKey重复申请幂等返回首次结果且历史链不增长() {
        seedSealedRace("race-1", "a", 1000L);
        ServiceResult first = claim(COURSE, "claim-dup", "race-1", "a", "req-claim-1");

        // 相同 recordClaimKey、不同 requestId 的重复申请
        ServiceResult replay = claim(COURSE, "claim-dup", "race-1", "a", "req-claim-2");

        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        assertThat(raceService.getRecordHistory(COURSE).records()).hasSize(1);
    }

    @Test
    void 认定requestId幂等_同键同参重放_异参409_失败不占键() {
        seedSealedRace("race-1", "a", 1000L);
        seedSealedRace("race-2", "b", 1200L);
        seedSealedRace("race-3", "c", 900L);

        // 同键同参重放首次结果
        ClaimRecordRequest request = new ClaimRecordRequest("claim-1", "race-1", "a", "req-c1");
        ServiceResult first = raceService.claimRecord(COURSE, request);
        ServiceResult replay = raceService.claimRecord(COURSE, request);
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        assertThat(raceService.getRecordHistory(COURSE).records()).hasSize(1);

        // 同键异参 409
        assertThatThrownBy(() -> raceService.claimRecord(COURSE,
                new ClaimRecordRequest("claim-2", "race-1", "a", "req-c1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // 失败（不更优）不占 requestId 键：修正参数后同键重试成功
        assertThatThrownBy(() -> claim(COURSE, "claim-3", "race-2", "b", "req-c3"))
                .isInstanceOf(RecordNotBetterException.class);
        assertThat(repository.findIdempotency("req-c3")).isEmpty();
        ServiceResult retry = claim(COURSE, "claim-3", "race-3", "c", "req-c3");
        assertThat(retry.status()).isEqualTo(201);
        assertThat(raceService.getCurrentRecord(COURSE).timeMs()).isEqualTo(900L);
    }

    @Test
    void 查询当前纪录与历史链为只读不触发认定() {
        seedSealedRace("race-1", "a", 1000L);
        claim(COURSE, "claim-1", "race-1", "a", "req-claim-1");

        CourseRecordResponse before = raceService.getCurrentRecord(COURSE);
        RecordHistoryResponse historyBefore = raceService.getRecordHistory(COURSE);

        // 重复只读查询后状态不变
        assertThat(raceService.getCurrentRecord(COURSE)).isEqualTo(before);
        assertThat(raceService.getRecordHistory(COURSE).records())
                .isEqualTo(historyBefore.records());
        assertThat(repository.findCourseRecords(COURSE)).hasSize(1);
    }
}
