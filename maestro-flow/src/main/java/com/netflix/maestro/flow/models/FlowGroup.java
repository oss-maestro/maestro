package com.netflix.maestro.flow.models;

/**
 * Flow group data model.
 *
 * @param groupId immutable data to persist
 * @param generation will increase it by 1 if a new node claims its ownership
 * @param address reachable owner address
 * @param heartbeatTs the heartbeat timestamp when the flow group originally owned
 * @author jun-he
 */
public record FlowGroup(long groupId, long generation, String address, long heartbeatTs) {}
