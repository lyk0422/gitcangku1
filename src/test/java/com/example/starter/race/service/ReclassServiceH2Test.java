package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ApproveReclassRequest;
import com.example.starter.race.api.CategoryResponse;
import com.example.starter.race.api.CategoryStandingResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateCategoryRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.ReclassApplyRequest;
import com.example.starter.race.api.ReclassApprovalResponse;
import com.example.starter.race.api.ReclassRequestResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RejectReclassRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.SealCategoryRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.CategoryStatus;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ReclassRequestStatus;
import com.example.starter.race.persistence.ReclassRecordRow;
import com.example.starter.race.persistence.ReclassRequestRow;
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
 * 组别重分 H2 测试：组别容量、批量原子迁移、排名重算、封榜冻结、
 * 退赛/封榜冲突（409）、整批校验（422）与幂等重放。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ReclassServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-reclass";

    @Autowired
    private RaceService raceService;

    private int seq;

    private int version() {
        return repository.findRace(RACE).orElseThrow().version();
    }

    private String reqId(String op) {
        return op + "-" + (++seq);
    }

    private void createRace() {
        raceService.createRace(new CreateRaceRequest(RACE, reqId("create")));
    }

    private void createCategory(String code, int capacity) {
        raceService.createCategory(RACE,
                new CreateCategoryRequest(code, capacity, version(), reqId("cat-" + code)));
    }

    private void register(String bib, Long finishTimeMs, String category) {
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, finishTimeMs, category, version(),
                        reqId("reg-" + bib)));
    }

    private String apply(String bib, String targetCategory) {
        String requestId = reqId("apply-" + bib);
        ServiceResult result = raceService.submitReclass(RACE, new ReclassApplyRequest(
                bib, targetCategory, "申请理由-" + bib, version(), requestId));
        assertThat(result.status()).isEqualTo(201);
        return requestId;
    }

    @Test
    void 申请批准全链路_原子迁移并重算两组排名且写入不可变重分记录() {
        createRace();
        createCategory("A", 10);
        createCategory("B", 10);
        register("a", 1000L, "A");
        register("b", 2000L, "A");
        register("c", 1500L, "B");
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "pen-a", "a", "ADD_TIME", 100L, version(), reqId("pen-a")));
        String appId = apply("a", "B");

        int approveVersion = version();
        ServiceResult result = raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-1", "referee", approveVersion, List.of(appId)));

        assertThat(result.status()).isEqualTo(200);
        ReclassApprovalResponse body = (ReclassApprovalResponse) result.body();
        assertThat(body.batchId()).isEqualTo("key-1");
        assertThat(body.version()).isEqualTo(approveVersion + 1);
        assertThat(body.approved()).hasSize(1);
        assertThat(body.approved().getFirst().status()).isEqualTo(ReclassRequestStatus.APPROVED);
        assertThat(body.approved().getFirst().batchId()).isEqualTo("key-1");

        // 来源与目标组别排名已重算：A 组只剩 b；B 组 a(总1100) 第一、c(1500) 第二
        assertThat(body.standings()).hasSize(2);
        CategoryStandingResponse standingA = body.standings().get(0);
        assertThat(standingA.categoryCode()).isEqualTo("A");
        assertThat(standingA.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b");
        CategoryStandingResponse standingB = body.standings().get(1);
        assertThat(standingB.categoryCode()).isEqualTo("B");
        assertThat(standingB.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "c");
        assertThat(standingB.entries().get(0).rank()).isEqualTo(1);
        // 处罚不复制而归属新组别视图：a 的加时体现在 B 组排名中
        assertThat(standingB.entries().get(0).penaltyMs()).isEqualTo(100L);
        assertThat(standingB.entries().get(0).totalTimeMs()).isEqualTo(1100L);

        // 参赛者组别已迁移
        RunnerRow runnerA = repository.findRunner(RACE, "a").orElseThrow();
        assertThat(runnerA.categoryCode()).isEqualTo("B");

        // 不可变重分记录：保留迁移前名次与计时快照
        List<ReclassRecordRow> records = repository.findReclassRecords(RACE);
        assertThat(records).hasSize(1);
        ReclassRecordRow record = records.getFirst();
        assertThat(record.batchId()).isEqualTo("key-1");
        assertThat(record.bib()).isEqualTo("a");
        assertThat(record.fromCategory()).isEqualTo("A");
        assertThat(record.toCategory()).isEqualTo("B");
        assertThat(record.preRank()).isEqualTo(1);
        assertThat(record.preStatus()).isEqualTo(EntryStatus.RANKED);
        assertThat(record.preFinishTimeMs()).isEqualTo(1000L);
        assertThat(record.prePenaltyMs()).isEqualTo(100L);
        assertThat(record.preTotalTimeMs()).isEqualTo(1100L);
        assertThat(record.operator()).isEqualTo("referee");

        // 同键成功重放完整排名结果，且不重复写记录（重放响应体为反序列化JSON，统一转换断言）
        ServiceResult replay = raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-1", "referee", approveVersion, List.of(appId)));
        assertThat(replay.status()).isEqualTo(200);
        ReclassApprovalResponse replayBody = new com.fasterxml.jackson.databind.ObjectMapper()
                .convertValue(replay.body(), ReclassApprovalResponse.class);
        assertThat(replayBody.batchId()).isEqualTo("key-1");
        assertThat(replayBody.version()).isEqualTo(approveVersion + 1);
        assertThat(replayBody.standings()).hasSize(2);
        assertThat(repository.findReclassRecords(RACE)).hasSize(1);
        assertThat(version()).isEqualTo(approveVersion + 1);

        // 组别排名查询与批准响应一致
        CategoryStandingResponse queryA = raceService.getCategoryResults(RACE, "A");
        assertThat(queryA.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("b");
        CategoryStandingResponse queryB = raceService.getCategoryResults(RACE, "B");
        assertThat(queryB.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "c");
    }

    @Test
    void 分段与处罚不复制而归属同一参赛者的新组别视图() {
        createRace();
        createCategory("A", 10);
        createCategory("B", 10);
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("CP1", 1)),
                version(), reqId("cp")));
        register("a", 1000L, "A");
        register("c", 1500L, "B");
        raceService.submitTiming(RACE, "a", new SubmitTimingRequest(
                "timing-a-cp1", "CP1", 500L, version(), reqId("timing-a")));
        String appId = apply("a", "B");

        raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-seg", "referee", version(), List.of(appId)));

        // 分段记录仍属同一参赛者（不复制、不删除）
        assertThat(raceService.getRunnerTimings(RACE, "a").checkpoints()
                .getFirst().elapsedMillis())
                .isEqualTo(500L);
        assertThat(repository.findTimingsForRunner(RACE, "a")).hasSize(1);
        // B 组视图：a 覆盖全部检查点正常排名，c 缺检查点不排名
        CategoryStandingResponse standingB = raceService.getCategoryResults(RACE, "B");
        assertThat(standingB.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "c");
        assertThat(standingB.entries().get(0).status()).isEqualTo(EntryStatus.RANKED);
        assertThat(standingB.entries().get(1).status())
                .isEqualTo(EntryStatus.MISSING_CHECKPOINT);
        // 重分记录固化迁移前状态（A 组内 a 覆盖检查点，RANKED 第一）
        ReclassRecordRow record = repository.findReclassRecords(RACE).getFirst();
        assertThat(record.preStatus()).isEqualTo(EntryStatus.RANKED);
        assertThat(record.preRank()).isEqualTo(1);
    }

    @Test
    void 申请校验_目标组别不存在404_相同组别422_重复申请409_退赛者409() {
        createRace();
        createCategory("A", 10);
        createCategory("B", 10);
        register("a", 1000L, "A");
        register("b", 1000L, null);

        assertThatThrownBy(() -> raceService.submitReclass(RACE, new ReclassApplyRequest(
                "a", "NOPE", "理由", version(), reqId("apply-missing"))))
                .isInstanceOf(NotFoundException.class);

        assertThatThrownBy(() -> raceService.submitReclass(RACE, new ReclassApplyRequest(
                "a", "A", "理由", version(), reqId("apply-same"))))
                .isInstanceOf(UnprocessableEntityException.class);

        apply("a", "B");
        assertThatThrownBy(() -> raceService.submitReclass(RACE, new ReclassApplyRequest(
                "a", "B", "再次申请", version(), reqId("apply-dup"))))
                .isInstanceOf(ConflictException.class);

        raceService.withdrawRunner(RACE, "b",
                new WithdrawRunnerRequest(version(), reqId("wd-b")));
        assertThatThrownBy(() -> raceService.submitReclass(RACE, new ReclassApplyRequest(
                "b", "B", "退赛后申请", version(), reqId("apply-wd"))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 批量校验_同一参赛者出现两次_容量不足_目标组别封榜_申请不存在_均整批422且不迁移() {
        createRace();
        createCategory("A", 10);
        createCategory("B", 1);
        createCategory("C", 10);
        register("a", 1000L, "A");
        register("c", 1500L, "C");

        // 同一参赛者一批出现两次
        String dupApp = apply("a", "C");
        int v1 = version();
        assertThatThrownBy(() -> raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-dup", "referee", v1, List.of(dupApp, dupApp))))
                .isInstanceOf(UnprocessableEntityException.class);
        assertUnchanged("a", "A", dupApp, v1);
        reject(dupApp);

        // 目标组别容量不足（B 容量1，c 迁入后占满）
        String moveC = apply("c", "B");
        raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-move-c", "referee", version(), List.of(moveC)));
        String capApp = apply("a", "B");
        int v2 = version();
        assertThatThrownBy(() -> raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-cap", "referee", v2, List.of(capApp))))
                .isInstanceOf(UnprocessableEntityException.class);
        assertUnchanged("a", "A", capApp, v2);
        reject(capApp);

        // 目标组别已封榜（申请提交后目标组别才被封榜）
        String sealedTarget = apply("a", "C");
        raceService.sealCategory(RACE, "C", new SealCategoryRequest(version(), reqId("seal-c")));
        int v3 = version();
        assertThatThrownBy(() -> raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-sealed", "referee", v3, List.of(sealedTarget))))
                .isInstanceOf(UnprocessableEntityException.class);
        assertUnchanged("a", "A", sealedTarget, v3);
        reject(sealedTarget);

        // 申请不存在；且同批的合法申请也不迁移（整批原子）
        createCategory("D", 10);
        String validApp = apply("a", "D");
        int v4 = version();
        assertThatThrownBy(() -> raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-missing", "referee", v4, List.of(validApp, "app-not-exist"))))
                .isInstanceOf(UnprocessableEntityException.class);
        assertUnchanged("a", "A", validApp, v4);
        assertThat(repository.findReclassRecords(RACE)).hasSize(1); // 仅 c->B 的一条
    }

    private void reject(String appId) {
        raceService.rejectReclass(RACE, appId,
                new RejectReclassRequest("清理", version(), reqId("reject")));
    }

    private void assertUnchanged(String bib, String category, String appId, int expectedVersion) {
        assertThat(repository.findRunner(RACE, bib).orElseThrow().categoryCode())
                .isEqualTo(category);
        assertThat(repository.findReclassRequest(appId).orElseThrow().status())
                .isEqualTo(ReclassRequestStatus.PENDING);
        assertThat(version()).isEqualTo(expectedVersion);
    }

    @Test
    void 参赛者已退赛或来源组别已封榜时批准返回409() {
        createRace();
        createCategory("A", 10);
        createCategory("B", 10);
        register("a", 1000L, "A");
        register("b", 2000L, "A");

        // 退赛后批准 -> 409
        String appA = apply("a", "B");
        raceService.withdrawRunner(RACE, "a", new WithdrawRunnerRequest(version(), reqId("wd-a")));
        assertThatThrownBy(() -> raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-wd", "referee", version(), List.of(appA))))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findReclassRequest(appA).orElseThrow().status())
                .isEqualTo(ReclassRequestStatus.PENDING);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode())
                .isEqualTo("A");

        // 来源组别封榜后批准 -> 409
        String appB = apply("b", "B");
        raceService.sealCategory(RACE, "A", new SealCategoryRequest(version(), reqId("seal-a")));
        assertThatThrownBy(() -> raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-src-sealed", "referee", version(), List.of(appB))))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findReclassRequest(appB).orElseThrow().status())
                .isEqualTo(ReclassRequestStatus.PENDING);
        assertThat(repository.findReclassRecords(RACE)).isEmpty();
    }

    @Test
    void 拒绝申请不改变成绩且可重新申请() {
        createRace();
        createCategory("A", 10);
        createCategory("B", 10);
        register("a", 1000L, "A");
        register("b", 2000L, "A");

        String appId = apply("a", "B");
        CategoryStandingResponse before = raceService.getCategoryResults(RACE, "A");

        ServiceResult rejected = raceService.rejectReclass(RACE, appId,
                new RejectReclassRequest("理由不充分", version(), reqId("reject")));
        assertThat(rejected.status()).isEqualTo(200);
        ReclassRequestResponse body = (ReclassRequestResponse) rejected.body();
        assertThat(body.status()).isEqualTo(ReclassRequestStatus.REJECTED);
        assertThat(body.rejectReason()).isEqualTo("理由不充分");

        // 成绩与组别归属不变
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode()).isEqualTo("A");
        CategoryStandingResponse after = raceService.getCategoryResults(RACE, "A");
        assertThat(after.entries()).extracting(ResultEntryResponse::bib)
                .containsExactlyElementsOf(
                        before.entries().stream().map(ResultEntryResponse::bib).toList());
        assertThat(repository.findReclassRecords(RACE)).isEmpty();

        // 已拒绝申请不能重复拒绝
        assertThatThrownBy(() -> raceService.rejectReclass(RACE, appId,
                new RejectReclassRequest("再次拒绝", version(), reqId("reject-2"))))
                .isInstanceOf(ConflictException.class);

        // 拒绝后可重新申请并批准
        String appId2 = apply("a", "B");
        raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-reapply", "referee", version(), List.of(appId2)));
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode()).isEqualTo("B");
    }

    @Test
    void 组别封榜仅固化提交时刻所属组别_历史快照不追溯重写() {
        createRace();
        createCategory("A", 10);
        createCategory("B", 10);
        register("a", 1000L, "A");
        register("b", 2000L, "A");
        register("c", 1500L, "B");

        raceService.sealCategory(RACE, "A", new SealCategoryRequest(version(), reqId("seal-a")));
        CategoryStandingResponse snapshot = raceService.getCategorySnapshot(RACE, "A");
        assertThat(snapshot.status()).isEqualTo(CategoryStatus.SEALED);
        assertThat(snapshot.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        assertThat(snapshot.entries().get(0).totalTimeMs()).isEqualTo(1000L);

        // 封榜后对 a 登记加时（赛事仍 OPEN）：组别榜单返回冻结快照，不追溯重写
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "pen-a", "a", "ADD_TIME", 500L, version(), reqId("pen-a")));
        CategoryStandingResponse after = raceService.getCategoryResults(RACE, "A");
        assertThat(after.status()).isEqualTo(CategoryStatus.SEALED);
        assertThat(after.entries().get(0).totalTimeMs()).isEqualTo(1000L);
        assertThat(after.entries().get(0).penaltyMs()).isZero();
        // 赛事级实时成绩仍反映最新处罚
        StandingResponse live = raceService.getResults(RACE);
        assertThat(live.entries().get(0).bib()).isEqualTo("a");
        assertThat(live.entries().get(0).totalTimeMs()).isEqualTo(1500L);

        // B 组重分不影响 A 组历史快照
        register("d", 1200L, "B");
        createCategory("E", 10);
        String appD = apply("d", "E");
        raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-d", "referee", version(), List.of(appD)));
        CategoryStandingResponse snapshotAgain = raceService.getCategorySnapshot(RACE, "A");
        assertThat(snapshotAgain.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        assertThat(snapshotAgain.entries().get(0).totalTimeMs()).isEqualTo(1000L);
    }

    @Test
    void 赛事封榜或版本冲突时重分相关写入返回409() {
        createRace();
        createCategory("A", 10);
        createCategory("B", 10);
        register("a", 1000L, "A");
        String appId = apply("a", "B");

        // 版本冲突
        assertThatThrownBy(() -> raceService.submitReclass(RACE, new ReclassApplyRequest(
                "a", "B", "理由", version() + 1, reqId("stale"))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-stale", "referee", version() + 1, List.of(appId))))
                .isInstanceOf(ConflictException.class);

        raceService.sealRace(RACE, new SealRaceRequest(version(), reqId("seal-race")));
        assertThatThrownBy(() -> raceService.submitReclass(RACE, new ReclassApplyRequest(
                "a", "B", "理由", version(), reqId("after-seal"))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-after-seal", "referee", version(), List.of(appId))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.createCategory(RACE,
                new CreateCategoryRequest("C", 5, version(), reqId("cat-c"))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.withdrawRunner(RACE, "a",
                new WithdrawRunnerRequest(version(), reqId("wd-after-seal"))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.sealCategory(RACE, "A",
                new SealCategoryRequest(version(), reqId("seal-cat-after"))))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findRace(RACE).orElseThrow().status())
                .isEqualTo(RaceStatus.SEALED);
    }

    @Test
    void 批准失败不占键_同键修正后成功() {
        createRace();
        createCategory("A", 10);
        createCategory("B", 1);
        register("a", 1000L, "A");
        register("c", 1500L, "B");
        String appId = apply("a", "B");

        // B 容量被 c 占满：批准 422，失败不占键
        assertThatThrownBy(() -> raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-retry", "referee", version(), List.of(appId))))
                .isInstanceOf(UnprocessableEntityException.class);

        // 退赛释放容量后同键重试成功（若失败占键，异参会返回409）
        raceService.withdrawRunner(RACE, "c", new WithdrawRunnerRequest(version(), reqId("wd-c")));
        ServiceResult result = raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-retry", "referee", version(), List.of(appId)));
        assertThat(result.status()).isEqualTo(200);
        assertThat(repository.findRunner(RACE, "a").orElseThrow().categoryCode()).isEqualTo("B");
        assertThat(repository.findReclassRecords(RACE)).hasSize(1);
        // 退赛者不参与组别排名
        CategoryStandingResponse standingB = raceService.getCategoryResults(RACE, "B");
        assertThat(standingB.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a");
    }

    @Test
    void 登记组别校验_不存在404_容量满422_已封榜409() {
        createRace();
        createCategory("A", 1);
        createCategory("B", 10);

        assertThatThrownBy(() -> raceService.registerRunner(RACE, new RegisterRunnerRequest(
                "a", 1000L, "NOPE", version(), reqId("reg-missing"))))
                .isInstanceOf(NotFoundException.class);

        register("a", 1000L, "A");
        assertThatThrownBy(() -> raceService.registerRunner(RACE, new RegisterRunnerRequest(
                "b", 1000L, "A", version(), reqId("reg-full"))))
                .isInstanceOf(UnprocessableEntityException.class);

        raceService.sealCategory(RACE, "B", new SealCategoryRequest(version(), reqId("seal-b")));
        assertThatThrownBy(() -> raceService.registerRunner(RACE, new RegisterRunnerRequest(
                "c", 1000L, "B", version(), reqId("reg-sealed"))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 查询组别列表与申请列表() {
        createRace();
        createCategory("A", 2);
        createCategory("B", 10);
        register("a", 1000L, "A");
        register("b", 2000L, "A");
        register("c", 1500L, "B");
        String appId = apply("c", "A");

        List<CategoryResponse> categories = raceService.getCategories(RACE);
        assertThat(categories).hasSize(2);
        assertThat(categories.get(0).categoryCode()).isEqualTo("A");
        assertThat(categories.get(0).activeCount()).isEqualTo(2);
        assertThat(categories.get(0).capacity()).isEqualTo(2);
        assertThat(categories.get(1).activeCount()).isEqualTo(1);

        List<ReclassRequestResponse> requests = raceService.getReclassRequests(RACE);
        assertThat(requests).hasSize(1);
        ReclassRequestResponse request = requests.getFirst();
        assertThat(request.requestId()).isEqualTo(appId);
        assertThat(request.bib()).isEqualTo("c");
        assertThat(request.fromCategory()).isEqualTo("B");
        assertThat(request.toCategory()).isEqualTo("A");
        assertThat(request.status()).isEqualTo(ReclassRequestStatus.PENDING);
        assertThat(request.raceVersion()).isEqualTo(version() - 1);

        // 未分组参赛者：fromCategory 为 null
        register("d", 3000L, null);
        String appD = apply("d", "B");
        ReclassRequestRow rowD = repository.findReclassRequest(appD).orElseThrow();
        assertThat(rowD.fromCategory()).isNull();

        // 未分组 -> B 的批准：来源无组别榜单，响应仅含目标组别排名
        ServiceResult result = raceService.approveReclass(RACE, new ApproveReclassRequest(
                "key-null-src", "referee", version(), List.of(appD)));
        ReclassApprovalResponse body = (ReclassApprovalResponse) result.body();
        assertThat(body.standings()).hasSize(1);
        assertThat(body.standings().getFirst().categoryCode()).isEqualTo("B");
        ReclassRecordRow record = repository.findReclassRecords(RACE).getFirst();
        assertThat(record.fromCategory()).isNull();
        assertThat(record.toCategory()).isEqualTo("B");
        assertThat(record.preRank()).isEqualTo(1);
    }
}
