package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ApproveReclassRequest;
import com.example.starter.race.api.CategoryStandingResponse;
import com.example.starter.race.api.ConfigureCategoriesRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.ReclassApplicationResponse;
import com.example.starter.race.api.ReclassApplicationsResponse;
import com.example.starter.race.api.ReclassApprovalResponse;
import com.example.starter.race.api.ReclassRecordResponse;
import com.example.starter.race.api.ReclassRecordsResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RejectReclassRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SubmitReclassApplicationRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.ApplicationStatus;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 组别重分的 H2 数据库测试：主流程、失败分支、容量、批量原子迁移与排名重算。
 * 组别固定为 ELITE(容量2)/AMATEUR(容量10)/VETERAN(容量10)，版本号按写操作逐次推进。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ReclassServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-reclass";

    @Autowired
    private RaceService raceService;

    /** 建赛 v1 + 配置组别 v2。 */
    private void initRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.configureCategories(RACE, new ConfigureCategoriesRequest(
                List.of(
                        new ConfigureCategoriesRequest.CategoryDefinition("AMATEUR", 10),
                        new ConfigureCategoriesRequest.CategoryDefinition("ELITE", 2),
                        new ConfigureCategoriesRequest.CategoryDefinition("VETERAN", 10)),
                1, "req-cats"));
    }

    private void register(String bib, String category, long finishMs, int version) {
        ServiceResult result = raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, category, finishMs, version, "req-reg-" + bib));
        assertThat(result.status()).isEqualTo(201);
    }

    private String submit(String bib, String target, int version) {
        String requestId = "req-app-" + bib + "-" + target + "-" + version;
        ServiceResult result = raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest(bib, target, "申请理由-" + bib, version, requestId));
        assertThat(result.status()).isEqualTo(201);
        return "rca-" + requestId;
    }

    @Test
    void 主流程_批量批准迁移并重算来源与目标组别排名() {
        initRace();
        register("a", "ELITE", 1000L, 2);   // v3
        register("b", "ELITE", 2000L, 3);   // v4
        register("c", "AMATEUR", 1500L, 4); // v5
        register("d", "AMATEUR", 3000L, 5); // v6
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "p-1", "a", "ADD_TIME", 500L, 6, "req-pen-a")); // v7: a 总耗时1500
        String appA = submit("a", "AMATEUR", 7); // v8
        String appC = submit("c", "ELITE", 8);   // v9

        ServiceResult result = raceService.approveReclassBatch(RACE,
                new ApproveReclassRequest("referee", List.of(appA, appC), 9, "rk-1"));

        assertThat(result.status()).isEqualTo(200);
        ReclassApprovalResponse body = (ReclassApprovalResponse) result.body();
        assertThat(body.version()).isEqualTo(10);
        // 不可变重分记录：保留迁移前名次与计时快照（按申请ID字典序，appA 在前）
        assertThat(body.records()).hasSize(2);
        ReclassRecordResponse recA = body.records().get(0);
        assertThat(recA.recordId()).isEqualTo("rk-1:" + appA);
        assertThat(recA.bib()).isEqualTo("a");
        assertThat(recA.operator()).isEqualTo("referee");
        assertThat(recA.fromCategory()).isEqualTo("ELITE");
        assertThat(recA.toCategory()).isEqualTo("AMATEUR");
        assertThat(recA.preRank()).isEqualTo(1);
        assertThat(recA.preStatus()).isEqualTo(EntryStatus.RANKED);
        assertThat(recA.preFinishTimeMs()).isEqualTo(1000L);
        assertThat(recA.prePenaltyMs()).isEqualTo(500L);
        assertThat(recA.preTotalTimeMs()).isEqualTo(1500L);
        assertThat(recA.createdAt()).isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());
        ReclassRecordResponse recC = body.records().get(1);
        assertThat(recC.bib()).isEqualTo("c");
        assertThat(recC.fromCategory()).isEqualTo("AMATEUR");
        assertThat(recC.toCategory()).isEqualTo("ELITE");
        assertThat(recC.preRank()).isEqualTo(1);
        assertThat(recC.prePenaltyMs()).isEqualTo(0L);
        assertThat(recC.preTotalTimeMs()).isEqualTo(1500L);
        // 重算后两组排名：AMATEUR={a:1500, d:3000}，ELITE={c:1500, b:2000}
        assertThat(body.standings()).extracting(CategoryStandingResponse::categoryCode)
                .containsExactly("AMATEUR", "ELITE");
        assertThat(body.standings().get(0).entries())
                .extracting(ResultEntryResponse::bib).containsExactly("a", "d");
        assertThat(body.standings().get(0).entries())
                .extracting(ResultEntryResponse::rank).containsExactly(1, 2);
        assertThat(body.standings().get(1).entries())
                .extracting(ResultEntryResponse::bib).containsExactly("c", "b");
        assertThat(body.standings().get(1).entries())
                .extracting(ResultEntryResponse::rank).containsExactly(1, 2);
        // 数据库终态：组别已迁移、版本只加一、申请已批准
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode())
                .isEqualTo("AMATEUR");
        assertThat(repository.findRunner(RACE, "c").orElseThrow().categoryCode())
                .isEqualTo("ELITE");
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(10);
        // 查询：两组排名、申请列表、重分记录列表
        CategoryStandingResponse elite = raceService.getCategoryResults(RACE, "ELITE");
        assertThat(elite.sealed()).isFalse();
        assertThat(elite.version()).isEqualTo(10);
        assertThat(elite.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("c", "b");
        CategoryStandingResponse amateur = raceService.getCategoryResults(RACE, "AMATEUR");
        assertThat(amateur.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "d");
        ReclassApplicationsResponse applications = raceService.getReclassApplications(RACE);
        assertThat(applications.applications())
                .extracting(ReclassApplicationResponse::status)
                .containsExactly(ApplicationStatus.APPROVED, ApplicationStatus.APPROVED);
        assertThat(applications.applications().get(0).decidedAt())
                .isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());
        ReclassRecordsResponse records = raceService.getReclassRecords(RACE);
        assertThat(records.records()).extracting(ReclassRecordResponse::bib)
                .containsExactly("a", "c");
    }

    @Test
    void 拒绝申请不改变成绩与组别() {
        initRace();
        register("a", "ELITE", 1000L, 2); // v3
        String appA = submit("a", "AMATEUR", 3); // v4

        ServiceResult rejected = raceService.rejectReclassApplication(RACE, appA,
                new RejectReclassRequest(4, "req-reject")); // v5

        assertThat(rejected.status()).isEqualTo(200);
        ReclassApplicationResponse body = (ReclassApplicationResponse) rejected.body();
        assertThat(body.status()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(body.decidedAt()).isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());
        // 成绩与组别不变：a 仍在 ELITE 且排名第1，AMATEUR 为空，无重分记录
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode())
                .isEqualTo("ELITE");
        assertThat(raceService.getCategoryResults(RACE, "ELITE").entries())
                .extracting(ResultEntryResponse::bib).containsExactly("a");
        assertThat(raceService.getCategoryResults(RACE, "AMATEUR").entries()).isEmpty();
        assertThat(raceService.getReclassRecords(RACE).records()).isEmpty();
        // 已拒绝的申请不能再批准（非待审批 -> 整批422）
        assertThatThrownBy(() -> raceService.approveReclassBatch(RACE,
                new ApproveReclassRequest("referee", List.of(appA), 5, "rk-rej")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 重复拒绝 -> 409
        assertThatThrownBy(() -> raceService.rejectReclassApplication(RACE, appA,
                new RejectReclassRequest(5, "req-reject-2")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 提交申请失败分支_目标不存在404_与当前相同422_版本冲突409() {
        initRace();
        register("a", "ELITE", 1000L, 2); // v3
        assertThatThrownBy(() -> raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest("a", "NOPE", "理由", 3, "req-bad-1")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest("a", "ELITE", "理由", 3, "req-bad-2")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThatThrownBy(() -> raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest("a", "AMATEUR", "理由", 1, "req-bad-3")))
                .isInstanceOf(ConflictException.class);
        // 失败不占版本、不产生申请
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(3);
        assertThat(raceService.getReclassApplications(RACE).applications()).isEmpty();
    }

    @Test
    void 批次校验_同一参赛者仅一次_违反整批422且原子回滚() {
        initRace();
        register("a", "ELITE", 1000L, 2);   // v3
        register("c", "AMATEUR", 1500L, 3); // v4
        String appA1 = submit("a", "AMATEUR", 4); // v5
        String appA2 = submit("a", "VETERAN", 5); // v6
        String appC = submit("c", "ELITE", 6);    // v7

        // a 在批内出现两次 -> 整批422，c 的合法申请也不同批迁移
        assertThatThrownBy(() -> raceService.approveReclassBatch(RACE,
                new ApproveReclassRequest("referee", List.of(appA1, appA2, appC), 7, "rk-dup")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("仅允许出现一次");

        // 原子性：无人迁移、版本不变、申请全部仍待审批、无重分记录
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode()).isEqualTo("ELITE");
        assertThat(repository.findRunner(RACE, "c").orElseThrow().categoryCode()).isEqualTo("AMATEUR");
        assertThat(raceService.getReclassApplications(RACE).applications())
                .extracting(ReclassApplicationResponse::status)
                .containsOnly(ApplicationStatus.PENDING);
        assertThat(raceService.getReclassRecords(RACE).records()).isEmpty();
    }
    @Test
    void 批次校验_目标组别容量超限整批422_同批互换不超限() {
        initRace();
        register("a", "ELITE", 1000L, 2);   // v3
        register("b", "ELITE", 2000L, 3);   // v4
        register("c", "AMATEUR", 1500L, 4); // v5
        register("d", "AMATEUR", 3000L, 5); // v6
        String appC = submit("c", "ELITE", 6); // v7
        String appD = submit("d", "ELITE", 7); // v8

        // ELITE 容量2：当前2人 + 迁入2人 = 4 > 2 -> 整批422
        assertThatThrownBy(() -> raceService.approveReclassBatch(RACE,
                new ApproveReclassRequest("referee", List.of(appC, appD), 8, "rk-cap")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("容量");
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(8);
        assertThat(repository.countActiveRunnersInCategory(RACE, "ELITE")).isEqualTo(2);

        // a 迁出 ELITE 腾出名额后，c 单独迁入成功（2-1+1=2 不超限）
        String appA = submit("a", "VETERAN", 8);  // v9
        ServiceResult swapped = raceService.approveReclassBatch(RACE,
                new ApproveReclassRequest("referee", List.of(appA, appC), 9, "rk-swap"));
        assertThat(swapped.status()).isEqualTo(200);
        assertThat(repository.countActiveRunnersInCategory(RACE, "ELITE")).isEqualTo(2);
        assertThat(raceService.getCategoryResults(RACE, "ELITE").entries())
                .extracting(ResultEntryResponse::bib).containsExactly("c", "b");
    }

    @Test
    void 登记时组别容量已满422_退赛释放名额() {
        initRace();
        register("a", "ELITE", 1000L, 2); // v3
        register("b", "ELITE", 2000L, 3); // v4
        // ELITE 已满：第三人登记 422
        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("e", "ELITE", 500L, 4, "req-reg-e")))
                .isInstanceOf(UnprocessableEntityException.class);
        // a 退赛后释放名额（退赛者不占容量）
        raceService.withdrawRunner(RACE, new WithdrawRunnerRequest("a", 4, "req-wd-a")); // v5
        ServiceResult result = raceService.registerRunner(RACE,
                new RegisterRunnerRequest("e", "ELITE", 500L, 5, "req-reg-e2"));
        assertThat(result.status()).isEqualTo(201);
        // 退赛者不参与排名
        assertThat(raceService.getCategoryResults(RACE, "ELITE").entries())
                .extracting(ResultEntryResponse::bib).containsExactly("e", "b");
        assertThat(raceService.getResults(RACE).entries())
                .extracting(ResultEntryResponse::bib).containsExactly("e", "b");
    }

    @Test
    void 批准时参赛者已退赛返回409() {
        initRace();
        register("a", "ELITE", 1000L, 2); // v3
        String appA = submit("a", "AMATEUR", 3); // v4
        raceService.withdrawRunner(RACE, new WithdrawRunnerRequest("a", 4, "req-wd-a")); // v5

        assertThatThrownBy(() -> raceService.approveReclassBatch(RACE,
                new ApproveReclassRequest("referee", List.of(appA), 5, "rk-wd")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("退赛");
        // 未迁移、申请仍待审批
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode()).isEqualTo("ELITE");
        assertThat(raceService.getReclassApplications(RACE).applications().get(0).status())
                .isEqualTo(ApplicationStatus.PENDING);
    }

    @Test
    void 赛事封榜后批准返回409() {
        initRace();
        register("a", "ELITE", 1000L, 2); // v3
        String appA = submit("a", "AMATEUR", 3); // v4
        raceService.sealRace(RACE, new SealRaceRequest(4, "req-seal")); // v5 SEALED

        assertThatThrownBy(() -> raceService.approveReclassBatch(RACE,
                new ApproveReclassRequest("referee", List.of(appA), 5, "rk-sealed")))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode()).isEqualTo("ELITE");
    }
}