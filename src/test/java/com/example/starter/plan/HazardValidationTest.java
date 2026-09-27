package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.model.HazardRule;
import com.example.starter.plan.service.HazardValidation;
import com.example.starter.plan.web.dto.HazardRuleRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 危险品隔离纯逻辑单元测试：位置重复、互斥类别间隔（实际/要求/差额）、
 * 限制区段授权，以及规则集与开放清单自身校验。不依赖 Spring 与数据库。
 */
class HazardValidationTest {

    private static HazardValidation.Car car(int position, String hazardClass) {
        return new HazardValidation.Car(position, hazardClass);
    }

    // ---------- 编组位置重复 ----------

    @Test
    void duplicatePositionsListAllConflictPairsSorted() {
        List<HazardValidation.Car> cars = List.of(
                car(3, null), car(3, "A"), car(3, "B"), car(5, "C"), car(5, null));

        List<Map<String, Object>> conflicts = HazardValidation.findDuplicatePositions(cars);

        assertThat(conflicts).hasSize(4);
        assertThat(conflicts.get(0).get("type")).isEqualTo("CONSIST_POSITION_DUPLICATE");
        // 先按位置、再按登记序号对排序
        assertThat(conflicts.get(0).get("position")).isEqualTo(3);
        assertThat(conflicts.get(0).get("firstIndex")).isEqualTo(0);
        assertThat(conflicts.get(0).get("otherIndex")).isEqualTo(1);
        assertThat(conflicts.get(3).get("position")).isEqualTo(5);
    }

    @Test
    void uniquePositionsHaveNoConflicts() {
        List<HazardValidation.Car> cars = List.of(car(1, null), car(2, "A"), car(3, null));
        assertThat(HazardValidation.findDuplicatePositions(cars)).isEmpty();
    }

    // ---------- 互斥类别间隔 ----------

    @Test
    void separationConflictReportsActualRequiredAndDeficit() {
        // A 在 1 和 8，B 在 4：(1,4) 距离 3 < 要求 5；(8,4) 距离 4 < 要求 5
        HazardRule rule = HazardRule.ordered("A", "B", 5);
        List<HazardValidation.Car> cars = List.of(car(1, "A"), car(4, "B"), car(8, "A"));

        List<Map<String, Object>> conflicts =
                HazardValidation.findSeparationConflicts(List.of(rule), cars);

        assertThat(conflicts).hasSize(2);
        Map<String, Object> first = conflicts.get(0);
        assertThat(first.get("type")).isEqualTo("HAZARD_SEPARATION");
        assertThat(first.get("classA")).isEqualTo("A");
        assertThat(first.get("classB")).isEqualTo("B");
        assertThat(first.get("positionA")).isEqualTo(1);
        assertThat(first.get("positionB")).isEqualTo(4);
        assertThat(first.get("actualCars")).isEqualTo(3);
        assertThat(first.get("requiredCars")).isEqualTo(5);
        assertThat(first.get("deficitCars")).isEqualTo(2);
        assertThat(conflicts.get(1).get("actualCars")).isEqualTo(4);
        assertThat(conflicts.get(1).get("deficitCars")).isEqualTo(1);
    }

    @Test
    void exactRequiredDistanceAndNormalCarsPass() {
        HazardRule rule = HazardRule.ordered("A", "B", 5);
        // 距离恰为 5 合法；普通车厢（null）不参与
        List<HazardValidation.Car> cars = List.of(
                car(1, "A"), car(2, null), car(3, null), car(4, null), car(6, "B"), car(7, "C"));
        assertThat(HazardValidation.findSeparationConflicts(List.of(rule), cars)).isEmpty();
    }

    @Test
    void rulePairIsOrderInsensitive() {
        HazardRule reversedRule = HazardRule.ordered("B", "A", 3);
        assertThat(reversedRule.classA()).isEqualTo("A");
        assertThat(reversedRule.classB()).isEqualTo("B");
        List<HazardValidation.Car> cars = List.of(car(1, "A"), car(2, "B"));
        assertThat(HazardValidation.findSeparationConflicts(List.of(reversedRule), cars))
                .singleElement()
                .extracting(d -> d.get("classA"), d -> d.get("classB"))
                .containsExactly("A", "B");
    }

    // ---------- 限制区段授权 ----------

    @Test
    void unauthorizedClassBlockedOnConfiguredSectionOnly() {
        List<HazardValidation.Car> cars = List.of(car(1, "A"), car(2, null), car(3, "B"));
        Map<String, List<String>> allowed = Map.of("S1", List.of("A"));

        // S1 限制只允许 A：B 被拦；S2 未配置不限制
        List<Map<String, Object>> s1 =
                HazardValidation.findUnauthorizedClasses(List.of("S1"), cars, allowed);
        assertThat(s1).hasSize(1);
        assertThat(s1.get(0).get("type")).isEqualTo("HAZARD_UNAUTHORIZED");
        assertThat(s1.get(0).get("sectionId")).isEqualTo("S1");
        assertThat(s1.get(0).get("position")).isEqualTo(3);
        assertThat(s1.get(0).get("hazardClass")).isEqualTo("B");
        assertThat(s1.get(0).get("allowedClasses")).isEqualTo(List.of("A"));

        List<Map<String, Object>> s2 =
                HazardValidation.findUnauthorizedClasses(List.of("S2"), cars, allowed);
        assertThat(s2).isEmpty();
    }

    @Test
    void emptyAllowedListBlocksEveryHazardClassButNotNormalCars() {
        List<HazardValidation.Car> cars = List.of(car(1, null), car(2, "A"));
        List<Map<String, Object>> conflicts = HazardValidation.findUnauthorizedClasses(
                List.of("S1"), cars, Map.of("S1", List.of()));
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).get("hazardClass")).isEqualTo("A");
    }

    @Test
    void outputSectionsAndPositionsAreSorted() {
        List<HazardValidation.Car> cars = List.of(car(9, "B"), car(1, "A"));
        Map<String, List<String>> allowed = Map.of("S2", List.of("X"), "S1", List.of("X"));
        List<Map<String, Object>> conflicts =
                HazardValidation.findUnauthorizedClasses(new ArrayList<>(List.of("S2", "S1")),
                        cars, allowed);
        assertThat(conflicts).hasSize(4);
        assertThat(conflicts.get(0).get("sectionId")).isEqualTo("S1");
        assertThat(conflicts.get(0).get("position")).isEqualTo(1);
        assertThat(conflicts.get(3).get("sectionId")).isEqualTo("S2");
        assertThat(conflicts.get(3).get("position")).isEqualTo(9);
    }

    // ---------- 配置自身校验 ----------

    @Test
    void normalizeRulesRejectsSameClassAndConflictingPair() {
        HazardValidation.RulesNormalization normalization = HazardValidation.normalizeRules(List.of(
                new HazardRuleRequest("A", "A", 1),
                new HazardRuleRequest("B", "A", 3),
                new HazardRuleRequest("A", "B", 5)));

        assertThat(normalization.conflicts()).hasSize(2);
        assertThat(normalization.conflicts().get(0).get("type")).isEqualTo("RULE_SAME_CLASS");
        Map<String, Object> pairConflict = normalization.conflicts().get(1);
        assertThat(pairConflict.get("type")).isEqualTo("RULE_PAIR_CONFLICT");
        assertThat(pairConflict.get("classA")).isEqualTo("A");
        assertThat(pairConflict.get("classB")).isEqualTo("B");
        // 冲突时保留首条要求值 3
        assertThat(pairConflict.get("requiredCars")).isEqualTo(3);
        assertThat(pairConflict.get("conflictingRequiredCars")).isEqualTo(5);
    }

    @Test
    void normalizeRulesDedupsIdenticalPairAndOrdersClasses() {
        HazardValidation.RulesNormalization normalization = HazardValidation.normalizeRules(List.of(
                new HazardRuleRequest("C", "A", 4),
                new HazardRuleRequest("A", "C", 4)));
        assertThat(normalization.conflicts()).isEmpty();
        assertThat(normalization.rules()).hasSize(1);
        assertThat(normalization.rules().get(0).classA()).isEqualTo("A");
        assertThat(normalization.rules().get(0).classB()).isEqualTo("C");
        assertThat(normalization.rules().get(0).minSeparationCars()).isEqualTo(4);
    }

    @Test
    void normalizeAllowedClassesReportsDuplicatesAndReturnsSortedDedup() {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        List<String> normalized =
                HazardValidation.normalizeAllowedClasses(List.of("B", "A", "B", "A"), conflicts);
        assertThat(normalized).containsExactly("A", "B");
        assertThat(conflicts).hasSize(2);
        assertThat(conflicts.get(0).get("type")).isEqualTo("SECTION_CLASS_DUPLICATE");
    }
}
