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
package org.apache.ignite.raft.jraft.rpc;

import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.ignite.raft.jraft.RaftMessagesFactory;
import org.apache.ignite.raft.jraft.util.AdaptiveBufAllocator;
import org.apache.ignite.raft.jraft.util.ByteBufferCollector;
import org.apache.ignite.raft.jraft.util.ByteString;
import org.apache.ignite.raft.jraft.util.Marshaller;
import org.apache.ignite.raft.jraft.util.RecyclableByteBufferList;
import org.apache.ignite.raft.jraft.util.RecycleUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 *
 */
@State(Scope.Benchmark)
public class AppendEntriesBenchmark {

    /**
     * entryCount=256, sizeOfEntry=2048 ---------------------------------------------------------------------------
     * Benchmark                                  Mode  Cnt  Score   Error   Units AppendEntriesBenchmark.adaptiveAndPooled
     *  thrpt    3  4.139 ± 2.662  ops/ms AppendEntriesBenchmark.copy               thrpt    3  0.148 ± 0.027  ops/ms
     * AppendEntriesBenchmark.pooled             thrpt    3  3.730 ± 0.355  ops/ms AppendEntriesBenchmark.zeroCopy
     * thrpt    3  3.069 ± 3.563  ops/ms
     *
     *
     * entryCount=256, sizeOfEntry=1024 ---------------------------------------------------------------------------
     * Benchmark                                  Mode  Cnt  Score   Error   Units AppendEntriesBenchmark.adaptiveAndPooled
     *  thrpt    3  8.290 ± 5.438  ops/ms AppendEntriesBenchmark.copy               thrpt    3  0.326 ± 0.137  ops/ms
     * AppendEntriesBenchmark.pooled             thrpt    3  7.559 ± 1.245  ops/ms AppendEntriesBenchmark.zeroCopy
     * thrpt    3  6.602 ± 0.859  ops/ms
     *
     * entryCount=256, sizeOfEntry=512 ---------------------------------------------------------------------------
     *
     * Benchmark                                  Mode  Cnt   Score   Error   Units
     * AppendEntriesBenchmark.adaptiveAndPooled  thrpt    3  14.358 ± 8.622  ops/ms AppendEntriesBenchmark.copy
     *      thrpt    3   1.625 ± 0.058  ops/ms AppendEntriesBenchmark.pooled             thrpt    3  15.332 ± 1.531
     * ops/ms AppendEntriesBenchmark.zeroCopy           thrpt    3  12.614 ± 5.904  ops/ms
     *
     * entryCount=256, sizeOfEntry=256 ---------------------------------------------------------------------------
     * Benchmark                                  Mode  Cnt   Score    Error   Units
     * AppendEntriesBenchmark.adaptiveAndPooled  thrpt    3  32.506 ± 21.961  ops/ms AppendEntriesBenchmark.copy
     *       thrpt    3   6.595 ±  5.772  ops/ms AppendEntriesBenchmark.pooled             thrpt    3  27.847 ± 14.010
     * ops/ms AppendEntriesBenchmark.zeroCopy           thrpt    3  26.427 ±  5.187  ops/ms
     *
     * entryCount=256, sizeOfEntry=128 ---------------------------------------------------------------------------
     * Benchmark                                  Mode  Cnt   Score    Error   Units
     * AppendEntriesBenchmark.adaptiveAndPooled  thrpt    3  60.014 ± 47.206  ops/ms AppendEntriesBenchmark.copy
     *       thrpt    3  22.884 ±  3.286  ops/ms AppendEntriesBenchmark.pooled             thrpt    3  57.373 ±  8.201
     * ops/ms AppendEntriesBenchmark.zeroCopy           thrpt    3  43.923 ±  7.133  ops/ms
     *
     * entryCount=256, sizeOfEntry=64 ---------------------------------------------------------------------------
     * Benchmark                                  Mode  Cnt    Score    Error   Units
     * AppendEntriesBenchmark.adaptiveAndPooled  thrpt    3  114.016 ± 84.874  ops/ms AppendEntriesBenchmark.copy
     *        thrpt    3   71.699 ± 19.016  ops/ms AppendEntriesBenchmark.pooled             thrpt    3  107.714 ±
     * 7.944  ops/ms AppendEntriesBenchmark.zeroCopy           thrpt    3   71.767 ± 14.510  ops/ms
     *
     * entryCount=256, sizeOfEntry=16 ---------------------------------------------------------------------------
     * Benchmark                                  Mode  Cnt    Score     Error   Units
     * AppendEntriesBenchmark.adaptiveAndPooled  thrpt    3  285.386 ± 114.361  ops/ms AppendEntriesBenchmark.copy
     *         thrpt    3  243.805 ±  31.725  ops/ms AppendEntriesBenchmark.pooled             thrpt    3  293.779 ±
     * 76.557  ops/ms AppendEntriesBenchmark.zeroCopy           thrpt    3  124.669 ±  32.460  ops/ms
     */

    private static final ThreadLocal<AdaptiveBufAllocator.Handle> handleThreadLocal = ThreadLocal
        .withInitial(AdaptiveBufAllocator.DEFAULT::newHandle);

    private int entryCount;
    private int sizeOfEntry;

    private final RaftMessagesFactory msgFactory = new RaftMessagesFactory();

    @Setup
    public void setup() {
        this.entryCount = 256;
        this.sizeOfEntry = 2048;
    }

    public static void main(String[] args) throws RunnerException {
        final int size = ThreadLocalRandom.current().nextInt(100, 1000);

        AppendEntriesBenchmark benchmark = new AppendEntriesBenchmark();
        benchmark.entryCount = 256;
        benchmark.sizeOfEntry = size;

        System.out.println(benchmark.sendEntries1().length);
        System.out.println(benchmark.sendEntries2().length);
        System.out.println(benchmark.sendEntries3().length);
        System.out.println(benchmark.sendEntries4().length);

        Options opt = new OptionsBuilder() //
            .include(AppendEntriesBenchmark.class.getSimpleName()) //
            .warmupIterations(1) //
            .warmupTime(TimeValue.seconds(5)) //
            .measurementIterations(3) //
            .measurementTime(TimeValue.seconds(10)) //
            .threads(8) //
            .forks(1) //
            .build();

        new Runner(opt).run();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void copy() {
        sendEntries1();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void pooled() {
        sendEntries2();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void adaptiveAndPooled() {
        sendEntries3();
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void zeroCopy() {
        sendEntries4();
    }

    private byte[] sendEntries1() {
        final AppendEntriesRequestBuilder rb = msgFactory.appendEntriesRequest();
        fillCommonFields(rb);
        final ByteBufferCollector dataBuffer = ByteBufferCollector.allocate();
        for (int i = 0; i < entryCount; i++) {
            final byte[] bytes = new byte[sizeOfEntry];
            ThreadLocalRandom.current().nextBytes(bytes);
            final ByteBuffer buf = ByteBuffer.wrap(bytes);
            dataBuffer.put(buf.slice());
        }
        final ByteBuffer buf = dataBuffer.getBuffer();
        buf.flip();
        rb.data(new ByteString(buf));
        return Marshaller.DEFAULT.marshall(rb.build());
    }

    private byte[] sendEntries2() {
        final AppendEntriesRequestBuilder rb = msgFactory.appendEntriesRequest();
        fillCommonFields(rb);
        final ByteBufferCollector dataBuffer = ByteBufferCollector.allocateByRecyclers();
        try {
            for (int i = 0; i < entryCount; i++) {
                final byte[] bytes = new byte[sizeOfEntry];
                ThreadLocalRandom.current().nextBytes(bytes);
                final ByteBuffer buf = ByteBuffer.wrap(bytes);
                dataBuffer.put(buf.slice());
            }
            final ByteBuffer buf = dataBuffer.getBuffer();
            buf.flip();
            rb.data(new ByteString(buf));
            return Marshaller.DEFAULT.marshall(rb.build());
        }
        finally {
            RecycleUtil.recycle(dataBuffer);
        }
    }

    private byte[] sendEntries3() {
        final AppendEntriesRequestBuilder rb = msgFactory.appendEntriesRequest();
        fillCommonFields(rb);
        final ByteBufferCollector dataBuffer = handleThreadLocal.get().allocateByRecyclers();
        try {
            for (int i = 0; i < entryCount; i++) {
                final byte[] bytes = new byte[sizeOfEntry];
                ThreadLocalRandom.current().nextBytes(bytes);
                final ByteBuffer buf = ByteBuffer.wrap(bytes);
                dataBuffer.put(buf.slice());
            }
            final ByteBuffer buf = dataBuffer.getBuffer();
            buf.flip();
            final int remaining = buf.remaining();
            handleThreadLocal.get().record(remaining);
            rb.data(new ByteString(buf));
            return Marshaller.DEFAULT.marshall(rb.build());
        }
        finally {
            RecycleUtil.recycle(dataBuffer);
        }
    }

    private byte[] sendEntries4() {
        final AppendEntriesRequestBuilder rb = msgFactory.appendEntriesRequest();
        fillCommonFields(rb);
        final RecyclableByteBufferList dataBuffer = RecyclableByteBufferList.newInstance();

        try {
            for (int i = 0; i < entryCount; i++) {
                final byte[] bytes = new byte[sizeOfEntry];
                ThreadLocalRandom.current().nextBytes(bytes);
                final ByteBuffer buf = ByteBuffer.wrap(bytes);
                dataBuffer.add(buf.slice());
            }
            rb.data(RecyclableByteBufferList.concatenate(dataBuffer));
            return Marshaller.DEFAULT.marshall(rb.build());
        }
        finally {
            RecycleUtil.recycle(dataBuffer);
        }
    }

    private static void fillCommonFields(final AppendEntriesRequestBuilder rb) {
        rb.term(1)
            .groupId("1")
            .serverId("test")
            .peerId("127.0.0.1:8080")
            .prevLogIndex(2)
            .prevLogTerm(3)
            .committedIndex(4);
    }
}
