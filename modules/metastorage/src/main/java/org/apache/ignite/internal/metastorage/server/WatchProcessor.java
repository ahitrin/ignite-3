/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.metastorage.server;

import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static java.util.concurrent.CompletableFuture.supplyAsync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ignite.internal.close.ManuallyCloseable;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.metastorage.Entry;
import org.apache.ignite.internal.metastorage.EntryEvent;
import org.apache.ignite.internal.metastorage.RevisionUpdateListener;
import org.apache.ignite.internal.metastorage.WatchEvent;
import org.apache.ignite.internal.metastorage.WatchListener;
import org.apache.ignite.internal.thread.NamedThreadFactory;
import org.apache.ignite.internal.util.IgniteUtils;

/**
 * Class for storing and notifying Meta Storage Watches.
 *
 * <p>Every Meta Storage update is processed by each registered Watch in parallel, however notifications for a single Watch are
 * linearised (Watches are always notified of one event at a time and in increasing order of revisions). It is also guaranteed that
 * Watches will not get notified of a new revision until all Watches have finished processing a previous revision.
 */
public class WatchProcessor implements ManuallyCloseable {
    /** Reads an entry from the storage using a given key and revision. */
    @FunctionalInterface
    public interface EntryReader {
        Entry get(byte[] key, long revision);
    }

    private static final IgniteLogger LOG = Loggers.forClass(WatchProcessor.class);

    /** Map that contains Watches and corresponding Watch notification process (represented as a CompletableFuture). */
    private final List<Watch> watches = new CopyOnWriteArrayList<>();

    /**
     * Future that represents the process of notifying registered Watches about a Meta Storage revision.
     *
     * <p>Since Watches are notified concurrently, this future is used to guarantee that no Watches get notified of a new revision,
     * until all Watches have finished processing the previous revision.
     */
    private volatile CompletableFuture<Void> notificationFuture = completedFuture(null);

    private final EntryReader entryReader;

    /** Callback that gets notified after a {@link WatchEvent} has been processed by a registered watch. */
    private volatile OnRevisionAppliedCallback revisionCallback;

    /** Executor for processing watch events. */
    private final ExecutorService watchExecutor;

    /** Meta Storage revision update listeners. */
    private final List<RevisionUpdateListener> revisionUpdateListeners = new CopyOnWriteArrayList<>();

    /**
     * Creates a new instance.
     *
     * @param entryReader Function for reading an entry from the storage using a given key and revision.
     */
    public WatchProcessor(String nodeName, EntryReader entryReader) {
        this.entryReader = entryReader;

        this.watchExecutor = Executors.newFixedThreadPool(4, NamedThreadFactory.create(nodeName, "metastorage-watch-executor", LOG));
    }

    /** Adds a watch. */
    public void addWatch(Watch watch) {
        watches.add(watch);
    }

    /** Removes a watch (identified by its listener). */
    public void removeWatch(WatchListener listener) {
        watches.removeIf(watch -> watch.listener() == listener);
    }

    /**
     * Returns the minimal target revision of all registered watches.
     */
    public OptionalLong minWatchRevision() {
        return watches.stream()
                .mapToLong(Watch::startRevision)
                .min();
    }

    /**
     * Sets the callback that will be executed every time after watches have been notified of a particular revision.
     */
    public void setRevisionCallback(OnRevisionAppliedCallback revisionCallback) {
        assert this.revisionCallback == null;

        this.revisionCallback = revisionCallback;
    }

    /**
     * Notifies registered watches about an update event.
     */
    public void notifyWatches(List<Entry> updatedEntries, HybridTimestamp time) {
        assert time != null;

        notificationFuture = notificationFuture
                .thenComposeAsync(v -> {
                    // Revision must be the same for all entries.
                    long newRevision = updatedEntries.get(0).revision();

                    // Collect all the events for each watch.
                    CompletableFuture<List<WatchAndEvents>> watchesAndEventsFuture = collectWatchesAndEvents(updatedEntries, newRevision);

                    return watchesAndEventsFuture
                            .thenComposeAsync(watchAndEvents -> {
                                CompletableFuture<Void> notifyWatchesFuture = notifyWatches(watchAndEvents, newRevision, time);

                                // Revision update is triggered strictly after all watch listeners have been notified.
                                CompletableFuture<Void> notifyUpdateRevisionFuture = notifyUpdateRevisionListeners(newRevision);

                                return allOf(notifyWatchesFuture, notifyUpdateRevisionFuture);
                            }, watchExecutor)
                            .thenComposeAsync(ignored ->
                                    invokeOnRevisionCallback(watchesAndEventsFuture, newRevision, time), watchExecutor
                            );
                }, watchExecutor);
    }


    private static CompletableFuture<Void> notifyWatches(List<WatchAndEvents> watchAndEventsList, long revision, HybridTimestamp time) {
        if (watchAndEventsList.isEmpty()) {
            return completedFuture(null);
        }

        CompletableFuture<?>[] notifyWatchFutures = new CompletableFuture[watchAndEventsList.size()];

        int i = 0;

        for (WatchAndEvents watchAndEvents : watchAndEventsList) {
            CompletableFuture<Void> notifyWatchFuture;

            try {
                if (watchAndEvents.events.isEmpty()) {
                    notifyWatchFuture = completedFuture(null);
                } else {
                    notifyWatchFuture = watchAndEvents.watch.onUpdate(new WatchEvent(watchAndEvents.events, revision, time))
                            .whenComplete((v, e) -> {
                                if (e != null) {
                                    if (e instanceof CompletionException) {
                                        e = e.getCause();
                                    }

                                    // TODO: IGNITE-14693 Implement Meta storage exception handling
                                    LOG.error("Error occurred when processing a watch event", e);

                                    watchAndEvents.watch.onError(e);
                                }
                            });
                }
            } catch (Throwable throwable) {
                watchAndEvents.watch.onError(throwable);

                notifyWatchFuture = failedFuture(throwable);
            }

            notifyWatchFutures[i++] = notifyWatchFuture;
        }

        return allOf(notifyWatchFutures);
    }

    private CompletableFuture<List<WatchAndEvents>> collectWatchesAndEvents(List<Entry> updatedEntries, long revision) {
        return supplyAsync(() -> {
            List<WatchAndEvents> watchAndEvents = List.of();

            for (Watch watch : watches) {
                List<EntryEvent> events = List.of();

                for (Entry newEntry : updatedEntries) {
                    byte[] newKey = newEntry.key();

                    assert newEntry.revision() == revision;

                    if (watch.matches(newKey, revision)) {
                        Entry oldEntry = entryReader.get(newKey, revision - 1);

                        if (events.isEmpty()) {
                            events = new ArrayList<>();
                        }

                        events.add(new EntryEvent(oldEntry, newEntry));
                    }
                }

                if (watchAndEvents.isEmpty()) {
                    watchAndEvents = new ArrayList<>();
                }

                watchAndEvents.add(new WatchAndEvents(watch, events));
            }

            return watchAndEvents;
        }, watchExecutor);
    }

    private CompletableFuture<Void> invokeOnRevisionCallback(
            CompletableFuture<List<WatchAndEvents>> watchAndEventsFuture,
            long revision,
            HybridTimestamp time
    ) {
        try {
            // Only notify about entries that have been accepted by at least one Watch.
            var acceptedEntries = new HashSet<EntryEvent>();

            assert watchAndEventsFuture.isDone();

            for (WatchAndEvents watchAndEvents : watchAndEventsFuture.join()) {
                acceptedEntries.addAll(watchAndEvents.events);
            }

            var event = new WatchEvent(acceptedEntries, revision, time);

            revisionCallback.onSafeTimeAdvanced(time);

            return revisionCallback.onRevisionApplied(event)
                    .whenComplete((ignored, e) -> {
                        if (e != null) {
                            LOG.error("Error occurred when notifying watches", e);
                        }
                    });
        } catch (Throwable e) {
            LOG.error("Error occurred when notifying watches", e);

            throw e;
        }
    }

    /**
     * Advances safe time without notifying watches (as there is no new revision).
     */
    public void advanceSafeTime(HybridTimestamp time) {
        assert time != null;

        notificationFuture = notificationFuture
                .thenRunAsync(() -> revisionCallback.onSafeTimeAdvanced(time), watchExecutor)
                .whenComplete((ignored, e) -> {
                    if (e != null) {
                        LOG.error("Error occurred when notifying safe time advanced callback", e);
                    }
                });
    }

    @Override
    public void close() {
        notificationFuture.cancel(true);

        IgniteUtils.shutdownAndAwaitTermination(watchExecutor, 10, TimeUnit.SECONDS);
    }

    /** Registers a Meta Storage revision update listener. */
    public void registerRevisionUpdateListener(RevisionUpdateListener listener) {
        revisionUpdateListeners.add(listener);
    }

    /** Unregisters a Meta Storage revision update listener. */
    public void unregisterRevisionUpdateListener(RevisionUpdateListener listener) {
        revisionUpdateListeners.remove(listener);
    }

    /** Explicitly notifies revision update listeners. */
    public CompletableFuture<Void> notifyUpdateRevisionListeners(long newRevision) {
        // Lazy set.
        List<CompletableFuture<?>> futures = List.of();

        for (RevisionUpdateListener listener : revisionUpdateListeners) {
            if (futures.isEmpty()) {
                futures = new ArrayList<>();
            }

            futures.add(listener.onUpdated(newRevision));
        }

        return futures.isEmpty() ? completedFuture(null) : allOf(futures.toArray(CompletableFuture[]::new));
    }
}
