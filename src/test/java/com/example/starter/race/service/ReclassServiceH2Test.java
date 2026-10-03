package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ApproveReclassRequest;
import com.example.starter.race.api.CategoryStandingResponse;
import com.example.starter.race.api.CreateCategoryRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.ReclassApplicationResponse;
import com.example.starter.race.api.ReclassApprovalResponse;
import com.example.starter.race.api.ReclassRecordResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RejectReclassRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitReclassApplicationRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ReclassRequestStatus;
import com.example.starter.race.persistence.RunnerRow;
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
 * 组别重分的 H2 数据库测试：主流程、失败分支、组别容量、
 * 批量原子迁移、排名重算、封榜冻结与幂等边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ReclassServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-reclass";

    @Autowired
    private RaceService raceService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int version() {
        return repository.findRace(RACE).orElseThrow().version();
    }

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
    }

    private void createCategory(String code, int capacity) {
        raceService.createCategory(RACE,
                new CreateCategoryRequest(code, capacity, version(), "req-cat-" + code));
    }

    private void register(String bib, Long finishMs, String categoryCode) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishMs, categoryCode, version(), "req-reg-" + bib));
    }

    private void submit(String applicationId, String bib, String targetCategory) {
        raceService.submitReclassApplication(RACE, new SubmitReclassApplicationRequest(
                applicationId, bib, targetCategory, "理由-" + applicationId,
                version(), "req-sub-" + applicationId));
    }

    private ServiceResult approve(String reclassKey, List<String> applicationIds) {
        return raceService.approveReclass(RACE,
                new ApproveReclassRequest(reclassKey, "op-1", version(), applicationIds));
    }

    @Test
    void 主流程_申请批准后迁移并重算来源与目标组别排名() {
        createRace();
        createCategory("A", 3);
        createCategory("B", 3);
        register("a", 1000L, "A");
        register("b", 2000L, "A");
        register("c", 1500L, "B");
        // b 加时 500ms：处罚跟随选手归属新组别视图
        raceService.addPenalty(RACE,
                new AddPenaltyRequest("p-1", "b", "ADD_TIME", 500L, version(), "req-pen-b"));

        CategoryStandingResponse aBefore = raceService.getCategoryResults(RACE, "A");
        assertThat(aBefore.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        assertThat(aBefore.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2);

        submit("app-1", "b", "B");
        ReclassApplicationResponse pending = raceService.listReclassApplications(RACE).getFirst();
        assertThat(pending.status()).isEqualTo(ReclassRequestStatus.PENDING);
        assertThat(pending.sourceCategoryCode()).isEqualTo("A");
        assertThat(pending.targetCategoryCode()).isEqualTo("B");
        assertThat(pending.reason()).isEqualTo("理由-app-1");

        ServiceResult result = approve("k1", List.of("app-1"));
        assertThat(result.status()).isEqualTo(200);
        ReclassApprovalResponse approval = (ReclassApprovalResponse) result.body();
        assertThat(approval.version()).isEqualTo(version());
        assertThat(approval.operator()).isEqualTo("op-1");

        // 不可变重分记录：保留迁移前名次与计时快照
        assertThat(approval.records()).hasSize(1);
        ReclassRecordResponse record = approval.records().getFirst();
        assertThat(record.recordId()).isEqualTo("k1:app-1");
        assertThat(record.bib()).isEqualTo("b");
        assertThat(record.fromCategoryCode()).isEqualTo("A");
        assertThat(record.toCategoryCode()).isEqualTo("B");
        assertThat(record.rankBefore()).isEqualTo(2);
        assertThat(record.finishTimeMs()).isEqualTo(2000L);
        assertThat(record.penaltyMs()).isEqualTo(500L);
        assertThat(record.totalTimeMs()).isEqualTo(2500L);
        assertThat(record.raceVersion()).isEqualTo(version());

        // 来源与目标组别排名重算
        assertThat(approval.standings()).extracting(CategoryStandingResponse::categoryCode)
                .containsExactly("A", "B");
        CategoryStandingResponse aAfter = approval.standings().get(0);
        assertThat(aAfter.entries()).extracting(ResultEntryResponse::bib).containsExactly("a");
        CategoryStandingResponse bAfter = approval.standings().get(1);
        assertThat(bAfter.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("c", "b");
        assertThat(bAfter.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2);
        assertThat(bAfter.entries().get(1).totalTimeMs()).isEqualTo(2500L);

        // 成绩归属新组别视图而非复制：选手行只有一条，组别已迁移
        RunnerRow bRow = repository.findRunner(RACE, "b").orElseThrow();
        assertThat(bRow.categoryCode()).isEqualTo("B");
        assertThat(bRow.finishTimeMs()).isEqualTo(2000L);
        assertThat(repository.findRunners(RACE)).hasSize(3);
        assertThat(repository.findPenalties(RACE)).hasSize(1);

        // 申请状态与重分记录查询
        List<ReclassApplicationResponse> applications = raceService.listReclassApplications(RACE);
        assertThat(applications).hasSize(1);
        assertThat(applications.getFirst().status()).isEqualTo(ReclassRequestStatus.APPROVED);
        assertThat(applications.getFirst().decidedAt())
                .isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());
        List<ReclassRecordResponse> records = raceService.listReclassRecords(RACE);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().reclassKey()).isEqualTo("k1");

        // 赛事总榜条目携带当前所属组别
        StandingResponse standing = raceService.getResults(RACE);
        assertThat(standing.entries())
                .filteredOn(entry -> entry.bib().equals("b"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.categoryCode()).isEqualTo("B"));
    }

    @Test
    void 提交申请失败分支_目标组别选手状态与重复申请() {
        createRace();
        createCategory("A", 2);
        createCategory("B", 2);
        register("a", 1000L, "A");

        // 目标组别不存在 -> 404
        assertThatThrownBy(() -> raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest("app-x1", "a", "NOPE", "r", version(), "req-x1")))
                .isInstanceOf(NotFoundException.class);
        // 选手不存在 -> 404
        assertThatThrownBy(() -> raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest("app-x2", "ghost", "B", "r", version(), "req-x2")))
                .isInstanceOf(NotFoundException.class);
        // 已在目标组别 -> 422
        assertThatThrownBy(() -> raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest("app-x3", "a", "A", "r", version(), "req-x3")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 失败不占键：同一 requestId 修正参数后可成功
        assertThatThrownBy(() -> raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest("app-x4", "a", "NOPE", "r", version(), "req-reuse")))
                .isInstanceOf(NotFoundException.class);
        ServiceResult reused = raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest("app-1", "a", "B", "r", version(), "req-reuse"));
        assertThat(reused.status()).isEqualTo(201);

        // 同一选手已有待审批申请 -> 409
        assertThatThrownBy(() -> raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest("app-2", "a", "B", "r", version(), "req-x5")))
                .isInstanceOf(ConflictException.class);

        // 退赛选手提交 -> 422
        register("w", 1000L, "A");
        raceService.withdrawRunner(RACE, "w", new WithdrawRunnerRequest(version(), "req-wd-w"));
        assertThatThrownBy(() -> raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest("app-3", "w", "B", "r", version(), "req-x6")))
                .isInstanceOf(UnprocessableEntityException.class);

        // 目标组别已封榜 -> 409
        raceService.sealCategory(RACE, "B", new SealRaceRequest(version(), "req-seal-b"));
        register("d", 3000L, "A");
        assertThatThrownBy(() -> raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest("app-4", "d", "B", "r", version(), "req-x7")))
                .isInstanceOf(ConflictException.class);

        // 赛事封榜后提交 -> 409
        raceService.sealRace(RACE, new SealRaceRequest(version(), "req-seal-race"));
        assertThatThrownBy(() -> raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest("app-5", "d", "A", "r", version(), "req-x8")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 批量原子迁移_容量超限整批422且不产生部分迁移() {
        createRace();
        createCategory("A", 3);
        createCategory("B", 2);
        register("a", 1000L, "A");
        register("b", 2000L, "A");
        register("c", 1500L, "B");
        submit("app-1", "a", "B");
        submit("app-2", "b", "B");

        // B 当前1人、容量2，迁入2人后将为3 -> 整批422
        int versionBefore = version();
        assertThatThrownBy(() -> approve("k1", List.of("app-1", "app-2")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("容量");

        // 原子性：无人迁移、申请仍待审批、无重分记录、版本未推进
        assertThat(version()).isEqualTo(versionBefore);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode()).isEqualTo("A");
        assertThat(repository.findRunner(RACE, "b").orElseThrow().categoryCode()).isEqualTo("A");
        assertThat(raceService.listReclassApplications(RACE))
                .allMatch(app -> app.status() == ReclassRequestStatus.PENDING);
        assertThat(raceService.listReclassRecords(RACE)).isEmpty();

        // 失败不占键：同一 reclassKey 缩小批次后成功
        ServiceResult ok = approve("k1", List.of("app-1"));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode()).isEqualTo("B");
        assertThat(repository.findRunner(RACE, "b").orElseThrow().categoryCode()).isEqualTo("A");
    }

    @Test
    void 批量校验_非待审批申请整批422() {
        createRace();
        createCategory("A", 3);
        createCategory("B", 3);
        register("a", 1000L, "A");
        register("b", 2000L, "A");
        submit("app-1", "a", "B");
        submit("app-2", "b", "B");
        assertThat(approve("k1", List.of("app-1")).status()).isEqualTo(200);

        // app-1 已批准，混入批次 -> 整批422，app-2 不被迁移
        int versionBefore = version();
        assertThatThrownBy(() -> approve("k2", List.of("app-1", "app-2")))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(version()).isEqualTo(versionBefore);
        assertThat(repository.findRunner(RACE, "b").orElseThrow().categoryCode()).isEqualTo("A");
        assertThat(raceService.listReclassApplications(RACE))
                .filteredOn(app -> app.applicationId().equals("app-2"))
                .singleElement()
                .satisfies(app -> assertThat(app.status()).isEqualTo(ReclassRequestStatus.PENDING));
    }

    @Test
    void 同批互换组别按净迁移校验容量() {
        createRace();
        createCategory("A", 1);
        createCategory("B", 1);
        register("a", 1000L, "A");
        register("c", 1500L, "B");
        submit("app-1", "a", "B");
        submit("app-2", "c", "A");

        // A、B 各自容量1：互换后净迁移为零，整批成功
        ServiceResult result = approve("k1", List.of("app-1", "app-2"));
        assertThat(result.status()).isEqualTo(200);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode()).isEqualTo("B");
        assertThat(repository.findRunner(RACE, "c").orElseThrow().categoryCode()).isEqualTo("A");
        ReclassApprovalResponse approval = (ReclassApprovalResponse) result.body();
        assertThat(approval.records()).hasSize(2);
        assertThat(approval.standings()).extracting(CategoryStandingResponse::categoryCode)
                .containsExactly("A", "B");
        assertThat(approval.standings().get(0).entries())
                .extracting(ResultEntryResponse::bib).containsExactly("c");
        assertThat(approval.standings().get(1).entries())
                .extracting(ResultEntryResponse::bib).containsExactly("a");
    }

    @Test
    void 组别容量_登记超限422且退赛释放名额() {
        createRace();
        createCategory("A", 1);
        register("a", 1000L, "A");

        // 容量已满 -> 422
        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 2000L, "A", version(), "req-reg-b")))
                .isInstanceOf(UnprocessableEntityException.class);

        // 退赛释放名额后可登记
        raceService.withdrawRunner(RACE, "a", new WithdrawRunnerRequest(version(), "req-wd-a"));
        ServiceResult registered = raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", 2000L, "A", version(), "req-reg-b"));
        assertThat(registered.status()).isEqualTo(201);

        // 重复退赛 -> 409
        assertThatThrownBy(() -> raceService.withdrawRunner(
                RACE, "a", new WithdrawRunnerRequest(version(), "req-wd-a2")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 拒绝申请不改变成绩() {
        createRace();
        createCategory("A", 3);
        createCategory("B", 3);
        register("a", 1000L, "A");
        register("b", 2000L, "A");
        submit("app-1", "b", "B");

        StandingResponse before = raceService.getResults(RACE);
        ServiceResult rejected = raceService.rejectReclassApplication(
                RACE, "app-1", new RejectReclassRequest(version(), "req-rej-1"));
        assertThat(rejected.status()).isEqualTo(200);
        ReclassApplicationResponse body = (ReclassApplicationResponse) rejected.body();
        assertThat(body.status()).isEqualTo(ReclassRequestStatus.REJECTED);
        assertThat(body.decidedAt()).isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());

        // 成绩与组别归属不变
        StandingResponse after = raceService.getResults(RACE);
        assertThat(json(after.entries())).isEqualTo(json(before.entries()));
        assertThat(repository.findRunner(RACE, "b").orElseThrow().categoryCode()).isEqualTo("A");
        assertThat(raceService.listReclassRecords(RACE)).isEmpty();

        // 重复拒绝 -> 409；已拒绝申请批准 -> 422
        assertThatThrownBy(() -> raceService.rejectReclassApplication(
                RACE, "app-1", new RejectReclassRequest(version(), "req-rej-2")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> approve("k1", List.of("app-1")))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void 封榜冻结_组别快照不追溯重写且涉封榜组别批准409() {
        createRace();
        createCategory("A", 3);
        createCategory("B", 3);
        register("a", 1000L, "A");
        register("b", 2000L, "A");
        register("c", 1500L, "B");

        // 组别封榜：冻结 A 当前排名
        ServiceResult sealed = raceService.sealCategory(
                RACE, "A", new SealRaceRequest(version(), "req-seal-a"));
        assertThat(sealed.status()).isEqualTo(200);
        CategoryStandingResponse sealedStanding = (CategoryStandingResponse) sealed.body();
        assertThat(sealedStanding.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(sealedStanding.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        int sealedVersion = sealedStanding.version();

        // 重复组别封榜 -> 409
        assertThatThrownBy(() -> raceService.sealCategory(
                RACE, "A", new SealRaceRequest(version(), "req-seal-a2")))
                .isInstanceOf(ConflictException.class);

        // 来源组别已封榜的申请：可提交，批准时 -> 409
        submit("app-1", "a", "B");
        assertThatThrownBy(() -> approve("k1", List.of("app-1")))
                .isInstanceOf(ConflictException.class);

        // 组别成绩在封榜后返回快照
        CategoryStandingResponse aResults = raceService.getCategoryResults(RACE, "A");
        assertThat(aResults.status()).isEqualTo(RaceStatus.SEALED);
        assertThat(aResults.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");

        // 赛事封榜：快照固化提交时刻所属组别，历史组别快照不追溯重写
        raceService.sealRace(RACE, new SealRaceRequest(version(), "req-seal-race"));
        CategoryStandingResponse aSnapshot = raceService.getCategorySnapshot(RACE, "A");
        assertThat(aSnapshot.version()).isEqualTo(sealedVersion);
        assertThat(aSnapshot.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        // 赛事封榜同时为未封榜的 B 组固化快照
        CategoryStandingResponse bSnapshot = raceService.getCategorySnapshot(RACE, "B");
        assertThat(bSnapshot.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("c");
        // 赛事快照条目携带封榜时刻所属组别
        StandingResponse raceSnapshot = raceService.getSnapshot(RACE);
        assertThat(raceSnapshot.entries())
                .filteredOn(entry -> entry.bib().equals("a"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.categoryCode()).isEqualTo("A"));

        // 赛事封榜后批准 -> 409
        assertThatThrownBy(() -> approve("k2", List.of("app-1")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 批准时选手已退赛返回409() {
        createRace();
        createCategory("A", 3);
        createCategory("B", 3);
        register("a", 1000L, "A");
        submit("app-1", "a", "B");
        raceService.withdrawRunner(RACE, "a", new WithdrawRunnerRequest(version(), "req-wd-a"));

        int versionBefore = version();
        assertThatThrownBy(() -> approve("k1", List.of("app-1")))
                .isInstanceOf(ConflictException.class);
        assertThat(version()).isEqualTo(versionBefore);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode()).isEqualTo("A");
        assertThat(raceService.listReclassRecords(RACE)).isEmpty();
    }

    @Test
    void 幂等_同键重放完整排名结果且异参409() throws Exception {
        createRace();
        createCategory("A", 3);
        createCategory("B", 3);
        register("a", 1000L, "A");
        register("c", 1500L, "B");
        submit("app-1", "a", "B");

        ApproveReclassRequest request =
                new ApproveReclassRequest("k1", "op-1", version(), List.of("app-1"));
        ServiceResult first = raceService.approveReclass(RACE, request);
        assertThat(first.status()).isEqualTo(200);
        int versionAfterFirst = version();

        // 同键同参重放：完整排名结果一致，版本与记录不重复
        ServiceResult replay = raceService.approveReclass(RACE, request);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(objectMapper.readTree(json(replay.body())))
                .isEqualTo(objectMapper.readTree(json(first.body())));
        assertThat(version()).isEqualTo(versionAfterFirst);
        assertThat(raceService.listReclassRecords(RACE)).hasSize(1);

        // 同键异参 -> 409
        submit("app-2", "c", "A");
        assertThatThrownBy(() -> raceService.approveReclass(RACE,
                new ApproveReclassRequest("k1", "op-1", version(), List.of("app-2"))))
                .isInstanceOf(ConflictException.class);
    }
}