package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中立赛段纯逻辑测试：区间定义校验、端点覆盖判断、扣时与净值计算。
 */
class NeutralLegsTest {

    @Test
    void 区间数量与检查点前置校验() {
        assertThat(NeutralLegs.validateLegs(List.of(new NeutralLegSpec(0, 1)), 0))
                .contains("必须先配置检查点");
        assertThat(NeutralLegs.validateLegs(List.of(), 3)).contains("1~5");
        List<NeutralLegSpec> six = List.of(
                new NeutralLegSpec(0, 1), new NeutralLegSpec(1, 2), new NeutralLegSpec(2, 3),
                new NeutralLegSpec(3, 4), new NeutralLegSpec(4, 5), new NeutralLegSpec(5, 6));
        assertThat(NeutralLegs.validateLegs(six, 10)).contains("1~5");
    }

    @Test
    void 区间端点校验() {
        // 终点必须严格大于起点
        assertThat(NeutralLegs.validateLegs(List.of(new NeutralLegSpec(2, 2)), 3))
                .contains("严格大于起点");
        assertThat(NeutralLegs.validateLegs(List.of(new NeutralLegSpec(2, 1)), 3))
                .contains("严格大于起点");
        // 终点不能超过检查点总数
        assertThat(NeutralLegs.validateLegs(List.of(new NeutralLegSpec(0, 4)), 3))
                .contains("必经检查点");
        // 起点不能超过最后一个检查点
        assertThat(NeutralLegs.validateLegs(List.of(new NeutralLegSpec(3, 4)), 3))
                .contains("发枪零点或已配置检查点");
        // 起点不能为负
        assertThat(NeutralLegs.validateLegs(List.of(new NeutralLegSpec(-1, 2)), 3))
                .contains("不能为负");
    }

    @Test
    void 区间不得交叠但相接合法() {
        // 交叠：[0,2] 与 [1,3]
        assertThat(NeutralLegs.validateLegs(List.of(
                new NeutralLegSpec(0, 2), new NeutralLegSpec(1, 3)), 3))
                .contains("不得交叠");
        // 乱序提交同样检出交叠
        assertThat(NeutralLegs.validateLegs(List.of(
                new NeutralLegSpec(1, 3), new NeutralLegSpec(0, 2)), 3))
                .contains("不得交叠");
        // 相接合法：[0,1] 与 [1,3]
        assertThat(NeutralLegs.validateLegs(List.of(
                new NeutralLegSpec(0, 1), new NeutralLegSpec(1, 3)), 3))
                .isNull();
        // 发枪零点起点合法
        assertThat(NeutralLegs.validateLegs(List.of(new NeutralLegSpec(0, 2)), 3))
                .isNull();
    }

    @Test
    void 端点覆盖判断() {
        NeutralLegSpec leg = new NeutralLegSpec(1, 2);
        assertThat(NeutralLegs.endpointsCovered(leg, Map.of(1, 20L, 2, 70L))).isTrue();
        assertThat(NeutralLegs.endpointsCovered(leg, Map.of(2, 70L))).isFalse();
        assertThat(NeutralLegs.endpointsCovered(leg, Map.of(1, 20L))).isFalse();
        // 起点为发枪零点时只需终点
        NeutralLegSpec gunStart = new NeutralLegSpec(0, 2);
        assertThat(NeutralLegs.endpointsCovered(gunStart, Map.of(2, 70L))).isTrue();
        assertThat(NeutralLegs.endpointsCovered(gunStart, Map.of())).isFalse();
    }

    @Test
    void 扣时合计与漏点不估计() {
        List<NeutralLegSpec> legs = List.of(
                new NeutralLegSpec(1, 2), new NeutralLegSpec(0, 1));
        Map<Integer, Long> elapsed = Map.of(1, 20L, 2, 70L);
        // [1,2]=50，[0,1]=20，合计70（顺序无关）
        assertThat(NeutralLegs.totalDeduction(legs, elapsed)).isEqualTo(70L);
        // 端点缺失 -> null，不做估计扣除
        assertThat(NeutralLegs.totalDeduction(legs, Map.of(1, 20L))).isNull();
        assertThat(NeutralLegs.legDeduction(new NeutralLegSpec(0, 2), Map.of(2, 70L)))
                .isEqualTo(70L);
    }

    @Test
    void 净竞技用时必须正值() {
        assertThat(NeutralLegs.netTimeViolation(1L)).isNull();
        assertThat(NeutralLegs.netTimeViolation(0L)).contains("正值");
        assertThat(NeutralLegs.netTimeViolation(-5L)).contains("正值");
    }
}
