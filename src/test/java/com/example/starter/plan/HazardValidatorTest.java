package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.model.HazardCar;
import com.example.starter.plan.model.HazardConfigVersion;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.service.HazardValidator;
import com.example.starter.plan.service.HazardValidator.EffectiveConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * 危险品隔离校验引擎纯单元测试：重复位置、互斥间隔（实际值/要求值/差额）、
 * 限制区段授权、配置无效与列车不在完整路径等分支，不依赖 Spring 与数据库。
 */
class HazardValidatorTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-22T01:00:00Z");

    private final HazardValidator validator = new HazardValidator();

    private Occupancy occ(String train, String section) {
        return new Occupancy(0, 1, 0, train, section, T0, T1);
    }

    private HazardCar car(String train, int position, String hazardClass) {
        return new HazardCar(0, 1, train, position, hazardClass);
    }

    private EffectiveConfig config(Map<String, Map<String, Integer>> separations,
                                   SortedSet<String> restrictedSections,
                                   TreeMap<String, SortedSet<String>> openBySection) {
        HazardConfigVersion version = new HazardConfigVersion(7L, 3,
                Instant.parse("2026-01-01T00:00:00Z"), null, "fingerprint-xyz", 0L);
        return new EffectiveConfig(version, separations, restrictedSections, openBySection);
    }

    private EffectiveConfig configWithRulesAndOpens(
            Map<String, Map<String, Integer>> separations,
            TreeMap<String, SortedSet<String>> openBySection) {
        return config(separations, new TreeSet<>(openBySection.keySet()), openBySection);
    }

    @Test
    void noEffectiveConfigMeansNoRestriction() {
        List<Occupancy> occupancies = List.of(occ("G1", "SEC-ANY"));
        List<HazardCar> cars = List.of(car("G1", 1, "A"), car("G1", 2, "B"));
        assertThat(validator.validate(occupancies, cars, null)).isEmpty();
    }

    @Test
    void duplicatePositionsReportedAsPairs() {
        List<Occupancy> occupancies = List.of(occ("G1", "SEC-1"));
        List<HazardCar> cars = List.of(
                car("G1", 5, "A"), car("G1", 5, "B"), car("G1", 5, "C"));

        List<Map<String, Object>> conflicts =
                validator.validateFormation(occupancies, cars);

        assertThat(conflicts).hasSize(3);
        assertThat(conflicts).allSatisfy(d -> {
            assertThat(d.get("type")).isEqualTo(HazardValidator.TYPE_DUPLICATE_POSITION);
            assertThat(d.get("trainNo")).isEqualTo("G1");
            assertThat(d.get("position")).isEqualTo(5);
        });
        // 冲突对按类别字典序两两列出
        assertThat(conflicts).extracting(d -> d.get("hazardClassA") + "|" + d.get("hazardClassB"))
                .containsExactly("A|B", "A|C", "B|C");
    }

    @Test
    void hazardCarOnTrainOutsidePathRejected() {
        List<Occupancy> occupancies = List.of(occ("G1", "SEC-1"));
        List<HazardCar> cars = List.of(car("G2", 1, "A"));

        List<Map<String, Object>> conflicts =
                validator.validateFormation(occupancies, cars);

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).get("type"))
                .isEqualTo(HazardValidator.TYPE_TRAIN_NOT_IN_PATH);
        assertThat(conflicts.get(0).get("trainNo")).isEqualTo("G2");
    }

    @Test
    void separationEnoughWhenGapMeetsRequirement() {
        TreeMap<String, Map<String, Integer>> rules = new TreeMap<>();
        rules.put("A", new TreeMap<>(Map.of("B", 3)));
        EffectiveConfig cfg = configWithRulesAndOpens(rules, new TreeMap<>());

        // 位置 1 与 5：实际相隔 3 节（5-1-1），满足要求 3
        List<Map<String, Object>> conflicts = validator.validate(
                List.of(occ("G1", "SEC-1")),
                List.of(car("G1", 1, "A"), car("G1", 5, "B")), cfg);
        assertThat(conflicts).isEmpty();
    }

    @Test
    void separationInsufficientReportsActualRequiredAndShortfall() {
        TreeMap<String, Map<String, Integer>> rules = new TreeMap<>();
        rules.put("A", new TreeMap<>(Map.of("B", 3)));
        EffectiveConfig cfg = configWithRulesAndOpens(rules, new TreeMap<>());

        // 位置 1 与 4：实际相隔 2 节，要求 3，差额 1
        List<Map<String, Object>> conflicts = validator.validate(
                List.of(occ("G1", "SEC-1")),
                List.of(car("G1", 4, "B"), car("G1", 1, "A")), cfg);

        assertThat(conflicts).hasSize(1);
        Map<String, Object> d = conflicts.get(0);
        assertThat(d.get("type")).isEqualTo(HazardValidator.TYPE_SEPARATION_INSUFFICIENT);
        assertThat(d.get("trainNo")).isEqualTo("G1");
        assertThat(d.get("hazardClassA")).isEqualTo("A");
        assertThat(d.get("hazardClassB")).isEqualTo("B");
        assertThat(d.get("positionA")).isEqualTo(1);
        assertThat(d.get("positionB")).isEqualTo(4);
        assertThat(d.get("actualCars")).isEqualTo(2);
        assertThat(d.get("requiredCars")).isEqualTo(3);
        assertThat(d.get("shortfallCars")).isEqualTo(1);
    }

    @Test
    void adjacentCarsOfSameClassHaveNoRuleAndPass() {
        EffectiveConfig cfg = configWithRulesAndOpens(new TreeMap<>(), new TreeMap<>());
        // 无互斥规则：同类别相邻车厢合法
        List<Map<String, Object>> conflicts = validator.validate(
                List.of(occ("G1", "SEC-1")),
                List.of(car("G1", 1, "A"), car("G1", 2, "A")), cfg);
        assertThat(conflicts).isEmpty();
    }

    @Test
    void separationRuleIsPerTrain() {
        TreeMap<String, Map<String, Integer>> rules = new TreeMap<>();
        rules.put("A", new TreeMap<>(Map.of("B", 5)));
        EffectiveConfig cfg = configWithRulesAndOpens(rules, new TreeMap<>());

        // 不同列车各挂一节，间隔要求不跨列车适用
        List<Map<String, Object>> conflicts = validator.validate(
                List.of(occ("G1", "SEC-1"), occ("G2", "SEC-1")),
                List.of(car("G1", 1, "A"), car("G2", 1, "B")), cfg);
        assertThat(conflicts).isEmpty();
    }

    @Test
    void unauthorizedClassThroughRestrictedSectionRejectedWithOpenList() {
        TreeMap<String, SortedSet<String>> opens = new TreeMap<>();
        opens.put("SEC-R", new TreeSet<>(List.of("A", "C")));
        EffectiveConfig cfg = configWithRulesAndOpens(new TreeMap<>(), opens);

        List<Map<String, Object>> conflicts = validator.validate(
                List.of(occ("G1", "SEC-R")),
                List.of(car("G1", 1, "B")), cfg);

        assertThat(conflicts).hasSize(1);
        Map<String, Object> d = conflicts.get(0);
        assertThat(d.get("type")).isEqualTo(HazardValidator.TYPE_CLASS_UNAUTHORIZED);
        assertThat(d.get("sectionId")).isEqualTo("SEC-R");
        assertThat(d.get("trainNo")).isEqualTo("G1");
        assertThat(d.get("position")).isEqualTo(1);
        assertThat(d.get("hazardClass")).isEqualTo("B");
        assertThat(d.get("openHazardClasses")).isEqualTo(List.of("A", "C"));
    }

    @Test
    void authorizedClassPassesRestrictedSectionAndUnrestrictedSectionUnchecked() {
        TreeMap<String, SortedSet<String>> opens = new TreeMap<>();
        opens.put("SEC-R", new TreeSet<>(List.of("A")));
        EffectiveConfig cfg = configWithRulesAndOpens(new TreeMap<>(), opens);

        // A 在开放清单内；另一节 Z 所在列车只经过非限制区段，授权校验不适用 → 无冲突
        assertThat(validator.validate(
                List.of(occ("G1", "SEC-R"), occ("G2", "SEC-FREE")),
                List.of(car("G1", 1, "A"), car("G2", 1, "Z")), cfg)).isEmpty();
    }

    @Test
    void trainNotPassingRestrictedSectionIsNotCheckedForIt() {
        TreeMap<String, SortedSet<String>> opens = new TreeMap<>();
        opens.put("SEC-R", new TreeSet<>(List.of("A")));
        EffectiveConfig cfg = configWithRulesAndOpens(new TreeMap<>(), opens);

        // G1 的完整路径只含非限制区段；即便 B 不在 SEC-R 清单内也不判冲突
        List<Map<String, Object>> conflicts = validator.validate(
                List.of(occ("G1", "SEC-OTHER")),
                List.of(car("G1", 1, "B")), cfg);
        assertThat(conflicts).isEmpty();
    }

    @Test
    void restrictedSectionWithEmptyOpenListIsInvalidConfig() {
        TreeMap<String, SortedSet<String>> opens = new TreeMap<>();
        opens.put("SEC-R", new TreeSet<>());
        SortedSet<String> restricted = new TreeSet<>(List.of("SEC-R"));
        EffectiveConfig cfg = config(new TreeMap<>(), restricted, opens);

        List<Map<String, Object>> conflicts = validator.validate(
                List.of(occ("G1", "SEC-R")),
                List.of(car("G1", 1, "A")), cfg);

        assertThat(conflicts).anySatisfy(d -> {
            assertThat(d.get("type")).isEqualTo(HazardValidator.TYPE_CONFIG_INVALID);
            assertThat(d.get("sectionId")).isEqualTo("SEC-R");
            assertThat(d.get("openHazardClasses")).isEqualTo(List.of());
        });
        // 配置无效同时该类别也未授权，两类冲突都列出
        assertThat(conflicts).extracting(d -> d.get("type"))
                .contains(HazardValidator.TYPE_CLASS_UNAUTHORIZED);
    }

    @Test
    void multipleConflictsAllReturnedStableSorted() {
        TreeMap<String, Map<String, Integer>> rules = new TreeMap<>();
        rules.put("A", new TreeMap<>(Map.of("B", 5)));
        TreeMap<String, SortedSet<String>> opens = new TreeMap<>();
        opens.put("SEC-R", new TreeSet<>(List.of("A")));
        EffectiveConfig cfg = configWithRulesAndOpens(rules, opens);

        // 间隔不足（实际 0，要求 5）+ B 未授权，两类冲突都列出，不提前短路
        List<Map<String, Object>> all = validator.validate(
                List.of(occ("G1", "SEC-R")),
                List.of(car("G1", 1, "A"), car("G1", 2, "B")), cfg);
        assertThat(all).extracting(d -> d.get("type"))
                .containsExactlyInAnyOrder(
                        HazardValidator.TYPE_SEPARATION_INSUFFICIENT,
                        HazardValidator.TYPE_CLASS_UNAUTHORIZED);

        // 无配置时同样编组无任何限制
        assertThat(validator.validate(
                List.of(occ("G1", "SEC-R")),
                List.of(car("G1", 1, "A"), car("G1", 2, "B")), null)).isEmpty();
    }
}
