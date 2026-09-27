package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 退役后态规划器（纯函数，无 IO）：基于提交时刻读取的区组/席位现状，
 * 先对整批请求代码校验完整后态，产出封存序号、配额重算与差额明细。
 * 任一代码不满足条件即抛出带原因码的异常，调用方据此整批回滚。
 *
 * <p>背景：每区组固定 4 席，按席位顺序两个 A、两个 B；已分配含退组均占席，
 * 此前退役封存的序号既不可分配也不计入可分配名额。</p>
 */
public final class RetirementPlanner {

    /** 实验退役后必须保留的可分配名额下限（实际值不得低于该要求值），实验已满员时豁免。 */
    public static final int MIN_AVAILABLE_EXPERIMENT = 1;

    private RetirementPlanner() {
    }

    /** 单个席位序号现状；occupied=已分配（含退组）占席；sealed=此前退役已封存。 */
    public record SeatState(int blockNo, int seatNo, String treatment, boolean occupied, boolean sealed) {
    }

    /** 一个待退役代码的规划结果。 */
    public record CodePlan(
            String treatment,
            int quotaBefore,
            int assignedCount,
            int sealedBefore,
            int sealedNow,
            int quotaAfter) {
    }

    /** 一个区组的配额重算结果。 */
    public record BlockPlan(
            int blockNo,
            int occupiedCount,
            int availableBefore,
            int sealedInBlock,
            int availableAfter,
            int quotaAAfter,
            int quotaBAfter) {
    }

    /** 完整退役规划（后态）。 */
    public record RetirementPlan(List<CodePlan> codes, List<BlockPlan> blocks, int sealedTotal) {
    }

    /**
     * 计算整批退役后态。
     *
     * @param requestedCodes 规范化（去重、升序）后的待退役代码集合
     * @param seats          实验全部席位现状（含占用与封存标记）
     * @param blockCount     区组数量
     */
    public static RetirementPlan plan(List<String> requestedCodes, List<SeatState> seats, int blockCount) {
        Map<String, List<SeatState>> byTreatment = seats.stream()
                .collect(Collectors.groupingBy(SeatState::treatment));

        List<CodePlan> codePlans = new ArrayList<>();
        // 被本批退役的代码集合，用于区组后态判定
        Set<String> retiring = new TreeSet<>(requestedCodes);
        for (String treatment : requestedCodes) {
            List<SeatState> codeSeats = byTreatment.getOrDefault(treatment, List.of());
            int quotaBefore = codeSeats.size();
            int assigned = (int) codeSeats.stream().filter(SeatState::occupied).count();
            int sealedBefore = (int) codeSeats.stream().filter(SeatState::sealed).count();
            int sealNow = (int) codeSeats.stream().filter(s -> !s.occupied() && !s.sealed()).count();
            if (sealNow == 0) {
                // 无未分配序号可封存：区分“此前已退役封存完”与“全部已分配”
                if (sealedBefore > 0) {
                    throw ApiException.conflict("CODE_ALREADY_RETIRED",
                            "处理代码 " + treatment + " 已退役，未分配序号已封存，不可重复退役");
                }
                throw ApiException.unprocessable("CODE_FULLY_ALLOCATED",
                        "处理代码 " + treatment + " 已全部分配，没有可封存的未分配序号；"
                                + "实际剩余 0 个，要求至少 1 个，差额 1 个");
            }
            int quotaAfter = quotaBefore - assigned - sealedBefore - sealNow;
            codePlans.add(new CodePlan(treatment, quotaBefore, assigned, sealedBefore, sealNow, quotaAfter));
        }

        List<BlockPlan> blockPlans = new ArrayList<>();
        int totalCapacity = seats.size();
        int totalOccupied = 0;
        int totalAvailableAfter = 0;
        for (int blockNo = 1; blockNo <= blockCount; blockNo++) {
            final int b = blockNo;
            List<SeatState> blockSeats = seats.stream().filter(s -> s.blockNo() == b).toList();
            int occupied = (int) blockSeats.stream().filter(SeatState::occupied).count();
            int availableBefore = (int) blockSeats.stream()
                    .filter(s -> !s.occupied() && !s.sealed()).count();
            int sealedInBlock = (int) blockSeats.stream()
                    .filter(s -> !s.occupied() && !s.sealed() && retiring.contains(s.treatment()))
                    .count();
            int availableAfter = availableBefore - sealedInBlock;
            int quotaAAfter = countAvailable(blockSeats, "A", retiring);
            int quotaBAfter = countAvailable(blockSeats, "B", retiring);
            totalOccupied += occupied;
            totalAvailableAfter += availableAfter;
            blockPlans.add(new BlockPlan(b, occupied, availableBefore, sealedInBlock, availableAfter,
                    quotaAAfter, quotaBAfter));
        }

        // 后态不变量：退役不得在实验仍有登记容量时把全部剩余有效代码名额清零。
        // 即：实验未满员（占用数 < 总席位）时，退役后至少保留 1 个可分配名额；
        // 已满员（全部席位占用）豁免。一次退役 A+B 或在唯一有效代码已退役后退役另一代码会触发。
        if (totalOccupied < totalCapacity && totalAvailableAfter < MIN_AVAILABLE_EXPERIMENT) {
            int deficit = MIN_AVAILABLE_EXPERIMENT - totalAvailableAfter;
            throw ApiException.unprocessable("INSUFFICIENT_REMAINING_CODE",
                    "退役后剩余有效代码名额不足以满足后续区组分配：实际 " + totalAvailableAfter
                            + " 个，要求至少 " + MIN_AVAILABLE_EXPERIMENT + " 个，差额 " + deficit
                            + " 个；整批回滚，未封存任何序号");
        }

        int sealedTotal = codePlans.stream().mapToInt(CodePlan::sealedNow).sum();
        return new RetirementPlan(List.copyOf(codePlans), List.copyOf(blockPlans), sealedTotal);
    }

    private static int countAvailable(List<SeatState> blockSeats, String treatment,
                                      Set<String> retiring) {
        return (int) blockSeats.stream()
                .filter(s -> s.treatment().equals(treatment))
                .filter(s -> !s.occupied() && !s.sealed() && !retiring.contains(treatment))
                .count();
    }
}
