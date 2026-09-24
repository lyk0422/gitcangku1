package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 分组晋级名单纯逻辑：依据分组与有效选手成绩计算直接晋级与补位名单，
 * 不涉及数据库与时间。
 *
 * <p>规则：
 * <ul>
 *   <li>每组内按总耗时升序（并列按参赛号字典序）取前 Q 名直接晋级；
 *       第 Q 名存在并列时全部纳入；</li>
 *   <li>各组未直接晋级的有效选手汇入全局补位池，按同一排序取前 W 名补位；
 *       第 W 名存在并列时全部纳入；</li>
 *   <li>任一分组有效选手不足 Q 名时整体失败并返回该分组；</li>
 *   <li>名次在各自选拔池内计算：并列同名次并跳号（1、1、3）。</li>
 * </ul>
 */
public final class AdvancementCalculator {

    private AdvancementCalculator() {
    }

    /** 晋级类型。 */
    public enum AdvanceType {
        /** 组内直接晋级。 */
        DIRECT,
        /** 全局补位。 */
        WILDCARD
    }

    /** 分组输入：分组代码与组内成员参赛号。 */
    public record GroupInput(String groupCode, List<String> memberBibs) {
    }

    /** 单条晋级决定。 */
    public record AdvanceEntry(
            String bib,
            String groupCode,
            AdvanceType type,
            int rank,
            long totalTimeMs) {
    }

    /** 计算结果：要么某分组有效选手不足，要么为完整晋级方案。 */
    public sealed interface AdvancementOutcome {

        /** 分组有效选手不足 Q 名。 */
        record Insufficient(String groupCode, int required, int actual)
                implements AdvancementOutcome {
        }

        /** 晋级方案。 */
        record Plan(
                List<AdvanceEntry> entries,
                int expectedCount,
                int actualCount,
                String overflowReason) implements AdvancementOutcome {
        }
    }

    /**
     * 计算晋级名单。
     *
     * @param groups        分组及成员（成员顺序无关）
     * @param totalTimeByBib 有效选手（有完赛计时、覆盖全部检查点、未取消资格）的总耗时；
     *                       不在此映射中的成员视为无效选手，不参与晋级
     * @param quotaPerGroup 每组直接晋级名额 Q（1~8）
     * @param wildcardCount 全局补位名额 W（0~8）
     * @return 计算结果；任一分组有效选手不足 Q 名时为 Insufficient
     */
    public static AdvancementOutcome compute(
            List<GroupInput> groups,
            Map<String, Long> totalTimeByBib,
            int quotaPerGroup,
            int wildcardCount) {
        List<AdvanceEntry> entries = new ArrayList<>();
        List<Candidate> wildcardPool = new ArrayList<>();
        boolean directOverflow = false;
        for (GroupInput group : groups) {
            List<Candidate> candidates = new ArrayList<>();
            for (String bib : group.memberBibs()) {
                Long totalTimeMs = totalTimeByBib.get(bib);
                if (totalTimeMs != null) {
                    candidates.add(new Candidate(bib, group.groupCode(), totalTimeMs));
                }
            }
            if (candidates.size() < quotaPerGroup) {
                return new AdvancementOutcome.Insufficient(
                        group.groupCode(), quotaPerGroup, candidates.size());
            }
            candidates.sort(CANDIDATE_ORDER);
            int directEnd = boundaryWithTies(candidates, quotaPerGroup);
            if (directEnd > quotaPerGroup) {
                directOverflow = true;
            }
            List<Integer> groupRanks = ranksWithTies(candidates);
            for (int i = 0; i < candidates.size(); i++) {
                Candidate candidate = candidates.get(i);
                if (i < directEnd) {
                    entries.add(new AdvanceEntry(
                            candidate.bib(), candidate.groupCode(),
                            AdvanceType.DIRECT, groupRanks.get(i), candidate.totalTimeMs()));
                } else {
                    wildcardPool.add(candidate);
                }
            }
        }

        wildcardPool.sort(CANDIDATE_ORDER);
        boolean wildcardOverflow = false;
        if (wildcardCount > 0 && !wildcardPool.isEmpty()) {
            int wildcardEnd = boundaryWithTies(wildcardPool, wildcardCount);
            wildcardOverflow = wildcardEnd > wildcardCount;
            List<Integer> poolRanks = ranksWithTies(wildcardPool);
            for (int i = 0; i < wildcardEnd; i++) {
                Candidate candidate = wildcardPool.get(i);
                entries.add(new AdvanceEntry(
                        candidate.bib(), candidate.groupCode(),
                        AdvanceType.WILDCARD, poolRanks.get(i), candidate.totalTimeMs()));
            }
        }

        int expectedCount = quotaPerGroup * groups.size() + wildcardCount;
        String overflowReason = overflowReason(directOverflow, wildcardOverflow);
        return new AdvancementOutcome.Plan(
                List.copyOf(entries), expectedCount, entries.size(), overflowReason);
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingLong(Candidate::totalTimeMs)
            .thenComparing(Candidate::bib);

    /** 选拔池候选。 */
    private record Candidate(String bib, String groupCode, long totalTimeMs) {
    }

    /**
     * 含并列的截取边界：取前 limit 名后，若后续候选与第 limit 名总耗时相同则一并纳入；
     * limit 超过候选数时返回候选总数。
     */
    private static int boundaryWithTies(List<Candidate> sorted, int limit) {
        int end = Math.min(limit, sorted.size());
        while (end < sorted.size()
                && sorted.get(end).totalTimeMs() == sorted.get(end - 1).totalTimeMs()) {
            end++;
        }
        return end;
    }

    /** 池内名次：并列同名次并跳号（1、1、3）。 */
    private static List<Integer> ranksWithTies(List<Candidate> sorted) {
        List<Integer> ranks = new ArrayList<>(sorted.size());
        int index = 0;
        while (index < sorted.size()) {
            int groupEnd = index + 1;
            while (groupEnd < sorted.size()
                    && sorted.get(groupEnd).totalTimeMs() == sorted.get(index).totalTimeMs()) {
                groupEnd++;
            }
            for (int i = index; i < groupEnd; i++) {
                ranks.add(index + 1);
            }
            index = groupEnd;
        }
        return ranks;
    }

    private static String overflowReason(boolean directOverflow, boolean wildcardOverflow) {
        List<String> reasons = new ArrayList<>(2);
        if (directOverflow) {
            reasons.add("组内直接晋级边界存在并列，并列者全部纳入");
        }
        if (wildcardOverflow) {
            reasons.add("补位边界存在并列，并列者全部纳入");
        }
        return reasons.isEmpty() ? null : String.join("；", reasons);
    }
}
