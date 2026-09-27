package com.example.starter.plan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.model.HazardCar;
import com.example.starter.plan.model.SeparationRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 危险品编组校验纯单元测试（不依赖数据库）：位置重复、互斥间隔实际值/要求值/差额、
 * 规则类别对规范化（提交顺序无关）与恰好满足边界。
 */
class HazardValidatorTest {

    private final HazardValidator validator = new HazardValidator(null);

    @Test
    void duplicateCarPositionReported() {
        List<HazardCar> cars = List.of(
                car(0, 3, "EXPLOSIVE"), car(1, 3, "FLAMMABLE"), car(2, 3, "TOXIC"));
        List<Map<String, Object>> conflicts =
                validator.validateFormation("SCH-1", cars, List.of());
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).get("type")).isEqualTo("DUPLICATE_CAR_POSITION");
        assertThat(conflicts.get(0).get("occurrences")).isEqualTo(3);
    }

    @Test
    void separationViolationReportsActualRequiredAndDeficit() {
        List<SeparationRule> rules =
                List.of(new SeparationRule("EXPLOSIVE", "FLAMMABLE", 4));
        List<HazardCar> cars = List.of(car(0, 2, "FLAMMABLE"), car(1, 5, "EXPLOSIVE"));
        List<Map<String, Object>> conflicts =
                validator.validateFormation("SCH-1", cars, rules);
        assertThat(conflicts).hasSize(1);
        Map<String, Object> detail = conflicts.get(0);
        assertThat(detail.get("type")).isEqualTo("SEPARATION_VIOLATION");
        assertThat(detail.get("classA")).isEqualTo("EXPLOSIVE");
        assertThat(detail.get("classB")).isEqualTo("FLAMMABLE");
        assertThat(detail.get("actualSeparation")).isEqualTo(3);
        assertThat(detail.get("requiredSeparation")).isEqualTo(4);
        assertThat(detail.get("deficit")).isEqualTo(1);
    }

    @Test
    void exactSeparationIsValidAndRuleOrderNormalized() {
        // 规则按字典序规范化存储；间隔恰好等于要求值合法
        List<SeparationRule> rules =
                List.of(new SeparationRule("FLAMMABLE", "EXPLOSIVE", 3));
        List<HazardCar> cars = List.of(car(0, 1, "EXPLOSIVE"), car(1, 4, "FLAMMABLE"));
        assertThat(validator.validateFormation("SCH-1", cars, rules)).isEmpty();
    }

    private static HazardCar car(long id, int position, String hazardClass) {
        return new HazardCar(id, 1L, (int) id, position, hazardClass);
    }
}
