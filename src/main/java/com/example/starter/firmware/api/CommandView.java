package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.AssignmentCommand;

/**
 * 代次指令视图。
 */
public record CommandView(long commandId, long releaseId, String deviceId, long cohortId, int generation,
                          String status, String firstResult, Long migrationId) {

    public static CommandView of(AssignmentCommand command) {
        return new CommandView(command.id(), command.releaseId(), command.deviceId(), command.cohortId(),
                command.generation(), command.status().name(),
                command.firstResult() == null ? null : command.firstResult().name(), command.migrationId());
    }
}
