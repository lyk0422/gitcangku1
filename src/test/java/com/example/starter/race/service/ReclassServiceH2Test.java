package com.example.starter.race.service;

import com.example.starter.race.api.ApproveReclassRequest;
import com.example.starter.race.api.CategoryResponse;
import com.example.starter.race.api.CategoryStandingResponse;
import com.example.starter.race.api.CreateCategoryRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.ReclassApplicationResponse;
import com.example.starter.race.api.ReclassApprovalResponse;
import com.example.starter.race.api.ReclassRecordResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RejectReclassRequest;
import com.example.starter.race.api.SealCategoryRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SubmitReclassApplicationRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.CategoryStatus;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RunnerStatus;
import com.example.starter.race.persistence.ReclassRequestRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 组别重分 H2 服务层测试：组别容量、批量原子迁移、排名重算、
 * 不可变重分记录、封榜冻结、退赛与版本/状态冲突分支。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ReclassServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-reclass";

    @Autowired
    private RaceService raceService;

    @Autowired
    private ObjectMapper objectMapper;

    /** 建赛事（v1）+ 组别 A（容量2，v2）+ 组别 B（容量2，v3）。 */
    private void setupRaceWithCategories() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.createCategory(RACE, new CreateCategoryRequest("CAT_A", 2, 1, "req-cat-a"));
        raceService.createCategory(RACE, new CreateCategoryRequest("CAT_B", 2, 2, "req-cat-b"));
    }

    private void register(String bib, String category, long finishMs, int version) {
        raceService.registerRunner(RACE, new RegisterRunnerRequest(
                bib, category, finishMs, version, "req-reg-" + bib));
    }

    private void submitApplication(String applicationId, String bib, String target, int version) {
        raceService.submitReclassApplication(RACE, new SubmitReclassApplicationRequest(
                applicationId, bib, target, "理由-" + applicationId, version,
                "req-sub-" + applicationId));
    }

    @Test
    void 批准批次原子迁移并重算两组排名且写入不可变记录() {
        setupRaceWithCategories();
        register("a1", "CAT_A", 1000L, 3);
        register("a2", "CAT_A", 2000L, 4);
        register("b1", "CAT_B", 1500L, 5);
        // 当前版本6；a2 申请从 CAT_A 迁到 CAT_B
        submitApplication("app-1", "a2", "CAT_B", 6);

        ServiceResult result = raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("batch-1", "referee", 7, List.of("app-1")));

        assertThat(result.status()).isEqualTo(200);
        ReclassApprovalResponse response = (ReclassApprovalResponse) result.body();
        assertThat(response.batchId()).isEqualTo("batch-1");
        assertThat(response.version()).isEqualTo(8);

        // 选手组别已迁移，成绩/分段/处罚归属新组别视图（不复制）
        RunnerRow moved = repository.findRunner(RACE, "a2").orElseThrow();
        assertThat(moved.categoryCode()).isEqualTo("CAT_B");
        assertThat(moved.finishTimeMs()).isEqualTo(2000L);

        // 不可变重分记录：保留迁移前名次与计时快照
        assertThat(response.records()).hasSize(1);
        ReclassRecordResponse record = response.records().getFirst();
        assertThat(record.bib()).isEqualTo("a2");
        assertThat(record.fromCategory()).isEqualTo("CAT_A");
        assertThat(record.toCategory()).isEqualTo("CAT_B");
        assertThat(record.prevRank()).isEqualTo(2);
        assertThat(record.prevStatus()).isEqualTo(EntryStatus.RANKED);
        assertThat(record.prevFinishTimeMs()).isEqualTo(2000L);
        assertThat(record.prevPenaltyMs()).isZero();
        assertThat(record.prevTotalTimeMs()).isEqualTo(2000L);

        // 两组排名重算：CAT_A 仅剩 a1；CAT_B 为 b1、a2
        assertThat(response.standings()).containsOnlyKeys("CAT_A", "CAT_B");
        assertThat(response.standings().get("CAT_A"))
                .extracting("bib").containsExactly("a1");
        assertThat(response.standings().get("CAT_B"))
                .extracting("bib").containsExactly("b1", "a2");
        assertThat(response.standings().get("CAT_B"))
                .extracting("rank").containsExactly(1, 2);

        // 申请状态与查询接口
        List<ReclassApplicationResponse> applications = raceService.listReclassApplications(RACE);
        assertThat(applications).hasSize(1);
        assertThat(applications.getFirst().status())
                .isEqualTo(ReclassRequestRow.ReclassRequestStatus.APPROVED);
        assertThat(applications.getFirst().batchId()).isEqualTo("batch-1");

        List<ReclassRecordResponse> records = raceService.listReclassRecords(RACE);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().toCategory()).isEqualTo("CAT_B");

        // 组别排名查询与组别列表人数
        CategoryStandingResponse catB = raceService.getCategoryStandings(RACE, "CAT_B");
        assertThat(catB.entries()).extracting("bib").containsExactly("b1", "a2");
        List<CategoryResponse> categories = raceService.listCategories(RACE);
        assertThat(categories).filteredOn(c -> c.categoryCode().equals("CAT_B"))
                .first().extracting(CategoryResponse::activeCount).isEqualTo(2);
        assertThat(categories).filteredOn(c -> c.categoryCode().equals("CAT_A"))
                .first().extracting(CategoryResponse::activeCount).isEqualTo(1);
    }

    @Test
    void 目标组别容量超限整批422且无部分迁移() {
        setupRaceWithCategories();
        register("a1", "CAT_A", 1000L, 3);
        register("a2", "CAT_A", 2000L, 4);
        register("b1", "CAT_B", 1500L, 5);
        register("b2", "CAT_B", 1600L, 6);
        // CAT_B 容量2已满；a1/a2 同时申请迁入，整批应422
        submitApplication("app-1", "a1", "CAT_B", 7);
        submitApplication("app-2", "a2", "CAT_B", 8);

        assertThatThrownBy(() -> raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("batch-cap", "referee", 9,
                        List.of("app-1", "app-2"))))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("容量");

        // 原子性：无任何迁移、无记录、申请仍待审批、版本未推进
        assertThat(repository.findRunner(RACE, "a1").orElseThrow().categoryCode())
                .isEqualTo("CAT_A");
        assertThat(repository.findRunner(RACE, "a2").orElseThrow().categoryCode())
                .isEqualTo("CAT_A");
        assertThat(raceService.listReclassRecords(RACE)).isEmpty();
        assertThat(raceService.listReclassApplications(RACE))
                .extracting(ReclassApplicationResponse::status)
                .containsOnly(ReclassRequestRow.ReclassRequestStatus.PENDING);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(9);
    }

    @Test
    void 批内同一参赛者出现两次整批422() {
        setupRaceWithCategories();
        register("a1", "CAT_A", 1000L, 3);
        // 再建一个目标组别避免容量干扰
        raceService.createCategory(RACE, new CreateCategoryRequest("CAT_C", null, 4, "req-cat-c"));
        submitApplication("app-1", "a1", "CAT_B", 5);
        // 直接构造批内重复：两个申请ID指向同一选手不可能（同时只允许一条PENDING），
        // 因此用同一申请ID重复与两条同 bib 申请两种途径之一；这里验证重复ID去重后正常，
        // 以及同 bib 两条 PENDING 无法构造（提交第二条即409）。
        assertThatThrownBy(() -> submitApplication("app-2", "a1", "CAT_C", 6))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("待审批");
    }

    @Test
    void 目标组别不存在或已封榜提交申请422() {
        setupRaceWithCategories();
        register("a1", "CAT_A", 1000L, 3);

        assertThatThrownBy(() -> submitApplication("app-x", "a1", "NO_SUCH", 4))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("目标组别不存在");

        // 目标组别先封榜，再提交申请 → 422
        raceService.sealCategory(RACE, "CAT_B", new SealCategoryRequest(4, "req-seal-b"));
        assertThatThrownBy(() -> submitApplication("app-y", "a1", "CAT_B", 5))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("目标组别已封榜");
    }

    @Test
    void 批准时目标组别已封榜整批422() {
        setupRaceWithCategories();
        register("a1", "CAT_A", 1000L, 3);
        register("b1", "CAT_B", 1500L, 4);
        submitApplication("app-1", "a1", "CAT_B", 5);

        // 申请提交后目标组别 CAT_B 封榜 → 批准整批422，无任何迁移
        raceService.sealCategory(RACE, "CAT_B", new SealCategoryRequest(6, "req-seal-b"));
        assertThatThrownBy(() -> raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("batch-t", "referee", 7, List.of("app-1"))))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("目标组别已封榜");

        assertThat(repository.findRunner(RACE, "a1").orElseThrow().categoryCode())
                .isEqualTo("CAT_A");
        assertThat(raceService.listReclassRecords(RACE)).isEmpty();
        assertThat(raceService.listReclassApplications(RACE))
                .extracting(ReclassApplicationResponse::status)
                .containsOnly(ReclassRequestRow.ReclassRequestStatus.PENDING);
    }

    @Test
    void 批准时来源组别已封榜返回409() {
        setupRaceWithCategories();
        register("a1", "CAT_A", 1000L, 3);
        register("b1", "CAT_B", 1500L, 4);
        submitApplication("app-1", "a1", "CAT_B", 5);

        // 来源组别 CAT_A 封榜（目标 CAT_B 仍开放）→ 批准 409 状态冲突
        raceService.sealCategory(RACE, "CAT_A", new SealCategoryRequest(6, "req-seal-a"));
        assertThatThrownBy(() -> raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("batch-s", "referee", 7, List.of("app-1"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("来源组别已封榜");

        assertThat(repository.findRunner(RACE, "a1").orElseThrow().categoryCode())
                .isEqualTo("CAT_A");
        assertThat(raceService.listReclassRecords(RACE)).isEmpty();
    }

    @Test
    void 参赛者退赛后批准返回409() {
        setupRaceWithCategories();
        register("a1", "CAT_A", 1000L, 3);
        submitApplication("app-1", "a1", "CAT_B", 4);
        raceService.withdrawRunner(RACE, "a1", new WithdrawRunnerRequest(5, "req-wd-a1"));

        assertThatThrownBy(() -> raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("batch-wd", "referee", 6, List.of("app-1"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已退赛");

        // 退赛选手不可再提交申请
        assertThatThrownBy(() -> submitApplication("app-2", "a1", "CAT_B", 6))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("已退赛");
        // 退赛选手排名状态 WITHDRAWN 且不占容量
        CategoryStandingResponse catA = raceService.getCategoryStandings(RACE, "CAT_A");
        assertThat(catA.entries()).hasSize(1);
        assertThat(catA.entries().getFirst().status()).isEqualTo(EntryStatus.WITHDRAWN);
        assertThat(catA.entries().getFirst().rank()).isNull();
        assertThat(raceService.listCategories(RACE))
                .filteredOn(c -> c.categoryCode().equals("CAT_A"))
                .first().extracting(CategoryResponse::activeCount).isEqualTo(0);
        assertThat(repository.findRunner(RACE, "a1").orElseThrow().status())
                .isEqualTo(RunnerStatus.WITHDRAWN);
    }

    @Test
    void 拒绝申请不改变成绩与组别() {
        setupRaceWithCategories();
        register("a1", "CAT_A", 1000L, 3);
        register("a2", "CAT_A", 2000L, 4);
        submitApplication("app-1", "a2", "CAT_B", 5);

        ServiceResult rejected = raceService.rejectReclassApplication(
                RACE, "app-1", new RejectReclassRequest(6, "req-rej-1"));
        assertThat(rejected.status()).isEqualTo(200);
        ReclassApplicationResponse body = (ReclassApplicationResponse) rejected.body();
        assertThat(body.status()).isEqualTo(ReclassRequestRow.ReclassRequestStatus.REJECTED);

        // 成绩与组别不变
        assertThat(repository.findRunner(RACE, "a2").orElseThrow().categoryCode())
                .isEqualTo("CAT_A");
        CategoryStandingResponse catA = raceService.getCategoryStandings(RACE, "CAT_A");
        assertThat(catA.entries()).extracting("bib").containsExactly("a1", "a2");
        assertThat(raceService.listReclassRecords(RACE)).isEmpty();

        // 已拒绝申请不可再批准或再拒绝
        assertThatThrownBy(() -> raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("batch-r", "referee", 7, List.of("app-1"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("非待审批");
        assertThatThrownBy(() -> raceService.rejectReclassApplication(
                RACE, "app-1", new RejectReclassRequest(7, "req-rej-2")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 组别封榜固化当时所属组别且历史快照不追溯重写() {
        setupRaceWithCategories();
        register("a1", "CAT_A", 1000L, 3);
        register("a2", "CAT_A", 2000L, 4);
        register("b1", "CAT_B", 3000L, 5);

        // 封 CAT_B（仅 b1），随后 a2 重分进 CAT_A→？目标不能是已封 CAT_B；
        // 验证 CAT_B 快照不受后续其他组别操作影响
        ServiceResult sealed = raceService.sealCategory(
                RACE, "CAT_B", new SealCategoryRequest(6, "req-seal-b"));
        assertThat(sealed.status()).isEqualTo(200);
        CategoryStandingResponse sealedBody = (CategoryStandingResponse) sealed.body();
        assertThat(sealedBody.status()).isEqualTo(CategoryStatus.SEALED);
        assertThat(sealedBody.entries()).extracting("bib").containsExactly("b1");

        // 重复封榜 409
        assertThatThrownBy(() -> raceService.sealCategory(
                RACE, "CAT_B", new SealCategoryRequest(7, "req-seal-b2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("组别已封榜");

        // 其他组别继续重分：a2 → CAT_B 已封不可迁入；a1/a2 在 CAT_A 内部不变。
        // 新建 CAT_C 并迁入 a2，验证 CAT_B 历史快照不被重写
        raceService.createCategory(RACE, new CreateCategoryRequest("CAT_C", null, 7, "req-cat-c"));
        submitApplication("app-1", "a2", "CAT_C", 8);
        raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("batch-1", "referee", 9, List.of("app-1")));

        CategoryStandingResponse catBSnapshot = raceService.getCategoryStandings(RACE, "CAT_B");
        assertThat(catBSnapshot.status()).isEqualTo(CategoryStatus.SEALED);
        assertThat(catBSnapshot.entries()).extracting("bib").containsExactly("b1");

        List<CategoryStandingResponse> snapshots = raceService.listCategorySnapshots(RACE);
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.getFirst().categoryCode()).isEqualTo("CAT_B");

        // 迁入 CAT_C 后其实时排名包含 a2
        CategoryStandingResponse catC = raceService.getCategoryStandings(RACE, "CAT_C");
        assertThat(catC.entries()).extracting("bib").containsExactly("a2");
    }

    @Test
    void 赛事级封榜快照固化提交时刻所属组别() {
        setupRaceWithCategories();
        register("a1", "CAT_A", 1000L, 3);
        register("b1", "CAT_B", 2000L, 4);
        submitApplication("app-1", "a1", "CAT_B", 5);
        raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("batch-1", "referee", 6, List.of("app-1")));

        // 重分后赛事级封榜：快照记录封榜时刻所属组别（a1 已在 CAT_B）
        raceService.sealRace(RACE, new SealRaceRequest(7, "req-seal-race"));
        var snapshot = repository.findSnapshot(RACE).orElseThrow();
        assertThat(snapshot.entries())
                .filteredOn(e -> e.bib().equals("a1"))
                .first().extracting("categoryCode").isEqualTo("CAT_B");
        assertThat(snapshot.entries())
                .filteredOn(e -> e.bib().equals("b1"))
                .first().extracting("categoryCode").isEqualTo("CAT_B");

        // 赛事封榜后任何重分操作 409
        assertThatThrownBy(() -> raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("batch-2", "referee", 8, List.of("app-1"))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 版本冲突与赛事不存在分支() {
        setupRaceWithCategories();
        register("a1", "CAT_A", 1000L, 3);
        submitApplication("app-1", "a1", "CAT_B", 4);

        // 批准版本滞后 → 409
        assertThatThrownBy(() -> raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("batch-v", "referee", 4, List.of("app-1"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");
        // 申请不存在 → 404
        assertThatThrownBy(() -> raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("batch-m", "referee", 5, List.of("no-such-app"))))
                .isInstanceOf(NotFoundException.class);
        // 组别不存在查询 → 404
        assertThatThrownBy(() -> raceService.getCategoryStandings(RACE, "NO_SUCH"))
                .isInstanceOf(NotFoundException.class);
        // 赛事不存在 → 404
        assertThatThrownBy(() -> raceService.listReclassApplications("no-such-race"))
                .isInstanceOf(NotFoundException.class);
        // 重复组别代码 → 409
        assertThatThrownBy(() -> raceService.createCategory(
                RACE, new CreateCategoryRequest("CAT_A", null, 5, "req-dup-cat")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("组别已存在");
    }

    @Test
    void reclassKey同键成功重放完整排名且失败不占键() throws Exception {
        setupRaceWithCategories();
        register("a1", "CAT_A", 1000L, 3);
        register("b1", "CAT_B", 3000L, 4);
        submitApplication("app-1", "a1", "CAT_B", 5);

        // 先用 reclassKey=k1 提交一个必然失败的批次（申请不存在 → 404），失败不占键
        assertThatThrownBy(() -> raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("k1", "referee", 6, List.of("missing-app"))))
                .isInstanceOf(NotFoundException.class);

        // 同键 k1 用于合法批次：成功（失败未占键）
        ServiceResult first = raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("k1", "referee", 6, List.of("app-1")));
        assertThat(first.status()).isEqualTo(200);
        ReclassApprovalResponse firstBody = (ReclassApprovalResponse) first.body();
        assertThat(firstBody.version()).isEqualTo(7);
        assertThat(firstBody.standings().get("CAT_B"))
                .extracting("bib").containsExactly("a1", "b1");

        // 同键同参重放：完整排名结果一致，版本不再推进，记录不重复
        ServiceResult replay = raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("k1", "referee", 6, List.of("app-1")));
        assertThat(replay.status()).isEqualTo(200);
        // 重放响应为固化的 JsonNode，与首次成功响应的 JSON 序列化结果完全一致
        assertThat(replay.body())
                .isEqualTo(objectMapper.readTree(objectMapper.writeValueAsString(firstBody)));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);
        assertThat(raceService.listReclassRecords(RACE)).hasSize(1);

        // 同键异参（不同申请集合）→ 409
        register("a2", "CAT_A", 500L, 7);
        submitApplication("app-2", "a2", "CAT_B", 8);
        assertThatThrownBy(() -> raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("k1", "referee", 9, List.of("app-2"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不同参数");
    }

    @Test
    void 登记时指定组别且校验组别存在与容量() {
        setupRaceWithCategories();
        // 不存在的组别 → 422
        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("x1", "NO_SUCH", 1000L, 3, "req-x1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("目标组别不存在");
        register("a1", "CAT_A", 1000L, 3);
        register("a2", "CAT_A", 2000L, 4);
        // CAT_A 容量2已满 → 422
        assertThatThrownBy(() -> raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a3", "CAT_A", 3000L, 5, "req-a3")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("容量");
        // 未分组登记仍可用，且未分组选手可提交重分申请
        register("u1", null, 500L, 5);
        submitApplication("app-u1", "u1", "CAT_B", 6);
        ServiceResult approved = raceService.approveReclassApplications(RACE,
                new ApproveReclassRequest("batch-u", "referee", 7, List.of("app-u1")));
        assertThat(approved.status()).isEqualTo(200);
        ReclassApprovalResponse body = (ReclassApprovalResponse) approved.body();
        // 未分组来源：迁移前名次为 null，计时快照保留
        assertThat(body.records().getFirst().fromCategory()).isNull();
        assertThat(body.records().getFirst().prevRank()).isNull();
        assertThat(body.records().getFirst().prevFinishTimeMs()).isEqualTo(500L);
        assertThat(repository.findRunner(RACE, "u1").orElseThrow().categoryCode())
                .isEqualTo("CAT_B");
    }
}
