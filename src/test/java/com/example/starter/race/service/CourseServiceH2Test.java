package com.example.starter.race.service;

import com.example.starter.race.api.ClaimRecordRequest;
import com.example.starter.race.api.CourseRecordHistoryResponse;
import com.example.starter.race.api.CourseRecordResponse;
import com.example.starter.race.api.CourseResponse;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterCourseRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CourseServiceImpl} 的 H2 数据库测试：赛道登记、纪录认定前置条件、
 * 原子替换、历史链保留与 recordClaimKey/requestId 幂等边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class CourseServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-1";

    @Autowired
    private CourseService courseService;

    @Autowired
    private RaceService raceService;

    @Test
    void 登记赛道初始无纪录且重复登记409() {
        ServiceResult created = courseService.registerCourse(
                new RegisterCourseRequest(COURSE, "req-course"));
        assertThat(created.status()).isEqualTo(201);
        CourseResponse body = (CourseResponse) created.body();
        assertThat(body.courseKey()).isEqualTo(COURSE);
        assertThat(body.currentRecordId()).isNull();
        assertThat(body.createdAt())
                .isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());

        CourseRecordHistoryResponse history = courseService.getRecordHistory(COURSE);
        assertThat(history.current()).isNull();
        assertThat(history.history()).isEmpty();

        assertThatThrownBy(() -> courseService.registerCourse(
                new RegisterCourseRequest(COURSE, "req-course-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("赛道已存在");
    }

    @Test
    void 查询不存在的赛道返回404() {
        assertThatThrownBy(() -> courseService.getCourse("ghost"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> courseService.getRecordHistory("ghost"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> courseService.claimRecord("ghost",
                new ClaimRecordRequest(RACE, "a", "claim-1", "req-claim")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 创建赛事必须关联已登记赛道() {
        assertThatThrownBy(() -> raceService.createRace(
                new CreateRaceRequest(RACE, "ghost-course", "req-create")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("赛道不存在");
        assertThat(repository.findRace(RACE)).isEmpty();
    }

    @Test
    void 无纪录时任何合法完赛计时均可认定并生成首条链节点() {
        seedSealedRaceWithTime(COURSE, RACE, "a", 5000L);

        ServiceResult result = courseService.claimRecord(COURSE,
                new ClaimRecordRequest(RACE, "a", "claim-1", "req-claim-1"));
        assertThat(result.status()).isEqualTo(201);
        CourseRecordResponse record = (CourseRecordResponse) result.body();
        assertThat(record.courseKey()).isEqualTo(COURSE);
        assertThat(record.seq()).isEqualTo(1);
        assertThat(record.raceId()).isEqualTo(RACE);
        assertThat(record.bib()).isEqualTo("a");
        assertThat(record.timeMs()).isEqualTo(5000L);
        assertThat(record.claimedAt())
                .isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());

        CourseResponse course = courseService.getCourse(COURSE);
        assertThat(course.currentRecordId()).isEqualTo(record.recordId());

        CourseRecordHistoryResponse history = courseService.getRecordHistory(COURSE);
        assertThat(history.current()).isEqualTo(record);
        assertThat(history.history()).containsExactly(record);
    }

    @Test
    void 更优认定原子替换当前纪录且旧纪录保留在历史链() {
        seedSealedRaceWithTime(COURSE, RACE, "a", 5000L);
        courseService.claimRecord(COURSE,
                new ClaimRecordRequest(RACE, "a", "claim-1", "req-claim-1"));

        // 另一赛事同赛道更快成绩
        String race2 = "race-2";
        seedSealedRaceWithTime(COURSE, race2, "b", 4000L);
        ServiceResult second = courseService.claimRecord(COURSE,
                new ClaimRecordRequest(race2, "b", "claim-2", "req-claim-2"));
        assertThat(second.status()).isEqualTo(201);
        CourseRecordResponse newRecord = (CourseRecordResponse) second.body();
        assertThat(newRecord.seq()).isEqualTo(2);
        assertThat(newRecord.timeMs()).isEqualTo(4000L);

        CourseRecordHistoryResponse history = courseService.getRecordHistory(COURSE);
        assertThat(history.current()).isEqualTo(newRecord);
        assertThat(history.history()).hasSize(2);
        assertThat(history.history()).extracting(CourseRecordResponse::seq)
                .containsExactly(1, 2);
        assertThat(history.history()).extracting(CourseRecordResponse::timeMs)
                .containsExactly(5000L, 4000L);
        // 旧纪录不可变：首节点仍是原赛事原选手原计时
        assertThat(history.history().getFirst().raceId()).isEqualTo(RACE);
        assertThat(history.history().getFirst().bib()).isEqualTo("a");
    }

    @Test
    void 未严格优于当前纪录返回422并携带实际当前纪录() {
        seedSealedRaceWithTime(COURSE, RACE, "a", 5000L);
        courseService.claimRecord(COURSE,
                new ClaimRecordRequest(RACE, "a", "claim-1", "req-claim-1"));

        String race2 = "race-2";
        // 相等也不算严格更优
        seedSealedRaceWithTime(COURSE, race2, "b", 5000L);
        assertThatThrownBy(() -> courseService.claimRecord(COURSE,
                new ClaimRecordRequest(race2, "b", "claim-2", "req-claim-2")))
                .isInstanceOf(RecordClaimRejectedException.class)
                .satisfies(ex -> {
                    CourseRecordResponse current =
                            ((RecordClaimRejectedException) ex).currentRecord();
                    assertThat(current.timeMs()).isEqualTo(5000L);
                    assertThat(current.bib()).isEqualTo("a");
                });

        String race3 = "race-3";
        seedSealedRaceWithTime(COURSE, race3, "c", 6000L);
        assertThatThrownBy(() -> courseService.claimRecord(COURSE,
                new ClaimRecordRequest(race3, "c", "claim-3", "req-claim-3")))
                .isInstanceOf(RecordClaimRejectedException.class);

        // 失败不产生链节点，当前纪录不变
        CourseRecordHistoryResponse history = courseService.getRecordHistory(COURSE);
        assertThat(history.history()).hasSize(1);
        assertThat(history.current().timeMs()).isEqualTo(5000L);
    }

    @Test
    void 认定前置条件_未封榜_未关联赛道_选手缺失_取消资格_无计时() {
        courseService.registerCourse(new RegisterCourseRequest(COURSE, "req-course"));
        courseService.registerCourse(new RegisterCourseRequest("course-2", "req-course-2"));
        raceService.createRace(new CreateRaceRequest(RACE, COURSE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 1000L, 1, "req-a"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("dq", 2000L, 2, "req-dq"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("untimed", null, 3, "req-untimed"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-dq", "dq", "DISQUALIFY", null, 4, "req-pen-dq"));

        // 未封榜 409
        assertThatThrownBy(() -> courseService.claimRecord(COURSE,
                new ClaimRecordRequest(RACE, "a", "claim-x", "req-claim-x")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("尚未封榜");

        raceService.sealRace(RACE, new SealRaceRequest(5, "req-seal"));

        // 赛事不存在 404
        assertThatThrownBy(() -> courseService.claimRecord(COURSE,
                new ClaimRecordRequest("ghost-race", "a", "claim-0", "req-claim-0")))
                .isInstanceOf(NotFoundException.class);
        // 赛事不属于该赛道 400
        assertThatThrownBy(() -> courseService.claimRecord("course-2",
                new ClaimRecordRequest(RACE, "a", "claim-1", "req-claim-1")))
                .isInstanceOf(BadRequestException.class);
        // 选手不在快照 404
        assertThatThrownBy(() -> courseService.claimRecord(COURSE,
                new ClaimRecordRequest(RACE, "ghost", "claim-2", "req-claim-2")))
                .isInstanceOf(NotFoundException.class);
        // 取消资格 422
        assertThatThrownBy(() -> courseService.claimRecord(COURSE,
                new ClaimRecordRequest(RACE, "dq", "claim-3", "req-claim-3")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("取消资格");
        // 无合法完赛计时 422
        assertThatThrownBy(() -> courseService.claimRecord(COURSE,
                new ClaimRecordRequest(RACE, "untimed", "claim-4", "req-claim-4")))
                .isInstanceOf(UnprocessableEntityException.class);

        // 全部失败均不产生纪录
        assertThat(courseService.getRecordHistory(COURSE).history()).isEmpty();
    }

    @Test
    void 认定计时使用封榜快照最终计时_含生效加时() {
        courseService.registerCourse(new RegisterCourseRequest(COURSE, "req-course"));
        raceService.createRace(new CreateRaceRequest(RACE, COURSE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 1000L, 1, "req-a"));
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p1", "a", "ADD_TIME", 250L, 2, "req-p1"));
        raceService.sealRace(RACE, new SealRaceRequest(3, "req-seal"));

        ServiceResult result = courseService.claimRecord(COURSE,
                new ClaimRecordRequest(RACE, "a", "claim-1", "req-claim-1"));
        CourseRecordResponse record = (CourseRecordResponse) result.body();
        // 最终计时 = 原始1000 + 加时250
        assertThat(record.timeMs()).isEqualTo(1250L);
    }

    @Test
    void recordClaimKey重复申请幂等返回首次结果且异参409() {
        seedSealedRaceWithTime(COURSE, RACE, "a", 5000L);
        String race2 = "race-2";
        seedSealedRaceWithTime(COURSE, race2, "b", 4000L);

        ServiceResult first = courseService.claimRecord(COURSE,
                new ClaimRecordRequest(RACE, "a", "claim-1", "req-claim-1"));
        assertThat(first.status()).isEqualTo(201);

        // 同 recordClaimKey 同参（不同 requestId）：幂等返回首次纪录
        ServiceResult replay = courseService.claimRecord(COURSE,
                new ClaimRecordRequest(RACE, "a", "claim-1", "req-claim-1b"));
        assertThat(replay.status()).isEqualTo(201);
        assertThat(((CourseRecordResponse) replay.body()).recordId())
                .isEqualTo(((CourseRecordResponse) first.body()).recordId());

        // 同 recordClaimKey 异参：409
        assertThatThrownBy(() -> courseService.claimRecord(COURSE,
                new ClaimRecordRequest(race2, "b", "claim-1", "req-claim-1c")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("recordClaimKey");

        // 链上仍只有一条
        assertThat(courseService.getRecordHistory(COURSE).history()).hasSize(1);
    }

    @Test
    void requestId同键同参重放首次结果异参409失败不占键() {
        seedSealedRaceWithTime(COURSE, RACE, "a", 5000L);
        ClaimRecordRequest claim =
                new ClaimRecordRequest(RACE, "a", "claim-1", "req-claim");

        ServiceResult first = courseService.claimRecord(COURSE, claim);
        ServiceResult replay = courseService.claimRecord(COURSE, claim);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(((com.fasterxml.jackson.databind.JsonNode) replay.body()).get("recordId")
                .asText())
                .isEqualTo(((CourseRecordResponse) first.body()).recordId());

        // 同 requestId 异参 409
        assertThatThrownBy(() -> courseService.claimRecord(COURSE,
                new ClaimRecordRequest(RACE, "a", "claim-other", "req-claim")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // 失败不占键：先用错误赛道触发失败，再用同 requestId 正确参数重试成功
        courseService.registerCourse(new RegisterCourseRequest("course-2", "req-course-2"));
        assertThatThrownBy(() -> courseService.claimRecord("course-2",
                new ClaimRecordRequest(RACE, "a", "claim-2", "req-claim-2")))
                .isInstanceOf(BadRequestException.class);
        assertThat(repository.findIdempotency("req-claim-2")).isEmpty();

        String race2 = "race-2";
        seedSealedRaceWithTime("course-2", race2, "b", 3000L);
        ServiceResult retry = courseService.claimRecord("course-2",
                new ClaimRecordRequest(race2, "b", "claim-2", "req-claim-2"));
        assertThat(retry.status()).isEqualTo(201);
    }

    @Test
    void 同一赛事同一选手同一计时只能成功认定一次() {
        seedSealedRaceWithTime(COURSE, RACE, "a", 5000L);
        courseService.claimRecord(COURSE,
                new ClaimRecordRequest(RACE, "a", "claim-1", "req-claim-1"));

        // 换 recordClaimKey 再申请同一计时：5000 未严格优于当前 5000，422
        assertThatThrownBy(() -> courseService.claimRecord(COURSE,
                new ClaimRecordRequest(RACE, "a", "claim-2", "req-claim-2")))
                .isInstanceOf(RecordClaimRejectedException.class);
        assertThat(courseService.getRecordHistory(COURSE).history()).hasSize(1);
    }

    @Test
    void 只读查询不触发认定也不改变状态() {
        seedSealedRaceWithTime(COURSE, RACE, "a", 5000L);
        courseService.getRecordHistory(COURSE);
        courseService.getCourse(COURSE);
        assertThat(courseService.getRecordHistory(COURSE).history()).isEmpty();

        courseService.claimRecord(COURSE,
                new ClaimRecordRequest(RACE, "a", "claim-1", "req-claim-1"));
        CourseRecordHistoryResponse before = courseService.getRecordHistory(COURSE);
        courseService.getRecordHistory(COURSE);
        courseService.getCourse(COURSE);
        CourseRecordHistoryResponse after = courseService.getRecordHistory(COURSE);
        assertThat(after).isEqualTo(before);
    }

    /** 登记赛道、建赛、登记指定成绩选手并封榜。 */
    private void seedSealedRaceWithTime(String courseKey, String raceId, String bib,
                                        long finishTimeMs) {
        if (courseRepository.findCourse(courseKey).isEmpty()) {
            courseService.registerCourse(
                    new RegisterCourseRequest(courseKey, "req-course-" + courseKey));
        }
        raceService.createRace(new CreateRaceRequest(raceId, courseKey,
                "req-create-" + raceId));
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest(bib, finishTimeMs, 1, "req-reg-" + raceId));
        raceService.sealRace(raceId, new SealRaceRequest(2, "req-seal-" + raceId));
    }
}
