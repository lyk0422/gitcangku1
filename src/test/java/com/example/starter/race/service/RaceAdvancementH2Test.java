package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AdvancementEntryResponse;
import com.example.starter.race.api.AdvancementResponse;
import com.example.starter.race.api.AssignGroupsRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.GenerateAdvancementRequest;
import com.example.starter.race.api.GroupsResponse;
import com.example.starter.race.api.NonAdvancedResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokeAdvancementRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.domain.AdvancementListStatus;
import com.example.starter.race.persistence.AdvancementListRow;
import com.example.starter.race.persistence.AdvancementRepository;
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
 * 分组划分与晋级名单的真实 H2 数据库测试：
 * 主流程、422 名额不足、并列超额、不可变快照、撤销重生成、幂等边界与封榜禁写。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class RaceAdvancementH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-adv";

    @Autowired
    private RaceService raceService;

    @Autowired
    private AdvancementRepository advancementRepository;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int create(String raceId) {
        raceService.createRace(new CreateRaceRequest(raceId, "req-create-" + raceId));
        return 1;
    }

    private int register(String raceId, String bib, Long finishTimeMs, int version) {
        raceService.registerRunner(raceId,
                new RegisterRunnerRequest(bib, finishTimeMs, version,
                        "req-reg-" + raceId + "-" + bib));
        return version + 1;
    }

    private AssignGroupsRequest.GroupDefinition group(String code, String... bibs) {
        return new AssignGroupsRequest.GroupDefinition(code, List.of(bibs));
    }

    @Test
    void 分组划分成功版本加一且之后不可改写() {
        int version = create(RACE);
        version = register(RACE, "a1", 1000L, version);
        version = register(RACE, "a2", 1100L, version);
        version = register(RACE, "b1", 1200L, version);
        version = register(RACE, "b2", 1300L, version);

        ServiceResult result = raceService.assignGroups(RACE,
                new AssignGroupsRequest(version, "req-groups",
                        List.of(group("A", "a1", "a2"), group("B", "b1", "b2"))));
        assertThat(result.status()).isEqualTo(201);
        GroupsResponse response = (GroupsResponse) result.body();
        assertThat(response.version()).isEqualTo(version + 1);
        assertThat(response.groups()).hasSize(2);
        assertThat(response.groups().getFirst().groupCode()).isEqualTo("A");
        assertThat(response.groups().getFirst().position()).isEqualTo(1);
        assertThat(response.groups().getFirst().members()).containsExactly("a1", "a2");

        GroupsResponse queried = raceService.getGroups(RACE);
        assertThat(queried.groups().get(1).members()).containsExactly("b1", "b2");

        // 再次划分一律409，即使版本号正确。
        final int nextVersion = version + 1;
        assertThatThrownBy(() -> raceService.assignGroups(RACE,
                new AssignGroupsRequest(nextVersion, "req-groups-2",
                        List.of(group("A", "a1", "a2"), group("B", "b1", "b2")))))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(version + 1);
    }

    @Test
    void 分组划分参数非法时返回400且不推进版本() {
        int version = create(RACE);
        version = register(RACE, "a1", 1000L, version);
        version = register(RACE, "a2", 1100L, version);
        version = register(RACE, "b1", 1200L, version);

        final int expectedVersion = version;
        // 只有1个分组。
        assertThatThrownBy(() -> raceService.assignGroups(RACE,
                new AssignGroupsRequest(expectedVersion, "req-g1", List.of(group("A", "a1", "a2")))))
                .isInstanceOf(BadRequestException.class);
        // 单组仅1人。
        assertThatThrownBy(() -> raceService.assignGroups(RACE,
                new AssignGroupsRequest(expectedVersion, "req-g2",
                        List.of(group("A", "a1"), group("B", "b1")))))
                .isInstanceOf(BadRequestException.class);
        // 选手跨组重复。
        assertThatThrownBy(() -> raceService.assignGroups(RACE,
                new AssignGroupsRequest(expectedVersion, "req-g3",
                        List.of(group("A", "a1", "a2"), group("B", "a1", "b1")))))
                .isInstanceOf(BadRequestException.class);
        // 成员未登记。
        assertThatThrownBy(() -> raceService.assignGroups(RACE,
                new AssignGroupsRequest(expectedVersion, "req-g4",
                        List.of(group("A", "a1", "ghost"), group("B", "a2", "b1")))))
                .isInstanceOf(BadRequestException.class);

        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(version);
    }

    @Test
    void 生成名单_直接晋级与跨组补位_未入组与无成绩者不参与() {
        int version = create(RACE);
        version = register(RACE, "a1", 1000L, version);
        version = register(RACE, "a2", 1100L, version);
        version = register(RACE, "a3", null, version);
        version = register(RACE, "b1", 1050L, version);
        version = register(RACE, "b2", 1150L, version);
        version = register(RACE, "b3", 1200L, version);
        version = register(RACE, "out", 900L, version); // 未入组，成绩再好也不参与
        raceService.assignGroups(RACE, new AssignGroupsRequest(version, "req-groups",
                List.of(group("A", "a1", "a2", "a3"), group("B", "b1", "b2", "b3"))));
        version++;

        ServiceResult result = raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-1", 1, 1, version, "req-adv-1"));
        assertThat(result.status()).isEqualTo(201);
        AdvancementResponse response = (AdvancementResponse) result.body();
        assertThat(response.status()).isEqualTo(AdvancementListStatus.ACTIVE);
        assertThat(response.version()).isEqualTo(version + 1);
        assertThat(response.directQuota()).isEqualTo(1);
        assertThat(response.wildcardQuota()).isEqualTo(1);
        assertThat(response.directCount()).isEqualTo(2);
        assertThat(response.wildcardCount()).isEqualTo(1);
        assertThat(response.advancedCount()).isEqualTo(3);
        assertThat(response.expectedCount()).isEqualTo(3);
        assertThat(response.overQuotaReasons()).isEmpty();
        // DIRECT 按分组顺序：a1（A组最快）、b1（B组最快）；WILDCARD 为跨组最快的 a2。
        assertThat(response.entries()).extracting(AdvancementEntryResponse::bib)
                .containsExactly("a1", "b1", "a2");
        assertThat(response.entries()).extracting(AdvancementEntryResponse::type)
                .containsExactly("DIRECT", "DIRECT", "WILDCARD");
        assertThat(response.entries().getLast().rank()).isEqualTo(1);

        NonAdvancedResponse nonAdvanced = raceService.getNonAdvanced(RACE);
        assertThat(nonAdvanced.advancementKey()).isEqualTo("adv-1");
        // 未晋级有效选手：A组 a3 无成绩不出现；B组 b2(1150)、b3(1200)。
        assertThat(nonAdvanced.groups()).hasSize(2);
        assertThat(nonAdvanced.groups().getFirst().members()).isEmpty();
        assertThat(nonAdvanced.groups().get(1).members()).containsExactly("b2", "b3");
    }

    @Test
    void 分组有效选手不足Q时整次422并指明分组且不生成名单() {
        int version = create(RACE);
        version = register(RACE, "a1", 1000L, version);
        version = register(RACE, "a2", 1100L, version);
        version = register(RACE, "b1", 1200L, version);
        version = register(RACE, "b2", 1300L, version);
        // b2 被取消资格，B组有效选手仅1名。
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "pen-dq", "b2", "DISQUALIFY", null, version, "req-dq"));
        version++;
        version = assignTwoGroups(version, "a1", "a2", "b1", "b2");

        final int generateVersion = version;
        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-x", 2, 0, generateVersion, "req-adv-x")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("groupCode=B")
                .hasMessageContaining("validRunners=1")
                .hasMessageContaining("directQuota=2");

        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(version);
        assertThat(advancementRepository.findActiveAdvancement(RACE)).isEmpty();
        assertThat(advancementRepository.findAdvancementHeader("adv-x")).isEmpty();

        // 失败不占 requestId：改用 Q=1 与同 requestId 可成功。
        ServiceResult retry = raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-2", 1, 0, version, "req-adv-x"));
        assertThat(retry.status()).isEqualTo(201);
    }

    @Test
    void 并列跨过Q与W边界时全部纳入并返回超额原因() {
        int version = create(RACE);
        version = register(RACE, "a1", 1000L, version);
        version = register(RACE, "a2", 1000L, version);
        version = register(RACE, "a3", 1000L, version);
        version = register(RACE, "b1", 2000L, version);
        version = register(RACE, "b2", 2100L, version);
        version = register(RACE, "b3", 2200L, version);
        raceService.assignGroups(RACE, new AssignGroupsRequest(version, "req-groups",
                List.of(group("A", "a1", "a2", "a3"), group("B", "b1", "b2", "b3"))));
        version++;

        // Q=2：A组三人同名次1全部DIRECT（3人）；B组 b1、b2 正常2人。
        // W=1：补位池仅 b3 一人。
        AdvancementResponse response = (AdvancementResponse) raceService.generateAdvancement(
                RACE, new GenerateAdvancementRequest(
                        "adv-tie", 2, 1, version, "req-adv-tie")).body();
        assertThat(response.entries()).extracting(AdvancementEntryResponse::bib)
                .containsExactly("a1", "a2", "a3", "b1", "b2", "b3");
        assertThat(response.directCount()).isEqualTo(5);
        assertThat(response.advancedCount()).isEqualTo(6);
        assertThat(response.expectedCount()).isEqualTo(5);
        assertThat(response.overQuotaReasons()).hasSize(1);
        AdvancementResponse.OverQuotaReason reason = response.overQuotaReasons().getFirst();
        assertThat(reason.boundary()).isEqualTo("DIRECT");
        assertThat(reason.groupCode()).isEqualTo("A");
        assertThat(reason.quota()).isEqualTo(2);
        assertThat(reason.tiedTimeMs()).isEqualTo(1000L);
        assertThat(reason.bibs()).containsExactly("a1", "a2", "a3");
    }

    @Test
    void 补位边界并列时全部纳入() {
        int version = create(RACE);
        version = register(RACE, "a1", 1000L, version);
        version = register(RACE, "a2", 1200L, version);
        version = register(RACE, "b1", 1000L, version);
        version = register(RACE, "b2", 1200L, version);
        version = register(RACE, "c1", 1000L, version);
        version = register(RACE, "c2", 1200L, version);
        raceService.assignGroups(RACE, new AssignGroupsRequest(version, "req-groups",
                List.of(group("A", "a1", "a2"), group("B", "b1", "b2"),
                        group("C", "c1", "c2"))));
        version++;

        AdvancementResponse response = (AdvancementResponse) raceService.generateAdvancement(
                RACE, new GenerateAdvancementRequest(
                        "adv-wtie", 1, 2, version, "req-adv-wtie")).body();
        assertThat(response.directCount()).isEqualTo(3);
        // a2/b2/c2 同为1200，W=2 边界被并列跨越，三人全部补位。
        assertThat(response.wildcardCount()).isEqualTo(3);
        assertThat(response.advancedCount()).isEqualTo(6);
        assertThat(response.overQuotaReasons()).hasSize(1);
        assertThat(response.overQuotaReasons().getFirst().boundary()).isEqualTo("WILDCARD");
        assertThat(response.overQuotaReasons().getFirst().bibs())
                .containsExactly("a2", "b2", "c2");
    }

    @Test
    void 名单快照固化后计时修订与处罚不改变名单() {
        int version = create(RACE);
        version = register(RACE, "a1", 1000L, version);
        version = register(RACE, "a2", 1100L, version);
        version = register(RACE, "b1", 1050L, version);
        version = register(RACE, "b2", 1150L, version);
        version = assignTwoGroups(version, "a1", "a2", "b1", "b2");

        AdvancementResponse before = (AdvancementResponse) raceService.generateAdvancement(
                RACE, new GenerateAdvancementRequest(
                        "adv-freeze", 1, 1, version, "req-adv-freeze")).body();
        int generatedVersion = before.version();
        assertThat(before.entries()).extracting(AdvancementEntryResponse::bib)
                .containsExactly("a1", "b1", "a2");

        // 生成后：a1 被加时（总耗时应变为2100）、b1 修订成绩变慢。
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "pen-late", "a1", "ADD_TIME", 1100L, generatedVersion, "req-pen-late"));
        int nextVersion = generatedVersion + 1;
        raceService.reviseTime(RACE,
                new ReviseTimeRequest("b1", 5000L, nextVersion, "req-rev-late"));

        AdvancementResponse after = raceService.getActiveAdvancement(RACE);
        assertThat(after.generatedAt()).isEqualTo(before.generatedAt());
        assertThat(after.version()).isEqualTo(generatedVersion);
        assertThat(after.entries()).extracting(AdvancementEntryResponse::bib)
                .containsExactly("a1", "b1", "a2");
        // 固化成绩保持生成时刻数值。
        assertThat(after.entries().getFirst().totalTimeMs()).isEqualTo(1000L);
        assertThat(after.entries().getFirst().penaltyMs()).isZero();
        assertThat(after.entries().get(1).totalTimeMs()).isEqualTo(1050L);
    }

    @Test
    void 重复生成409_撤销后可重新生成且原快照保留() {
        int version = create(RACE);
        version = register(RACE, "a1", 1000L, version);
        version = register(RACE, "a2", 1100L, version);
        version = register(RACE, "b1", 1050L, version);
        version = register(RACE, "b2", 1150L, version);
        version = assignTwoGroups(version, "a1", "a2", "b1", "b2");

        raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-old", 1, 1, version, "req-adv-old"));

        final int afterAdvVersion = version + 1;
        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-new", 1, 1, afterAdvVersion, "req-adv-new")))
                .isInstanceOf(ConflictException.class);
        // advancementKey 全局唯一：另一个赛事也不能复用 adv-old。
        String race2 = "race-adv-2";
        int v2 = create(race2);
        v2 = register(race2, "x1", 1000L, v2);
        v2 = register(race2, "x2", 1100L, v2);
        v2 = register(race2, "y1", 1050L, v2);
        v2 = register(race2, "y2", 1150L, v2);
        raceService.assignGroups(race2, new AssignGroupsRequest(v2, "req-groups-2",
                List.of(group("X", "x1", "x2"), group("Y", "y1", "y2"))));
        final int race2Version = v2 + 1;
        assertThatThrownBy(() -> raceService.generateAdvancement(race2,
                new GenerateAdvancementRequest("adv-old", 1, 0, race2Version, "req-adv-cross")))
                .isInstanceOf(ConflictException.class);

        // 撤销当前生效名单。
        int revokeVersion = version + 1;
        AdvancementResponse revoked = (AdvancementResponse) raceService.revokeAdvancement(
                RACE, new RevokeAdvancementRequest(revokeVersion, "req-revoke")).body();
        assertThat(revoked.status()).isEqualTo(AdvancementListStatus.REVOKED);
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(revoked.entries()).hasSize(3);
        assertThatThrownBy(() -> raceService.getActiveAdvancement(RACE))
                .isInstanceOf(NotFoundException.class);

        // 原快照（含条目）保留为 REVOKED。
        AdvancementListRow oldRow =
                advancementRepository.findAdvancementHeader("adv-old").orElseThrow();
        assertThat(oldRow.status()).isEqualTo(AdvancementListStatus.REVOKED);
        assertThat(advancementRepository.findAdvancementHistory(RACE)).hasSize(1);

        // 重新生成：新键、新名额，赛事再次有一份生效名单。
        AdvancementResponse regenerated = (AdvancementResponse) raceService
                .generateAdvancement(RACE, new GenerateAdvancementRequest(
                        "adv-regen", 2, 0, revokeVersion + 1, "req-adv-regen")).body();
        assertThat(regenerated.advancementKey()).isEqualTo("adv-regen");
        assertThat(regenerated.status()).isEqualTo(AdvancementListStatus.ACTIVE);
        assertThat(regenerated.directCount()).isEqualTo(4);
        assertThat(advancementRepository.findAdvancementHistory(RACE)).hasSize(2);
    }

    @Test
    void requestId同参重放首次结果_异参409() {
        int version = create(RACE);
        version = register(RACE, "a1", 1000L, version);
        version = register(RACE, "a2", 1100L, version);
        version = register(RACE, "b1", 1050L, version);
        version = register(RACE, "b2", 1150L, version);
        version = assignTwoGroups(version, "a1", "a2", "b1", "b2");

        GenerateAdvancementRequest request =
                new GenerateAdvancementRequest("adv-idem", 1, 1, version, "req-same");
        ServiceResult first = raceService.generateAdvancement(RACE, request);
        ServiceResult replay = raceService.generateAdvancement(RACE, request);
        assertThat(replay.status()).isEqualTo(first.status());
        // 重放体由幂等记录中原 JSON 反序列化而来，与首次响应 JSON 完全一致。
        assertThat(json(replay.body())).isEqualTo(json(first.body()));
        assertThat(advancementRepository.findActiveAdvancement(RACE)).isPresent();

        // 同 requestId 异参 → 409。
        final int idemVersion = version;
        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-other", 1, 1, idemVersion, "req-same")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 封榜后禁止生成与撤销名单() {
        int version = create(RACE);
        version = register(RACE, "a1", 1000L, version);
        version = register(RACE, "a2", 1100L, version);
        version = register(RACE, "b1", 1050L, version);
        version = register(RACE, "b2", 1150L, version);
        int groupVersion = version;
        version = assignTwoGroups(version, "a1", "a2", "b1", "b2");
        raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-seal", 1, 1, version, "req-adv-seal"));
        int afterAdvancement = version + 1;
        raceService.sealRace(RACE, new SealRaceRequest(afterAdvancement, "req-seal"));
        int sealedVersion = afterAdvancement + 1;

        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-after-seal", 1, 1,
                        sealedVersion, "req-adv-after")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> raceService.revokeAdvancement(RACE,
                new RevokeAdvancementRequest(sealedVersion, "req-revoke-after")))
                .isInstanceOf(ConflictException.class);
        // 封榜不改变已固化名单。
        assertThat(raceService.getActiveAdvancement(RACE).advancementKey())
                .isEqualTo("adv-seal");
        assertThat(groupVersion).isPositive();
    }

    @Test
    void 漏检查点选手不参与晋级() {
        int version = create(RACE);
        version = register(RACE, "a1", 5000L, version);
        version = register(RACE, "a2", 6000L, version);
        version = register(RACE, "b1", 5100L, version);
        version = register(RACE, "b2", 5200L, version);
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("cp1", 1),
                        new ConfigureCheckpointsRequest.CheckpointDefinition("cp2", 2)),
                version, "req-cps"));
        version++;
        // a1 只通过 cp1，缺少 cp2；其余人两点齐全。
        raceService.submitTiming(RACE, "a1", new SubmitTimingRequest(
                "t-a1-1", "cp1", 1000L, version, "req-t-a1-1"));
        version++;
        for (String[] spec : new String[][] {
                {"a2", "1000", "2000"}, {"b1", "1000", "2000"}, {"b2", "1000", "2000"}}) {
            String bib = spec[0];
            raceService.submitTiming(RACE, bib, new SubmitTimingRequest(
                    "t-" + bib + "-1", "cp1", Long.parseLong(spec[1]), version,
                    "req-t-" + bib + "-1"));
            version++;
            raceService.submitTiming(RACE, bib, new SubmitTimingRequest(
                    "t-" + bib + "-2", "cp2", Long.parseLong(spec[2]), version,
                    "req-t-" + bib + "-2"));
            version++;
        }
        version = assignTwoGroups(version, "a1", "a2", "b1", "b2");

        // Q=2 时 A 组有效选手仅 a2 一名 → 422。
        final int cpVersion = version;
        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-cp", 2, 0, cpVersion, "req-adv-cp")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("groupCode=A");

        // Q=1 可生成：a2 代表 A 组；漏点的 a1 不出现于晋级与未晋级名单。
        AdvancementResponse response = (AdvancementResponse) raceService
                .generateAdvancement(RACE, new GenerateAdvancementRequest(
                        "adv-cp2", 1, 1, cpVersion, "req-adv-cp2")).body();
        assertThat(response.entries()).extracting(AdvancementEntryResponse::bib)
                .containsExactly("a2", "b1", "b2");
        NonAdvancedResponse nonAdvanced = raceService.getNonAdvanced(RACE);
        assertThat(nonAdvanced.groups().getFirst().members()).isEmpty();
    }

    private int assignTwoGroups(int version, String a1, String a2, String b1, String b2) {
        raceService.assignGroups(RACE, new AssignGroupsRequest(version, "req-groups",
                List.of(group("A", a1, a2), group("B", b1, b2))));
        return version + 1;
    }
}
