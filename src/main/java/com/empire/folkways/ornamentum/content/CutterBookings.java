package com.empire.folkways.ornamentum.content;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;

/** 每台切割机的占位队列；同一时刻只允许一个居民工作。 */
public final class CutterBookings {
    private final Map<WorldPos, LinkedHashMap<UUID, Long>> turns = new LinkedHashMap<>();
    private final Map<WorldPos, Station> stations = new LinkedHashMap<>();
    private long epoch;
    private long revision;

    public synchronized void admit(WorldPos at) {
        Station before = this.stations.get(at);
        if (before == null) {
            this.stations.put(at, new Station(this.epoch, true));
        } else if (!before.allowed()) {
            this.stations.put(at, new Station(++this.revision, true));
        }
    }

    public synchronized void withdraw(WorldPos at) {
        this.stations.put(at, new Station(++this.revision, false));
        this.turns.remove(at);
    }

    public synchronized boolean free(WorldPos at) {
        return this.station(at).allowed() && !this.turns.containsKey(at);
    }

    public synchronized boolean valid(WorldPos at, UUID turn) {
        Map<UUID, Long> queue = this.turns.get(at);
        return this.station(at).allowed() && queue != null && queue.containsKey(turn);
    }

    public synchronized boolean cancelled(WorldPos at, UUID turn) {
        return !this.valid(at, turn);
    }

    public synchronized long generation(WorldPos at) {
        return this.station(at).generation();
    }

    public synchronized boolean valid(WorldPos at, UUID turn, long generation) {
        return generation == this.station(at).generation() && this.valid(at, turn);
    }

    public synchronized boolean cancelled(WorldPos at, UUID turn, long generation) {
        return !this.valid(at, turn, generation);
    }

    public synchronized boolean first(WorldPos at, UUID lastNode) {
        Map<UUID, Long> queue = this.turns.get(at);
        return this.station(at).allowed() && queue != null && !queue.isEmpty()
                && queue.keySet().iterator().next().equals(lastNode);
    }

    public synchronized Hold hold(WorldPos at, UUID lastNode, long estimatedTicks) {
        final long duration = Math.max(0L, estimatedTicks);
        final long generation = this.station(at).generation();
        final long created = this.epoch;
        return new Hold() {
            private boolean held;
            private Set<UUID> after = Set.of();

            private boolean current() {
                Station now = CutterBookings.this.station(at);
                return created == CutterBookings.this.epoch
                        && generation == now.generation()
                        && now.allowed();
            }

            @Override
            public Optional<RefusalKind> take() {
                synchronized (CutterBookings.this) {
                    if (!this.current()) {
                        return Optional.of(OrnamentumRefusal.STATION_CHANGED);
                    }
                    if (this.held) {
                        return Optional.empty();
                    }
                    LinkedHashMap<UUID, Long> queue = CutterBookings.this.turns
                            .computeIfAbsent(at, ignored -> new LinkedHashMap<>());
                    if (queue.containsKey(lastNode)) {
                        return Optional.of(OrnamentumRefusal.STATION_BUSY);
                    }
                    this.after = Set.copyOf(queue.keySet());
                    queue.put(lastNode, duration);
                    this.held = true;
                    return Optional.empty();
                }
            }

            @Override
            public long estimatedWaitTicks() {
                synchronized (CutterBookings.this) {
                    if (!this.current()) {
                        return 0L;
                    }
                    LinkedHashMap<UUID, Long> queue = CutterBookings.this.turns.get(at);
                    if (queue == null) {
                        return 0L;
                    }
                    long ticks = 0L;
                    for (Entry<UUID, Long> entry : queue.entrySet()) {
                        if (entry.getKey().equals(lastNode)) {
                            break;
                        }
                        ticks = entry.getValue() > Long.MAX_VALUE - ticks
                                ? Long.MAX_VALUE
                                : ticks + entry.getValue();
                    }
                    return ticks;
                }
            }

            @Override
            public Set<UUID> after() {
                return this.after;
            }

            @Override
            public void release(Ending how) {
                synchronized (CutterBookings.this) {
                    if (!this.held) {
                        return;
                    }
                    this.held = false;
                    this.after = Set.of();
                    if (this.current()) {
                        LinkedHashMap<UUID, Long> queue = CutterBookings.this.turns.get(at);
                        if (queue != null) {
                            queue.remove(lastNode);
                            if (queue.isEmpty()) {
                                CutterBookings.this.turns.remove(at);
                            }
                        }
                    }
                }
            }
        };
    }

    private Station station(WorldPos at) {
        return this.stations.getOrDefault(at, new Station(this.epoch, true));
    }

    public synchronized void clear() {
        this.epoch = ++this.revision;
        this.turns.clear();
        this.stations.clear();
    }

    private record Station(long generation, boolean allowed) {
    }
}
