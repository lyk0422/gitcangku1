package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
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
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.domain.EntryStatus;
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
 * 分组晋级名单的 H2 数据库测试：分组划分、晋级与补位、并列超额、
 * 快照冻结、撤销重生成与幂等边界。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class AdvancementServiceH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-adv";

    @Autowired
    private RaceService raceService;

    @Autowired
    private ObjectMapper objectMapper;

    private int createRace(String raceId) {
        raceService.createRace(new CreateRaceRequest(raceId, "req-create-" + raceId));
        return 1;
    }

    private int register(String raceId, int version, String bib, Long finishMs) {
        raceService.registerRunner(raceId, new RegisterRunnerRequest(
                bib, finishMs, version, "req-reg-" + raceId + "-" + bib));
        return version + 1;
    }

    /** 登记 8 名选手：a1~a4 与 b1~b4，完赛耗时分别为 base+100 递增。 */
    private int registerEightRunners(String raceId, int version) {
        version = register(raceId, version, "a1", 100L);
        version = register(raceId, version, "a2", 200L);
        version = register(raceId, version, "a3", 300L);
        version = register(raceId, version, "a4", 400L);
        version = register(raceId, version, "b1", 150L);
        version = register(raceId, version, "b2", 250L);
        version = register(raceId, version, "b3", 350L);
        version = register(raceId, version, "b4", 450L);
        return version;
    }

    private int assignTwoGroups(String raceId, int version) {
        ServiceResult result = raceService.assignGroups(raceId, new AssignGroupsRequest(
                version, "req-groups-" + raceId,
                List.of(
                        new AssignGroupsRequest.GroupDefinition("A", List.of("a1", "a2", "a3", "a4")),
                        new AssignGroupsRequest.GroupDefinition("B", List.of("b1", "b2", "b3", "b4")))));
        assertThat(result.status()).isEqualTo(201);
        return version + 1;
    }

    @Test
    void 完整主流程_分组生成查询撤销后重新生成() {
        int version = createRace(RACE);
        version = registerEightRunners(RACE, version);
        version = assignTwoGroups(RACE, version);

        GroupsResponse groups = raceService.getGroups(RACE);
        assertThat(groups.version()).isEqualTo(version);
        assertThat(groups.groups()).hasSize(2);
        assertThat(groups.groups().get(0).groupCode()).isEqualTo("A");
        assertThat(groups.groups().get(0).bibs()).containsExactly("a1", "a2", "a3", "a4");
        assertThat(groups.groups().get(1).bibs()).containsExactly("b1", "b2", "b3", "b4");

        // Q=2、W=1：A/B 组前两名直接晋级，补位池 a3=300、b3=350、a4=400、b4=450 取 a3
        ServiceResult generated = raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-1", 2, 1, version, "req-gen-1"));
        assertThat(generated.status()).isEqualTo(201);
        version++;
        AdvancementResponse advancement = (AdvancementResponse) generated.body();
        assertThat(advancement.advancementKey()).isEqualTo("adv-1");
        assertThat(advancement.version()).isEqualTo(version);
        assertThat(advancement.expectedCount()).isEqualTo(5);
        assertThat(advancement.actualCount()).isEqualTo(5);
        assertThat(advancement.overflowReason()).isNull();
        assertThat(advancement.status()).isEqualTo("ACTIVE");
        assertThat(advancement.generatedAt()).isEqualTo(
                FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());
        assertThat(advancement.entries())
                .extracting("bib", "groupCode", "type", "rank")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a1", "A", "DIRECT", 1),
                        org.assertj.core.groups.Tuple.tuple("a2", "A", "DIRECT", 2),
                        org.assertj.core.groups.Tuple.tuple("b1", "B", "DIRECT", 1),
                        org.assertj.core.groups.Tuple.tuple("b2", "B", "DIRECT", 2),
                        org.assertj.core.groups.Tuple.tuple("a3", "A", "WILDCARD", 1));

        // 生效名单查询与快照内容一致
        AdvancementResponse active = raceService.getActiveAdvancement(RACE);
        assertThat(active.entries()).hasSize(5);
        assertThat(active.entries().get(4).totalTimeMs()).isEqualTo(300L);

        // 未晋级清单：a4、b3、b4，含实时成绩状态
        NonAdvancedResponse nonAdvanced = raceService.getNonAdvanced(RACE);
        assertThat(nonAdvanced.advancementKey()).isEqualTo("adv-1");
        assertThat(nonAdvanced.entries())
                .extracting("bib", "groupCode", "status")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a4", "A", EntryStatus.RANKED),
                        org.assertj.core.groups.Tuple.tuple("b3", "B", EntryStatus.RANKED),
                        org.assertj.core.groups.Tuple.tuple("b4", "B", EntryStatus.RANKED));

        // 同一赛事最多一份生效名单：重复生成 409
        int versionAfterGenerate = version;
        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-2", 2, 1, versionAfterGenerate, "req-gen-2")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("生效晋级名单");

        // 整份撤销后重新生成；原快照保留且键不复用
        ServiceResult revoked = raceService.revokeAdvancement(RACE,
                new RevokeAdvancementRequest(version, "req-rev-1"));
        assertThat(revoked.status()).isEqualTo(200);
        version++;
        AdvancementResponse revokedBody = (AdvancementResponse) revoked.body();
        assertThat(revokedBody.status()).isEqualTo("REVOKED");
        assertThat(revokedBody.revokedAt()).isEqualTo(
                FixedClockTestConfig.FIXED_INSTANT.toEpochMilli());

        assertThatThrownBy(() -> raceService.getActiveAdvancement(RACE))
                .isInstanceOf(NotFoundException.class);
        AdvancementResponse retained = raceService.getAdvancement(RACE, "adv-1");
        assertThat(retained.status()).isEqualTo("REVOKED");
        assertThat(retained.entries()).hasSize(5);

        int versionAfterRevoke = version;
        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-1", 2, 1, versionAfterRevoke, "req-gen-3")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("advancementKey");

        ServiceResult regenerated = raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-2", 1, 0, version, "req-gen-4"));
        assertThat(regenerated.status()).isEqualTo(201);
        AdvancementResponse second = (AdvancementResponse) regenerated.body();
        assertThat(second.entries()).hasSize(2);
        assertThat(second.entries())
                .extracting("bib", "type")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a1", "DIRECT"),
                        org.assertj.core.groups.Tuple.tuple("b1", "DIRECT"));
    }

    @Test
    void 并列跨过Q边界全部纳入且响应给出实际人数与超额原因() {
        int version = createRace(RACE);
        version = register(RACE, version, "a1", 100L);
        version = register(RACE, version, "a2", 100L);
        version = register(RACE, version, "a3", 300L);
        version = register(RACE, version, "b1", 500L);
        version = register(RACE, version, "b2", 600L);
        ServiceResult assigned = raceService.assignGroups(RACE, new AssignGroupsRequest(
                version, "req-groups-tie",
                List.of(
                        new AssignGroupsRequest.GroupDefinition("A", List.of("a1", "a2", "a3")),
                        new AssignGroupsRequest.GroupDefinition("B", List.of("b1", "b2")))));
        assertThat(assigned.status()).isEqualTo(201);
        version++;

        // Q=1：A 组第1名并列 a1/a2 全部纳入，实际 3 人超过计划 2 人
        ServiceResult generated = raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-tie", 1, 0, version, "req-gen-tie"));
        assertThat(generated.status()).isEqualTo(201);
        AdvancementResponse body = (AdvancementResponse) generated.body();
        assertThat(body.expectedCount()).isEqualTo(2);
        assertThat(body.actualCount()).isEqualTo(3);
        assertThat(body.overflowReason()).contains("并列");
        assertThat(body.entries())
                .extracting("bib", "type", "rank")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a1", "DIRECT", 1),
                        org.assertj.core.groups.Tuple.tuple("a2", "DIRECT", 1),
                        org.assertj.core.groups.Tuple.tuple("b1", "DIRECT", 1));
    }

    @Test
    void 分组有效选手不足Q时整次422且不生成名单不占requestId() {
        int version = createRace(RACE);
        version = register(RACE, version, "a1", 100L);
        version = register(RACE, version, "a2", 200L);
        version = register(RACE, version, "b1", 150L);
        // b2 无完赛计时，B 组有效选手不足 Q=2
        version = register(RACE, version, "b2", null);
        raceService.assignGroups(RACE, new AssignGroupsRequest(
                version, "req-groups-ins",
                List.of(
                        new AssignGroupsRequest.GroupDefinition("A", List.of("a1", "a2")),
                        new AssignGroupsRequest.GroupDefinition("B", List.of("b1", "b2")))));
        version++;

        int versionBeforeGenerate = version;
        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-ins", 2, 0, versionBeforeGenerate,
                        "req-gen-ins")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("B");

        // 未生成名单、版本未推进、requestId 未占用
        assertThat(repository.findAdvancement("adv-ins")).isEmpty();
        assertThat(repository.findActiveAdvancement(RACE)).isEmpty();
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(version);
        assertThat(repository.findIdempotency("req-gen-ins")).isEmpty();

        // 补齐 b2 计时后用同一 requestId 重试成功
        version = registerRevise(version);
        ServiceResult generated = raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-ins", 2, 0, version, "req-gen-ins"));
        assertThat(generated.status()).isEqualTo(201);
        assertThat(((AdvancementResponse) generated.body()).entries()).hasSize(4);
    }

    private int registerRevise(int version) {
        raceService.reviseTime(RACE, new ReviseTimeRequest("b2", 250L, version, "req-rev-b2"));
        return version + 1;
    }

    @Test
    void 缺检查点与取消资格选手不参与晋级() {
        int version = createRace(RACE);
        // 配置一个检查点（须在任何分段记录之前）
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(
                List.of(new ConfigureCheckpointsRequest.CheckpointDefinition("CP1", 1)),
                version, "req-cp"));
        version++;
        version = register(RACE, version, "a1", 1000L);
        version = register(RACE, version, "a2", 2000L);
        version = register(RACE, version, "b1", 1500L);
        version = register(RACE, version, "b2", 2500L);
        // a1、a2、b1 通过 CP1；b2 缺检查点
        version = submitTiming(version, "a1", "t-a1", 500L);
        version = submitTiming(version, "a2", "t-a2", 900L);
        version = submitTiming(version, "b1", "t-b1", 700L);
        raceService.assignGroups(RACE, new AssignGroupsRequest(
                version, "req-groups-cp",
                List.of(
                        new AssignGroupsRequest.GroupDefinition("A", List.of("a1", "a2")),
                        new AssignGroupsRequest.GroupDefinition("B", List.of("b1", "b2")))));
        version++;

        // b2 缺检查点 -> B 组有效选手不足 -> 422
        int versionWithMissing = version;
        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-cp", 2, 0, versionWithMissing, "req-gen-cp")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("B");

        // 取消资格同样不计入有效选手
        version = submitTiming(version, "b2", "t-b2", 800L);
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "pen-dq", "a2", "DISQUALIFY", null, version, "req-pen-dq"));
        version++;
        int versionWithDq = version;
        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-cp", 2, 0, versionWithDq, "req-gen-cp2")))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("A");

        // 撤销取消资格后生成成功：b2 覆盖检查点后为有效选手
        raceService.revokePenalty(RACE, "pen-dq",
                new RevokePenaltyRequest(version, "req-revpen"));
        version++;
        ServiceResult generated = raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-cp", 2, 0, version, "req-gen-cp3"));
        assertThat(generated.status()).isEqualTo(201);
        assertThat(((AdvancementResponse) generated.body()).entries())
                .extracting("bib")
                .containsExactly("a1", "a2", "b1", "b2");
    }

    private int submitTiming(int version, String bib, String timingId, long elapsedMillis) {
        raceService.submitTiming(RACE, bib, new SubmitTimingRequest(
                timingId, "CP1", elapsedMillis, version, "req-timing-" + timingId));
        return version + 1;
    }

    @Test
    void 快照不随后续计时修订处罚与封榜改写() {
        int version = createRace(RACE);
        version = registerEightRunners(RACE, version);
        version = assignTwoGroups(RACE, version);
        raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-frozen", 2, 1, version, "req-gen-frozen"));
        version++;

        // 生成后修订 a1 成绩并对 b1 加时：快照内容保持不变
        raceService.reviseTime(RACE, new ReviseTimeRequest("a1", 999L, version, "req-rev-a1"));
        version++;
        raceService.addPenalty(RACE, new AddPenaltyRequest(
                "pen-b1", "b1", "ADD_TIME", 5000L, version, "req-pen-b1"));
        version++;

        AdvancementResponse frozen = raceService.getActiveAdvancement(RACE);
        assertThat(frozen.entries())
                .extracting("bib", "totalTimeMs")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a1", 100L),
                        org.assertj.core.groups.Tuple.tuple("a2", 200L),
                        org.assertj.core.groups.Tuple.tuple("b1", 150L),
                        org.assertj.core.groups.Tuple.tuple("b2", 250L),
                        org.assertj.core.groups.Tuple.tuple("a3", 300L));

        // 封榜后快照仍可读且不变；封榜后禁止生成或撤销名单
        raceService.sealRace(RACE, new SealRaceRequest(version, "req-seal"));
        int sealedVersion = version + 1;
        AdvancementResponse afterSeal = raceService.getActiveAdvancement(RACE);
        assertThat(afterSeal.entries()).hasSize(5);
        assertThat(afterSeal.entries().get(0).totalTimeMs()).isEqualTo(100L);
        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-x", 1, 0, sealedVersion, "req-gen-x")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
        assertThatThrownBy(() -> raceService.revokeAdvancement(RACE,
                new RevokeAdvancementRequest(sealedVersion, "req-rev-x")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("封榜");
    }

    @Test
    void 生成名单幂等_同参重放异参409失败不占键() throws Exception {
        int version = createRace(RACE);
        version = registerEightRunners(RACE, version);
        version = assignTwoGroups(RACE, version);

        ServiceResult first = raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-idem", 2, 1, version, "req-gen-idem"));
        assertThat(first.status()).isEqualTo(201);

        // 同参重放：返回首次结果（规范化 JSON 一致），版本不再推进
        ServiceResult replayed = raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-idem", 2, 1, version, "req-gen-idem"));
        assertThat(replayed.status()).isEqualTo(201);
        // 重放响应体由存档 JSON 反序列化，数值节点类型可能与原对象不同，按规范化 JSON 文本比较
        assertThat(objectMapper.writeValueAsString(replayed.body()))
                .isEqualTo(objectMapper.writeValueAsString(first.body()));
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(version + 1);

        // 同 requestId 异参：409
        int versionBeforeReplay = version;
        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-idem", 1, 1, versionBeforeReplay,
                        "req-gen-idem")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");
    }

    @Test
    void 分组划分校验与一次性约束() {
        int version = createRace(RACE);
        version = register(RACE, version, "a1", 100L);
        version = register(RACE, version, "a2", 200L);
        version = register(RACE, version, "b1", 150L);
        version = register(RACE, version, "b2", 250L);
        int v = version;

        // 仅 1 个分组 -> 400
        assertThatThrownBy(() -> raceService.assignGroups(RACE, new AssignGroupsRequest(
                v, "req-g-1",
                List.of(new AssignGroupsRequest.GroupDefinition("A", List.of("a1", "a2"))))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("2~8");
        // 组内仅 1 人 -> 400
        assertThatThrownBy(() -> raceService.assignGroups(RACE, new AssignGroupsRequest(
                v, "req-g-2",
                List.of(
                        new AssignGroupsRequest.GroupDefinition("A", List.of("a1")),
                        new AssignGroupsRequest.GroupDefinition("B", List.of("b1", "b2"))))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("2~16");
        // 同一选手划入两个分组 -> 400
        assertThatThrownBy(() -> raceService.assignGroups(RACE, new AssignGroupsRequest(
                v, "req-g-3",
                List.of(
                        new AssignGroupsRequest.GroupDefinition("A", List.of("a1", "a2")),
                        new AssignGroupsRequest.GroupDefinition("B", List.of("a1", "b1"))))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("一个分组");
        // 未登记选手 -> 404
        assertThatThrownBy(() -> raceService.assignGroups(RACE, new AssignGroupsRequest(
                v, "req-g-4",
                List.of(
                        new AssignGroupsRequest.GroupDefinition("A", List.of("a1", "a2")),
                        new AssignGroupsRequest.GroupDefinition("B", List.of("b1", "zz"))))))
                .isInstanceOf(NotFoundException.class);
        // 版本冲突 -> 409
        assertThatThrownBy(() -> raceService.assignGroups(RACE, new AssignGroupsRequest(
                v + 1, "req-g-5",
                List.of(
                        new AssignGroupsRequest.GroupDefinition("A", List.of("a1", "a2")),
                        new AssignGroupsRequest.GroupDefinition("B", List.of("b1", "b2"))))))
                .isInstanceOf(ConflictException.class);
        // 失败不占 requestId：修正参数后同键成功
        ServiceResult assigned = raceService.assignGroups(RACE, new AssignGroupsRequest(
                v, "req-g-4",
                List.of(
                        new AssignGroupsRequest.GroupDefinition("A", List.of("a1", "a2")),
                        new AssignGroupsRequest.GroupDefinition("B", List.of("b1", "b2")))));
        assertThat(assigned.status()).isEqualTo(201);

        // 划分后不可改写 -> 409
        assertThatThrownBy(() -> raceService.assignGroups(RACE, new AssignGroupsRequest(
                v + 1, "req-g-6",
                List.of(
                        new AssignGroupsRequest.GroupDefinition("A", List.of("a1", "b1")),
                        new AssignGroupsRequest.GroupDefinition("B", List.of("a2", "b2"))))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("不可修改");
    }

    @Test
    void 生成名单前置校验() {
        int version = createRace(RACE);
        version = register(RACE, version, "a1", 100L);
        version = register(RACE, version, "a2", 200L);
        version = register(RACE, version, "b1", 150L);
        version = register(RACE, version, "b2", 250L);

        // 未划分分组 -> 409
        int beforeGroupsVersion = version;
        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-pre", 1, 0, beforeGroupsVersion,
                        "req-gen-pre")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("尚未划分分组");

        raceService.assignGroups(RACE, new AssignGroupsRequest(
                version, "req-groups-pre",
                List.of(
                        new AssignGroupsRequest.GroupDefinition("A", List.of("a1", "a2")),
                        new AssignGroupsRequest.GroupDefinition("B", List.of("b1", "b2")))));
        version++;
        int afterGroupsVersion = version;

        // Q/W 越界 -> 400
        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-q0", 0, 0, afterGroupsVersion, "req-gen-q0")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1~8");
        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-q9", 9, 0, afterGroupsVersion, "req-gen-q9")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1~8");
        assertThatThrownBy(() -> raceService.generateAdvancement(RACE,
                new GenerateAdvancementRequest("adv-w9", 1, 9, afterGroupsVersion, "req-gen-w9")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("0~8");

        // 无生效名单时撤销 -> 404
        assertThatThrownBy(() -> raceService.revokeAdvancement(RACE,
                new RevokeAdvancementRequest(afterGroupsVersion, "req-rev-none")))
                .isInstanceOf(NotFoundException.class);
    }
}
