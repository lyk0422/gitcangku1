package com.example.starter.race.service;

import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.AdvancementEntryResponse;
import com.example.starter.race.api.AdvancementResponse;
import com.example.starter.race.api.GroupsResponse;
import com.example.starter.race.api.NonAdvancedResponse;
import com.example.starter.race.api.PenaltyResponse;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RunnerResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.AdvancementEntryType;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.persistence.AdvancementEntryRow;
import com.example.starter.race.persistence.AdvancementGroupMemberRow;
import com.example.starter.race.persistence.AdvancementGroupRow;
import com.example.starter.race.persistence.AdvancementListRow;
import com.example.starter.race.persistence.AdvancementNonAdvancedRow;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotCheckpointRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 持久化行记录与 API 响应之间的转换。
 */
final class ResponseMapper {

    private ResponseMapper() {
    }

    static RunnerResponse toRunnerResponse(RunnerRow row) {
        return new RunnerResponse(row.bib(), row.finishTimeMs(), row.createdAt(), row.updatedAt());
    }

    static PenaltyResponse toPenaltyResponse(PenaltyRow row) {
        return new PenaltyResponse(row.penaltyId(), row.bib(), row.type(), row.amountMs(),
                row.revoked(), row.createdAt(), row.revokedAt());
    }

    static CheckpointTimingResponse toTimingResponse(CheckpointTimingRow row) {
        return new CheckpointTimingResponse(
                row.timingId(), row.bib(), row.checkpointCode(), row.position(),
                row.elapsedMillis(), row.createdAt());
    }

    static StandingResponse liveStanding(
            RaceRow race,
            List<RunnerRow> runners,
            List<PenaltyRow> penalties,
            List<CheckpointRow> checkpoints,
            List<CheckpointTimingRow> timings) {
        List<ResultEntry> entries = ResultCalculator.compute(
                runners, penalties, checkpoints, timings);
        return new StandingResponse(
                race.raceId(),
                race.version(),
                race.status(),
                null,
                entries.stream().map(ResponseMapper::toEntryResponse).toList());
    }

    static StandingResponse snapshotStanding(SnapshotRow snapshot) {
        return new StandingResponse(
                snapshot.raceId(),
                snapshot.version(),
                RaceStatus.SEALED,
                snapshot.sealedAt(),
                snapshot.entries().stream().map(ResponseMapper::toEntryResponse).toList());
    }

    static ResultEntryResponse toEntryResponse(ResultEntry entry) {
        return new ResultEntryResponse(
                entry.bib(),
                entry.rank(),
                entry.status(),
                entry.finishTimeMs(),
                entry.penaltyMs(),
                entry.totalTimeMs(),
                entry.checkpointCount(),
                entry.coveredCheckpointCount(),
                entry.missingCheckpoints());
    }

    static ResultEntryResponse toEntryResponse(SnapshotEntryRow entry) {
        return new ResultEntryResponse(
                entry.bib(),
                entry.rank(),
                entry.status(),
                entry.finishTimeMs(),
                entry.penaltyMs(),
                entry.totalTimeMs(),
                entry.checkpointCount(),
                entry.coveredCheckpointCount(),
                entry.missingCheckpoints());
    }

    /**
     * 组装单个选手的分段明细：按检查点顺序，把已有通过记录映射到对应检查点，
     * 未通过的检查点 elapsedMillis/timingId 为 null。
     */
    static RunnerTimingResponse runnerTiming(
            RaceRow race,
            RunnerRow runner,
            List<CheckpointRow> checkpoints,
            List<CheckpointTimingRow> timings) {
        Map<String, CheckpointTimingRow> byCode = new LinkedHashMap<>();
        for (CheckpointTimingRow timing : timings) {
            byCode.put(timing.checkpointCode(), timing);
        }
        List<CheckpointPassResponse> passes = checkpoints.stream()
                .map(checkpoint -> {
                    CheckpointTimingRow timing = byCode.get(checkpoint.checkpointCode());
                    return new CheckpointPassResponse(
                            checkpoint.checkpointCode(),
                            checkpoint.position(),
                            timing == null ? null : timing.elapsedMillis(),
                            timing == null ? null : timing.timingId());
                })
                .toList();
        return new RunnerTimingResponse(
                race.raceId(), runner.bib(), race.version(), runner.finishTimeMs(), passes);
    }

    /**
     * 由封榜快照中的分段明细组装单选手分段视图（缺失检查点 elapsedMillis/timingId 为 null）。
     */
    static RunnerTimingResponse snapshotRunnerTiming(
            SnapshotRow snapshot,
            String bib,
            Long finishTimeMs,
            List<CheckpointRow> checkpoints) {
        Map<String, SnapshotCheckpointRow> byCode = new LinkedHashMap<>();
        for (SnapshotCheckpointRow detail : snapshot.checkpoints()) {
            if (detail.bib().equals(bib)) {
                byCode.put(detail.checkpointCode(), detail);
            }
        }
        List<CheckpointPassResponse> passes = checkpoints.stream()
                .map(checkpoint -> {
                    SnapshotCheckpointRow detail = byCode.get(checkpoint.checkpointCode());
                    if (detail == null) {
                        return new CheckpointPassResponse(
                                checkpoint.checkpointCode(), checkpoint.position(), null, null);
                    }
                    return new CheckpointPassResponse(
                            detail.checkpointCode(), detail.position(),
                            detail.elapsedMillis(), detail.timingId());
                })
                .toList();
        return new RunnerTimingResponse(
                snapshot.raceId(), bib, snapshot.version(), finishTimeMs, passes);
    }

    /** 组装分组查询响应：按分组顺序，成员按参赛号字典序。 */
    static GroupsResponse groupsResponse(
            String raceId,
            int version,
            List<AdvancementGroupRow> groups,
            List<AdvancementGroupMemberRow> members) {
        Map<String, List<String>> membersByGroup = new LinkedHashMap<>();
        for (AdvancementGroupRow group : groups) {
            membersByGroup.put(group.groupCode(), new ArrayList<>());
        }
        for (AdvancementGroupMemberRow member : members) {
            List<String> bibs = membersByGroup.get(member.groupCode());
            if (bibs != null) {
                bibs.add(member.bib());
            }
        }
        List<GroupsResponse.GroupResponse> groupResponses = groups.stream()
                .map(group -> new GroupsResponse.GroupResponse(
                        group.groupCode(), group.position(),
                        List.copyOf(membersByGroup.get(group.groupCode()))))
                .toList();
        return new GroupsResponse(raceId, version, groupResponses);
    }

    /** 由持久化名单快照组装晋级名单响应，超额原因按固化的展示顺序重算。 */
    static AdvancementResponse advancementResponse(AdvancementListRow list) {
        List<AdvancementEntryRow> entries = list.entries();
        List<AdvancementEntryResponse> entryResponses = entries.stream()
                .map(ResponseMapper::toAdvancementEntryResponse)
                .toList();
        int directCount = 0;
        int wildcardCount = 0;
        for (AdvancementEntryRow entry : entries) {
            if (entry.type() == AdvancementEntryType.DIRECT) {
                directCount++;
            } else if (entry.type() == AdvancementEntryType.WILDCARD) {
                wildcardCount++;
            }
        }
        List<AdvancementResponse.OverQuotaReason> reasons =
                overQuotaReasons(list, entries);
        return new AdvancementResponse(
                list.advancementKey(),
                list.raceId(),
                list.version(),
                list.status(),
                list.directQuota(),
                list.wildcardQuota(),
                list.generatedAt(),
                list.revokedAt(),
                directCount,
                wildcardCount,
                entries.size(),
                list.directQuota() * groupCount(entries) + list.wildcardQuota(),
                reasons,
                entryResponses);
    }

    /** 由生效名单固化数据组装未晋级清单：按分组顺序、组内成绩（展示顺序）排列。 */
    static NonAdvancedResponse nonAdvancedResponse(AdvancementListRow list) {
        Map<String, List<String>> membersByGroup = new LinkedHashMap<>();
        Map<String, Integer> positionByGroup = new LinkedHashMap<>();
        // DIRECT 条目按分组顺序出现，借此固化分组顺序；未晋级行自身携带分组。
        for (AdvancementEntryRow entry : list.entries()) {
            if (entry.type() == AdvancementEntryType.DIRECT) {
                positionByGroup.putIfAbsent(entry.groupCode(),
                        positionByGroup.size() + 1);
            }
        }
        for (AdvancementNonAdvancedRow row : list.nonAdvanced()) {
            positionByGroup.putIfAbsent(row.groupCode(), positionByGroup.size() + 1);
        }
        for (String groupCode : positionByGroup.keySet()) {
            membersByGroup.put(groupCode, new ArrayList<>());
        }
        for (AdvancementNonAdvancedRow row : list.nonAdvanced()) {
            membersByGroup.get(row.groupCode()).add(row.bib());
        }
        List<NonAdvancedResponse.GroupNonAdvanced> groups = positionByGroup.keySet().stream()
                .map(groupCode -> new NonAdvancedResponse.GroupNonAdvanced(
                        groupCode,
                        positionByGroup.get(groupCode),
                        List.copyOf(membersByGroup.get(groupCode))))
                .toList();
        return new NonAdvancedResponse(
                list.raceId(), list.advancementKey(), list.version(), groups);
    }

    private static AdvancementEntryResponse toAdvancementEntryResponse(
            AdvancementEntryRow entry) {
        return new AdvancementEntryResponse(
                entry.bib(),
                entry.groupCode(),
                entry.rank(),
                entry.type().name(),
                entry.finishTimeMs(),
                entry.penaltyMs(),
                entry.totalTimeMs());
    }

    /** 统计名单中出现的分组数量（DIRECT 条目覆盖全部分组）。 */
    private static int groupCount(List<AdvancementEntryRow> entries) {
        java.util.Set<String> groupCodes = new java.util.HashSet<>();
        for (AdvancementEntryRow entry : entries) {
            groupCodes.add(entry.groupCode());
        }
        return groupCodes.size();
    }

    /**
     * 依据固化条目重算并列超额原因：每组 DIRECT 末名所在名次若跨过 Q 边界，
     * 以及 WILDCARD 末名所在全局名次若跨过 W 边界，各给出同名次全部参赛号。
     */
    private static List<AdvancementResponse.OverQuotaReason> overQuotaReasons(
            AdvancementListRow list, List<AdvancementEntryRow> entries) {
        List<AdvancementResponse.OverQuotaReason> reasons = new ArrayList<>();
        Map<String, List<AdvancementEntryRow>> directByGroup = new LinkedHashMap<>();
        List<AdvancementEntryRow> wildcard = new ArrayList<>();
        for (AdvancementEntryRow entry : entries) {
            if (entry.type() == AdvancementEntryType.DIRECT) {
                directByGroup.computeIfAbsent(entry.groupCode(), key -> new ArrayList<>())
                        .add(entry);
            } else {
                wildcard.add(entry);
            }
        }
        for (Map.Entry<String, List<AdvancementEntryRow>> groupEntry : directByGroup.entrySet()) {
            List<AdvancementEntryRow> groupEntries = groupEntry.getValue();
            // 并列同名次组跨过 Q 边界时，组内 DIRECT 条目数会大于 Q，
            // 被越界纳入者与边界名次同名次（总耗时相同）。
            if (groupEntries.size() > list.directQuota()) {
                AdvancementEntryRow last = groupEntries.getLast();
                long tiedTimeMs = last.totalTimeMs();
                reasons.add(new AdvancementResponse.OverQuotaReason(
                        AdvancementEntryType.DIRECT.name(),
                        groupEntry.getKey(),
                        list.directQuota(),
                        tiedTimeMs,
                        groupEntries.stream()
                                .filter(entry -> entry.rank() == last.rank())
                                .map(AdvancementEntryRow::bib)
                                .sorted()
                                .toList()));
            }
        }
        if (wildcard.size() > list.wildcardQuota()) {
            AdvancementEntryRow last = wildcard.getLast();
            long tiedTimeMs = last.totalTimeMs();
            reasons.add(new AdvancementResponse.OverQuotaReason(
                    AdvancementEntryType.WILDCARD.name(),
                    null,
                    list.wildcardQuota(),
                    tiedTimeMs,
                    wildcard.stream()
                            .filter(entry -> entry.rank() == last.rank())
                            .map(AdvancementEntryRow::bib)
                            .sorted()
                            .toList()));
        }
        return reasons;
    }
}
