package eu.berdosi.app.heartbeat;

/**
 * What the user was doing during a reading. Stored by {@link #name()}, so entries can be added or
 * reordered without invalidating the log. The UI shows them in declaration order.
 */
enum ActivityLevel {
    LYING,
    SITTING,
    STANDING,
    WALKING,
    AEROBIC,
    TESTING;

    /** Null (no activity recorded) and names this version doesn't know both map to null. */
    static ActivityLevel fromName(String name) {
        if (name == null) return null;
        for (ActivityLevel level : values()) {
            if (level.name().equals(name)) return level;
        }
        return null;
    }
}
