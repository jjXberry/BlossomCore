package com.blossomsmp.core.pvp;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Stops players farming rating off the same person (or an alt account).
 * No Minecraft code in here, so it can be tested on its own.
 */
public final class KillGuard {

    public enum Verdict { COUNTED, COOLDOWN, DAILY_CAP }

    /** What one player remembers about kills on one other player. */
    public static final class Record {
        public long last;   // time of the last counted kill (ms)
        public long day;    // the day "count" belongs to
        public int count;   // counted kills on that day

        public Record() {
        }

        public Record(long last, long day, int count) {
            this.last = last;
            this.day = day;
            this.count = count;
        }
    }

    private KillGuard() {
    }

    /**
     * @param killerRecent the killer's records (victim -> record)
     * @param victimRecent the victim's records (so kills in both directions share one daily cap)
     * @param cooldownMs   time before killing the same player counts again
     * @param dailyCap     counted kills allowed per day between the same two players (0 = no cap)
     */
    public static Verdict check(Map<UUID, Record> killerRecent, Map<UUID, Record> victimRecent,
                                UUID killer, UUID victim, long now, long day, long cooldownMs, int dailyCap) {
        Record mine = killerRecent.get(victim);
        if (mine != null && cooldownMs > 0 && now - mine.last < cooldownMs) {
            return Verdict.COOLDOWN;
        }
        if (dailyCap > 0) {
            int today = 0;
            if (mine != null && mine.day == day) {
                today += mine.count;
            }
            Record theirs = victimRecent.get(killer);
            if (theirs != null && theirs.day == day) {
                today += theirs.count;
            }
            if (today >= dailyCap) {
                return Verdict.DAILY_CAP;
            }
        }
        return Verdict.COUNTED;
    }

    /** Remember a counted kill. */
    public static void record(Map<UUID, Record> killerRecent, UUID victim, long now, long day) {
        Record record = killerRecent.computeIfAbsent(victim, id -> new Record());
        if (record.day != day) {
            record.day = day;
            record.count = 0;
        }
        record.count++;
        record.last = now;
    }

    /** How long until killing this player counts again (0 = it counts now). */
    public static long cooldownLeft(Map<UUID, Record> killerRecent, UUID victim, long now, long cooldownMs) {
        Record mine = killerRecent.get(victim);
        if (mine == null) {
            return 0L;
        }
        return Math.max(0L, cooldownMs - (now - mine.last));
    }

    /** Forget records that can't matter any more, so player files stay small. */
    public static void prune(Map<UUID, Record> recent, long now, long day, long cooldownMs) {
        Iterator<Record> it = recent.values().iterator();
        while (it.hasNext()) {
            Record record = it.next();
            if (record.day != day && now - record.last >= cooldownMs) {
                it.remove();
            }
        }
    }
}
