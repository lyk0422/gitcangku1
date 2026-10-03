package com.example.starter.race.service;

import com.example.starter.race.api.ApproveReclassRequest;
import com.example.starter.race.api.CategoryStandingResponse;
import com.example.starter.race.api.ConfigureCategoriesRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.ReclassApprovalResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.SealCategoryRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitReclassApplicationRequest;
import com.example.starter.race.persistence.SnapshotRow;
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
 * 组别封榜冻结与 reclassKey 幂等的 H2 数据库测试。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class ReclassSealIdempotencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-reclass-seal";

    @Autowired
    private RaceService raceService;

    @Autowired
    private ObjectMapper objectMapper;

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
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest(bib, category, finishMs, version, "req-reg-" + bib));
    }

    private String submit(String bib, String target, int version) {
        String requestId = "req-app-" + bib + "-" + target + "-" + version;
        raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest(bib, target, "理由-" + bib, version, requestId));
        return "rca-" + requestId;
    }

    @Test
    void 涉及已封榜组别的批次整批422() {
        initRace();
        register("a", "ELITE", 1000L, 2);   // v3
        register("c", "AMATEUR", 1500L, 3); // v4
        String appA = submit("a", "AMATEUR", 4); // v5
        String appC = submit("c", "VETERAN", 5); // v6
        raceService.sealCategory(RACE, "AMATEUR", new SealCategoryRequest(6, "req-seal-cat")); // v7

        // 目标组别 AMATEUR 已封榜 -> 422
        assertThatThrownBy(() -> raceService.approveReclassBatch(RACE,
                new ApproveReclassRequest("referee", List.of(appA), 7, "rk-1")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("已封榜组别");
        // 来源组别 AMATEUR 已封榜 -> 422
        assertThatThrownBy(() -> raceService.approveReclassBatch(RACE,
                new ApproveReclassRequest("referee", List.of(appC), 7, "rk-2")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("已封榜组别");
        // 提交到已封榜组别 -> 409；重复封榜 -> 409
        assertThatThrownBy(() -> raceService.submitReclassApplication(RACE,
                new SubmitReclassApplicationRequest("a", "AMATEUR", "理由", 7, "req-app-x")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.sealCategory(RACE, "AMATEUR",
                new SealCategoryRequest(7, "req-seal-cat-2")))
                .isInstanceOf(ConflictException.class);
        // 全部失败不占版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(7);
    }

    @Test
    void 组别封榜固化快照_赛事封榜仅固化当前组别_历史快照不重写() {
        initRace();
        register("a", "ELITE", 1000L, 2);     // v3
        register("b", "ELITE", 2000L, 3);     // v4
        register("c", "AMATEUR", 1500L, 4);   // v5
        register("d", "AMATEUR", 3000L, 5);   // v6
        // v7: ELITE 组别封榜，固化 a/b 排名
        raceService.sealCategory(RACE, "ELITE", new SealCategoryRequest(6, "req-seal-elite"));
        CategoryStandingResponse sealedElite = raceService.getCategoryResults(RACE, "ELITE");
        assertThat(sealedElite.sealed()).isTrue();
        assertThat(sealedElite.version()).isEqualTo(7);
        assertThat(sealedElite.sealedAt()).isEqualTo(FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());
        assertThat(sealedElite.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        // v8: d 提交 AMATEUR->VETERAN 申请；v9: 批准（不涉及已封榜的 ELITE）
        String appD = submit("d", "VETERAN", 7);
        ServiceResult approved = raceService.approveReclassBatch(RACE,
                new ApproveReclassRequest("referee", List.of(appD), 8, "rk-d"));
        assertThat(approved.status()).isEqualTo(200);
        // v10: 赛事封榜，仅固化提交时刻所属组别
        raceService.sealRace(RACE, new SealRaceRequest(9, "req-seal-race"));
        SnapshotRow snapshot = repository.findSnapshot(RACE).orElseThrow();
        assertThat(snapshot.entries()).hasSize(4);
        assertThat(snapshot.entries())
                .filteredOn(entry -> entry.bib().equals("d"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.categoryCode()).isEqualTo("VETERAN"));
        assertThat(snapshot.entries())
                .filteredOn(entry -> entry.bib().equals("a"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.categoryCode()).isEqualTo("ELITE"));
        // 历史组别快照不追溯重写：仍是 v7 的 a/b 排名
        CategoryStandingResponse historical = raceService.getCategorySnapshot(RACE, "ELITE");
        assertThat(historical.version()).isEqualTo(7);
        assertThat(historical.entries()).extracting(ResultEntryResponse::bib)
                .containsExactly("a", "b");
        assertThat(historical.entries()).extracting(ResultEntryResponse::rank)
                .containsExactly(1, 2);
        // 赛事级快照也可查
        StandingResponse raceSnapshot = raceService.getSnapshot(RACE);
        assertThat(raceSnapshot.entries()).hasSize(4);
    }

    @Test
    void reclassKey同键同参重放完整排名_异参409() throws Exception {
        initRace();
        register("a", "ELITE", 1000L, 2);   // v3
        register("c", "AMATEUR", 1500L, 3); // v4
        String appA = submit("a", "AMATEUR", 4); // v5
        ApproveReclassRequest request = new ApproveReclassRequest(
                "referee", List.of(appA), 5, "rk-idem");

        ServiceResult first = raceService.approveReclassBatch(RACE, request);
        assertThat(first.status()).isEqualTo(200);
        // 同键同参重放：原样返回完整排名结果，不产生第二次迁移
        ServiceResult replayed = raceService.approveReclassBatch(RACE, request);
        assertThat(replayed.status()).isEqualTo(200);
        // 重放体为反序列化的 JsonNode，数值节点类型可能与原对象不同，按序列化文本比对。
        String expected = objectMapper.writeValueAsString(first.body());
        String actual = objectMapper.writeValueAsString(replayed.body());
        assertThat(actual).isEqualTo(expected);
        assertThat(((ReclassApprovalResponse) first.body()).standings()).hasSize(2);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(6);
        assertThat(raceService.getReclassRecords(RACE).records()).hasSize(1);
        // 同键异参（不同操作者）-> 409
        assertThatThrownBy(() -> raceService.approveReclassBatch(RACE,
                new ApproveReclassRequest("other", List.of(appA), 5, "rk-idem")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void reclassKey失败不占键_修正后同键可成功() {
        initRace();
        register("a", "ELITE", 1000L, 2);   // v3
        register("b", "ELITE", 2000L, 3);   // v4
        register("c", "AMATEUR", 1500L, 4); // v5
        String appC = submit("c", "ELITE", 5); // v6：目标 ELITE 已满

        // 容量超限失败 -> 不占 reclassKey
        assertThatThrownBy(() -> raceService.approveReclassBatch(RACE,
                new ApproveReclassRequest("referee", List.of(appC), 6, "rk-retry")))
                .isInstanceOf(UnprocessableEntityException.class);
        // 同键换成合法参数（a 先迁出腾名额，同批互换）可成功
        String appA = submit("a", "VETERAN", 6); // v7
        ServiceResult result = raceService.approveReclassBatch(RACE,
                new ApproveReclassRequest("referee", List.of(appA, appC), 7, "rk-retry"));
        assertThat(result.status()).isEqualTo(200);
        assertThat(raceService.getCategoryResults(RACE, "ELITE").entries())
                .extracting(ResultEntryResponse::bib).containsExactly("c", "b");
    }
}