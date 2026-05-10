package com.valui.monitor.scheduler.drr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Per-user Deficit Round Robin state.
 * <p>
 * Not thread-safe — only accessed from the single dispatcher thread inside {@link DrrDispatcher}.
 * <p>
 * DRR semantics: each user accumulates {@code weight} deficit credits per round.
 * Credits are spent one-per-task; unused credits carry over, preventing starvation
 * when a user's burst is larger than its weight.
 */
public class UserSchedulingState {

    private final UUID userId;
    private final int  weight;
    private       int  deficit;
    private final Deque<UUID> pending = new ArrayDeque<>();

    public UserSchedulingState(UUID userId, int weight) {
        this.userId  = userId;
        this.weight  = weight;
        this.deficit = 0;
    }

    public UUID userId() { return userId; }

    /** Append to tail (normal enqueue from delay queue). */
    public void enqueue(UUID controllerId) {
        if (!pending.contains(controllerId)) pending.addLast(controllerId);
    }

    /** Prepend to head (put back after pool throttle — retry first next round). */
    public void requeue(UUID controllerId) {
        pending.addFirst(controllerId);
    }

    public boolean remove(UUID controllerId) { return pending.remove(controllerId); }
    public boolean isEmpty()                 { return pending.isEmpty(); }
    public int     pendingCount()            { return pending.size(); }

    /**
     * DRR serve: adds {@code weight} to deficit, then dequeues up to {@code deficit} controllers.
     * Returns the controller IDs that should be dispatched this round.
     * Unused deficit carries forward.
     */
    public List<UUID> serve() {
        deficit += weight;
        List<UUID> served = new ArrayList<>();
        while (deficit > 0 && !pending.isEmpty()) {
            served.add(pending.pollFirst());
            deficit--;
        }
        return served;
    }
}
