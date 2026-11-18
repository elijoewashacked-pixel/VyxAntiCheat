package com.vyxac;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One report / ticket. */
public final class Report {
    public static final String OPEN = "OPEN", CLAIMED = "CLAIMED", DISMISSED = "DISMISSED", ACTIONED = "ACTIONED";

    public int id;
    public UUID reporterId, targetId;
    public String reporterName = "", targetName = "", reason = "", snapshot = "";
    public String status = OPEN, claimedBy = "", closedBy = "", resolution = "";
    public long createdAt, closedAt;
    /** conversation history: "staffName|time|text" */
    public final List<String> messages = new ArrayList<>();
    /** messages waiting for the reporter (delivered when they are online) */
    public final List<String> unread = new ArrayList<>();

    public boolean isOpen() {
        return status.equals(OPEN) || status.equals(CLAIMED);
    }
}
