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

package org.apache.ignite.internal.pagememory.persistence.checkpoint;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import org.apache.ignite.internal.components.LongJvmPauseDetector;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.pagememory.DataRegion;
import org.apache.ignite.internal.pagememory.FullPageId;
import org.apache.ignite.internal.pagememory.PageMemory;
import org.apache.ignite.internal.pagememory.configuration.schema.PageMemoryCheckpointConfiguration;
import org.apache.ignite.internal.pagememory.configuration.schema.PageMemoryCheckpointView;
import org.apache.ignite.internal.pagememory.io.PageIoRegistry;
import org.apache.ignite.internal.pagememory.persistence.GroupPartitionId;
import org.apache.ignite.internal.pagememory.persistence.PartitionMetaManager;
import org.apache.ignite.internal.pagememory.persistence.PersistentPageMemory;
import org.apache.ignite.internal.pagememory.persistence.checkpoint.CheckpointDirtyPages.CheckpointDirtyPagesView;
import org.apache.ignite.internal.pagememory.persistence.compaction.Compactor;
import org.apache.ignite.internal.pagememory.persistence.store.DeltaFilePageStoreIo;
import org.apache.ignite.internal.pagememory.persistence.store.FilePageStore;
import org.apache.ignite.internal.pagememory.persistence.store.FilePageStoreManager;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.internal.util.worker.IgniteWorkerListener;
import org.apache.ignite.lang.IgniteInternalCheckedException;
import org.jetbrains.annotations.Nullable;

/**
 * Main class to abstract checkpoint-related processes and actions and hide them from higher-level components.
 *
 * <p>Implements sharp checkpointing algorithm.
 *
 * <p>Represents only an intermediate step in refactoring of checkpointing component and may change in the future.
 *
 * <p>This checkpoint ensures that all pages marked as dirty under {@link #checkpointTimeoutLock} will be consistently saved to disk.
 *
 * <p>Configuration of this checkpoint allows the following:
 * <ul>
 *     <li>Collecting all pages from configured dataRegions which was marked as dirty under {@link #checkpointTimeoutLock}.</li>
 *     <li>Marking the start of checkpoint on disk.</li>
 *     <li>Notifying the subscribers of different checkpoint states through {@link CheckpointListener}.</li>
 *     <li>Synchronizing collected pages with disk using {@link FilePageStoreManager}.</li>
 * </ul>
 */
public class CheckpointManager {
    /** Checkpoint worker. */
    private final Checkpointer checkpointer;

    /** Main checkpoint steps. */
    private final CheckpointWorkflow checkpointWorkflow;

    /** Timeout checkpoint lock which should be used while write to memory happened. */
    private final CheckpointTimeoutLock checkpointTimeoutLock;

    /** Checkpoint page writer factory. */
    private final CheckpointPagesWriterFactory checkpointPagesWriterFactory;

    /** File page store manager. */
    private final FilePageStoreManager filePageStoreManager;

    /** Delta file compactor. */
    private final Compactor compactor;

    /**
     * Constructor.
     *
     * @param igniteInstanceName Ignite instance name.
     * @param checkpointConfig Checkpoint configuration.
     * @param workerListener Listener for life-cycle checkpoint worker events.
     * @param longJvmPauseDetector Long JVM pause detector.
     * @param filePageStoreManager File page store manager.
     * @param partitionMetaManager Partition meta information manager.
     * @param dataRegions Data regions.
     * @param ioRegistry Page IO registry.
     * @param pageSize Page size in bytes.
     * @throws IgniteInternalCheckedException If failed.
     */
    public CheckpointManager(
            String igniteInstanceName,
            @Nullable IgniteWorkerListener workerListener,
            @Nullable LongJvmPauseDetector longJvmPauseDetector,
            PageMemoryCheckpointConfiguration checkpointConfig,
            FilePageStoreManager filePageStoreManager,
            PartitionMetaManager partitionMetaManager,
            Collection<? extends DataRegion<PersistentPageMemory>> dataRegions,
            PageIoRegistry ioRegistry,
            // TODO: IGNITE-17017 Move to common config
            int pageSize
    ) throws IgniteInternalCheckedException {
        this.filePageStoreManager = filePageStoreManager;

        PageMemoryCheckpointView checkpointConfigView = checkpointConfig.value();

        long logReadLockThresholdTimeout = checkpointConfigView.logReadLockThresholdTimeout();

        ReentrantReadWriteLockWithTracking reentrantReadWriteLockWithTracking = logReadLockThresholdTimeout > 0
                ? new ReentrantReadWriteLockWithTracking(Loggers.forClass(CheckpointReadWriteLock.class), logReadLockThresholdTimeout)
                : new ReentrantReadWriteLockWithTracking();

        CheckpointReadWriteLock checkpointReadWriteLock = new CheckpointReadWriteLock(reentrantReadWriteLockWithTracking);

        checkpointWorkflow = new CheckpointWorkflow(
                igniteInstanceName,
                checkpointReadWriteLock,
                dataRegions,
                checkpointConfigView.checkpointThreads()
        );

        checkpointPagesWriterFactory = new CheckpointPagesWriterFactory(
                (pageMemory, fullPageId, pageBuf) -> writePageToDeltaFilePageStore(pageMemory, fullPageId, pageBuf, true),
                ioRegistry,
                partitionMetaManager,
                pageSize
        );

        compactor = new Compactor(
                Loggers.forClass(Compactor.class),
                igniteInstanceName,
                workerListener,
                checkpointConfig.compactionThreads(),
                filePageStoreManager,
                pageSize
        );

        checkpointer = new Checkpointer(
                igniteInstanceName,
                workerListener,
                longJvmPauseDetector,
                checkpointWorkflow,
                checkpointPagesWriterFactory,
                filePageStoreManager,
                compactor,
                checkpointConfig
        );

        checkpointTimeoutLock = new CheckpointTimeoutLock(
                checkpointReadWriteLock,
                checkpointConfigView.readLockTimeout(),
                () -> safeToUpdateAllPageMemories(dataRegions),
                checkpointer
        );
    }

    /**
     * Starts a checkpoint manger.
     */
    public void start() {
        checkpointWorkflow.start();

        checkpointer.start();

        checkpointTimeoutLock.start();

        compactor.start();
    }

    /**
     * Stops a checkpoint manger.
     */
    public void stop() throws Exception {
        IgniteUtils.closeAll(
                checkpointTimeoutLock::stop,
                checkpointer::stop,
                checkpointWorkflow::stop,
                compactor::stop
        );
    }

    /**
     * Returns checkpoint timeout lock which can be used for protection of writing to memory.
     */
    public CheckpointTimeoutLock checkpointTimeoutLock() {
        return checkpointTimeoutLock;
    }

    /**
     * Adds a listener to be called for the corresponding persistent data region.
     *
     * @param listener Listener.
     * @param dataRegion Persistent data region for which listener is corresponded to, {@code null} for all regions.
     */
    public void addCheckpointListener(CheckpointListener listener, @Nullable DataRegion<PersistentPageMemory> dataRegion) {
        checkpointWorkflow.addCheckpointListener(listener, dataRegion);
    }

    /**
     * Removes the listener.
     *
     * @param listener Listener.
     */
    public void removeCheckpointListener(CheckpointListener listener) {
        checkpointWorkflow.removeCheckpointListener(listener);
    }

    /**
     * Start the new checkpoint immediately.
     *
     * @param reason Checkpoint reason.
     * @return Triggered checkpoint progress.
     */
    public CheckpointProgress forceCheckpoint(String reason) {
        return checkpointer.scheduleCheckpoint(0, reason);
    }

    /**
     * Schedules a checkpoint in the future.
     *
     * @param delayMillis Delay in milliseconds from the curent moment.
     * @param reason Checkpoint reason.
     * @return Triggered checkpoint progress.
     */
    public CheckpointProgress scheduleCheckpoint(long delayMillis, String reason) {
        return checkpointer.scheduleCheckpoint(delayMillis, reason);
    }

    /**
     * Returns the progress of the last checkpoint, or the current checkpoint if in progress, {@code null} if no checkpoint has occurred.
     */
    public @Nullable CheckpointProgress lastCheckpointProgress() {
        return checkpointer.lastCheckpointProgress();
    }

    /**
     * Returns {@link true} if it is safe for all {@link DataRegion data regions} to update their {@link PageMemory}.
     *
     * @param dataRegions Data regions.
     * @see PersistentPageMemory#safeToUpdate()
     */
    static boolean safeToUpdateAllPageMemories(Collection<? extends DataRegion<PersistentPageMemory>> dataRegions) {
        for (DataRegion<PersistentPageMemory> dataRegion : dataRegions) {
            if (!dataRegion.pageMemory().safeToUpdate()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Writes a page to delta file page store.
     *
     * <p>Must be used at breakpoint and page replacement.
     *
     * @param pageMemory Page memory.
     * @param pageId Page ID.
     * @param pageBuf Page buffer to write from.
     * @param calculateCrc If {@code false} crc calculation will be forcibly skipped.
     * @throws IgniteInternalCheckedException If page writing failed (IO error occurred).
     */
    public void writePageToDeltaFilePageStore(
            PersistentPageMemory pageMemory,
            FullPageId pageId,
            ByteBuffer pageBuf,
            boolean calculateCrc
    ) throws IgniteInternalCheckedException {
        FilePageStore filePageStore = filePageStoreManager.getStore(new GroupPartitionId(pageId.groupId(), pageId.partitionId()));

        // If the partition is deleted (or will be soon), then such writes to the disk should be skipped.
        if (filePageStore == null || filePageStore.isMarkedToDestroy()) {
            return;
        }

        CheckpointProgress lastCheckpointProgress = lastCheckpointProgress();

        assert lastCheckpointProgress != null : "Checkpoint has not happened yet";
        assert lastCheckpointProgress.inProgress() : "Checkpoint must be in progress";

        CheckpointDirtyPages pagesToWrite = lastCheckpointProgress.pagesToWrite();

        assert pagesToWrite != null : "Dirty pages must be sorted out";

        CompletableFuture<DeltaFilePageStoreIo> deltaFilePageStoreFuture = filePageStore.getOrCreateNewDeltaFile(
                index -> filePageStoreManager.tmpDeltaFilePageStorePath(pageId.groupId(), pageId.partitionId(), index),
                () -> pageIndexesForDeltaFilePageStore(pagesToWrite.getPartitionView(pageMemory, pageId.groupId(), pageId.partitionId()))
        );

        deltaFilePageStoreFuture.join().write(pageId.pageId(), pageBuf, calculateCrc);
    }

    /**
     * Returns the indexes of the dirty pages to be written to the delta file page store.
     *
     * @param partitionDirtyPages Dirty pages of the partition.
     */
    static int[] pageIndexesForDeltaFilePageStore(CheckpointDirtyPagesView partitionDirtyPages) {
        // +1 since the first page (pageIdx == 0) will always be PartitionMetaIo.
        int[] pageIndexes = new int[partitionDirtyPages.size() + 1];

        for (int i = 0; i < partitionDirtyPages.size(); i++) {
            pageIndexes[i + 1] = partitionDirtyPages.get(i).pageIdx();
        }

        return pageIndexes;
    }

    /**
     * Triggers compacting for new delta files.
     */
    public void triggerCompaction() {
        compactor.triggerCompaction();
    }

    /**
     * Callback on destruction of the partition of the corresponding group.
     *
     * <p>Prepares the checkpointer and compactor for partition destruction.
     *
     * @param groupPartitionId Pair of group ID with partition ID.
     * @return Future that will complete when the callback completes.
     */
    public CompletableFuture<Void> onPartitionDestruction(GroupPartitionId groupPartitionId) {
        return CompletableFuture.allOf(
                checkpointer.prepareToDestroyPartition(groupPartitionId),
                compactor.prepareToDestroyPartition(groupPartitionId)
        );
    }
}
