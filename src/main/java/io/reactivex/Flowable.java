/**
 * Copyright (c) 2016-present, RxJava Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */
package io.reactivex;

import java.util.*;
import java.util.concurrent.*;

import org.reactivestreams.*;

import io.reactivex.annotations.*;
import io.reactivex.disposables.Disposable;
import io.reactivex.exceptions.Exceptions;
import io.reactivex.flowables.*;
import io.reactivex.functions.*;
import io.reactivex.internal.functions.*;
import io.reactivex.internal.fuseable.*;
import io.reactivex.internal.operators.flowable.*;
import io.reactivex.internal.operators.observable.ObservableFromPublisher;
import io.reactivex.internal.schedulers.ImmediateThinScheduler;
import io.reactivex.internal.subscribers.*;
import io.reactivex.internal.util.*;
import io.reactivex.parallel.ParallelFlowable;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.schedulers.*;
import io.reactivex.subscribers.*;

/**
 * The Flowable class that implements the Reactive-Streams Pattern and offers factory methods,
 * intermediate operators and the ability to consume reactive dataflows.
 * <p>
 * Reactive-Streams operates with {@code Publisher}s which {@code Flowable} extends. Many operators
 * therefore accept general {@code Publisher}s directly and allow direct interoperation with other
 * Reactive-Streams implementations.
 * <p>
 * The Flowable hosts the default buffer size of 128 elements for operators, accessible via {@link #bufferSize()},
 * that can be overridden globally via the system parameter {@code rx2.buffer-size}. Most operators, however, have
 * overloads that allow setting their internal buffer size explicitly.
 * <p>
 * The documentation for this class makes use of marble diagrams. The following legend explains these diagrams:
 * <p>
 * <img width="640" height="317" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/legend.png" alt="">
 * <p>
 * For more information see the <a href="http://reactivex.io/documentation/Publisher.html">ReactiveX
 * documentation</a>.
 *
 * @param <T>
 *            the type of the items emitted by the Flowable
 */
public abstract class Flowable<T> implements Publisher<T> {
    /** The default buffer size. */
    static final int BUFFER_SIZE;
    static {
        BUFFER_SIZE = Math.max(1, Integer.getInteger("rx2.buffer-size", 128));
    }

    /**
     * Mirrors the one Publisher in an Iterable of several Publishers that first either emits an item or sends
     * a termination notification.
     * <p>
     * <img width="640" height="385" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/amb.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator itself doesn't interfere with backpressure which is determined by the winning
     *  {@code Publisher}'s backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code amb} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element type
     * @param sources
     *            an Iterable of Publishers sources competing to react first. A subscription to each Publisher will
     *            occur in the same order as in this Iterable.
     * @return a Flowable that emits the same sequence as whichever of the source Publishers first
     *         emitted an item or sent a termination notification
     * @see <a href="http://reactivex.io/documentation/operators/amb.html">ReactiveX operators documentation: Amb</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> amb(Iterable<? extends Publisher<? extends T>> sources) {
        ObjectHelper.requireNonNull(sources, "sources is null");
        return RxJavaPlugins.onAssembly(new FlowableAmb<T>(null, sources));
    }

    /**
     * Mirrors the one Publisher in an array of several Publishers that first either emits an item or sends
     * a termination notification.
     * <p>
     * <img width="640" height="385" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/amb.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator itself doesn't interfere with backpressure which is determined by the winning
     *  {@code Publisher}'s backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code ambArray} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element type
     * @param sources
     *            an array of Publisher sources competing to react first. A subscription to each Publisher will
     *            occur in the same order as in this Iterable.
     * @return a Flowable that emits the same sequence as whichever of the source Publishers first
     *         emitted an item or sent a termination notification
     * @see <a href="http://reactivex.io/documentation/operators/amb.html">ReactiveX operators documentation: Amb</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> ambArray(Publisher<? extends T>... sources) {
        ObjectHelper.requireNonNull(sources, "sources is null");
        int len = sources.length;
        if (len == 0) {
            return empty();
        } else
        if (len == 1) {
            return fromPublisher(sources[0]);
        }
        return RxJavaPlugins.onAssembly(new FlowableAmb<T>(sources, null));
    }

    /**
     * Returns the default internal buffer size used by most async operators.
     * <p>The value can be overridden via system parameter {@code rx2.buffer-size}
     * <em>before</em> the Flowable class is loaded.
     * @return the default internal buffer size.
     */
    public static int bufferSize() {
        return BUFFER_SIZE;
    }

    /**
     * Combines a collection of source Publishers by emitting an item that aggregates the latest values of each of
     * the source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function.
     * <p>
     * Note on method signature: since Java doesn't allow creating a generic array with {@code new T[]}, the
     * implementation of this operator has to create an {@code Object[]} instead. Unfortunately, a
     * {@code Function<Integer[], R>} passed to the method would trigger a {@code ClassCastException}.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * If the provided array of source Publishers is empty, the resulting sequence completes immediately without emitting
     * any items and without any calls to the combiner function.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T>
     *            the common base type of source values
     * @param <R>
     *            the result type
     * @param sources
     *            the collection of source Publishers
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SchedulerSupport(SchedulerSupport.NONE)
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    public static <T, R> Flowable<R> combineLatest(Publisher<? extends T>[] sources, Function<? super Object[], ? extends R> combiner) {
        return combineLatest(sources, combiner, bufferSize());
    }

    /**
     * Combines a collection of source Publishers by emitting an item that aggregates the latest values of each of
     * the source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function.
     * <p>
     * Note on method signature: since Java doesn't allow creating a generic array with {@code new T[]}, the
     * implementation of this operator has to create an {@code Object[]} instead. Unfortunately, a
     * {@code Function<Integer[], R>} passed to the method would trigger a {@code ClassCastException}.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * If there are no source Publishers provided, the resulting sequence completes immediately without emitting
     * any items and without any calls to the combiner function.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T>
     *            the common base type of source values
     * @param <R>
     *            the result type
     * @param sources
     *            the collection of source Publishers
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SchedulerSupport(SchedulerSupport.NONE)
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    public static <T, R> Flowable<R> combineLatest(Function<? super Object[], ? extends R> combiner, Publisher<? extends T>... sources) {
        return combineLatest(sources, combiner, bufferSize());
    }

    /**
     * Combines a collection of source Publishers by emitting an item that aggregates the latest values of each of
     * the source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function.
     * <p>
     * Note on method signature: since Java doesn't allow creating a generic array with {@code new T[]}, the
     * implementation of this operator has to create an {@code Object[]} instead. Unfortunately, a
     * {@code Function<Integer[], R>} passed to the method would trigger a {@code ClassCastException}.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * If the provided array of source Publishers is empty, the resulting sequence completes immediately without emitting
     * any items and without any calls to the combiner function.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T>
     *            the common base type of source values
     * @param <R>
     *            the result type
     * @param sources
     *            the collection of source Publishers
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @param bufferSize
     *            the internal buffer size and prefetch amount applied to every source Flowable
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SchedulerSupport(SchedulerSupport.NONE)
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    public static <T, R> Flowable<R> combineLatest(Publisher<? extends T>[] sources, Function<? super Object[], ? extends R> combiner, int bufferSize) {
        ObjectHelper.requireNonNull(sources, "sources is null");
        if (sources.length == 0) {
            return empty();
        }
        ObjectHelper.requireNonNull(combiner, "combiner is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return RxJavaPlugins.onAssembly(new FlowableCombineLatest<T, R>(sources, combiner, bufferSize, false));
    }

    /**
     * Combines a collection of source Publishers by emitting an item that aggregates the latest values of each of
     * the source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function.
     * <p>
     * Note on method signature: since Java doesn't allow creating a generic array with {@code new T[]}, the
     * implementation of this operator has to create an {@code Object[]} instead. Unfortunately, a
     * {@code Function<Integer[], R>} passed to the method would trigger a {@code ClassCastException}.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * If the provided iterable of source Publishers is empty, the resulting sequence completes immediately without emitting
     * any items and without any calls to the combiner function.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T>
     *            the common base type of source values
     * @param <R>
     *            the result type
     * @param sources
     *            the collection of source Publishers
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SchedulerSupport(SchedulerSupport.NONE)
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    public static <T, R> Flowable<R> combineLatest(Iterable<? extends Publisher<? extends T>> sources,
            Function<? super Object[], ? extends R> combiner) {
        return combineLatest(sources, combiner, bufferSize());
    }

    /**
     * Combines a collection of source Publishers by emitting an item that aggregates the latest values of each of
     * the source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function.
     * <p>
     * Note on method signature: since Java doesn't allow creating a generic array with {@code new T[]}, the
     * implementation of this operator has to create an {@code Object[]} instead. Unfortunately, a
     * {@code Function<Integer[], R>} passed to the method would trigger a {@code ClassCastException}.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * If the provided iterable of source Publishers is empty, the resulting sequence completes immediately without emitting any items and
     * without any calls to the combiner function.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T>
     *            the common base type of source values
     * @param <R>
     *            the result type
     * @param sources
     *            the collection of source Publishers
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @param bufferSize
     *            the internal buffer size and prefetch amount applied to every source Flowable
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SchedulerSupport(SchedulerSupport.NONE)
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    public static <T, R> Flowable<R> combineLatest(Iterable<? extends Publisher<? extends T>> sources,
            Function<? super Object[], ? extends R> combiner, int bufferSize) {
        ObjectHelper.requireNonNull(sources, "sources is null");
        ObjectHelper.requireNonNull(combiner, "combiner is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return RxJavaPlugins.onAssembly(new FlowableCombineLatest<T, R>(sources, combiner, bufferSize, false));
    }

    /**
     * Combines a collection of source Publishers by emitting an item that aggregates the latest values of each of
     * the source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function.
     * <p>
     * Note on method signature: since Java doesn't allow creating a generic array with {@code new T[]}, the
     * implementation of this operator has to create an {@code Object[]} instead. Unfortunately, a
     * {@code Function<Integer[], R>} passed to the method would trigger a {@code ClassCastException}.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * If the provided array of source Publishers is empty, the resulting sequence completes immediately without emitting
     * any items and without any calls to the combiner function.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatestDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T>
     *            the common base type of source values
     * @param <R>
     *            the result type
     * @param sources
     *            the collection of source Publishers
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SchedulerSupport(SchedulerSupport.NONE)
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    public static <T, R> Flowable<R> combineLatestDelayError(Publisher<? extends T>[] sources,
            Function<? super Object[], ? extends R> combiner) {
        return combineLatestDelayError(sources, combiner, bufferSize());
    }

    /**
     * Combines a collection of source Publishers by emitting an item that aggregates the latest values of each of
     * the source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function and delays any error from the sources until
     * all source Publishers terminate.
     * <p>
     * Note on method signature: since Java doesn't allow creating a generic array with {@code new T[]}, the
     * implementation of this operator has to create an {@code Object[]} instead. Unfortunately, a
     * {@code Function<Integer[], R>} passed to the method would trigger a {@code ClassCastException}.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * If there are no source Publishers provided, the resulting sequence completes immediately without emitting
     * any items and without any calls to the combiner function.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatestDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T>
     *            the common base type of source values
     * @param <R>
     *            the result type
     * @param sources
     *            the collection of source Publishers
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SchedulerSupport(SchedulerSupport.NONE)
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    public static <T, R> Flowable<R> combineLatestDelayError(Function<? super Object[], ? extends R> combiner,
            Publisher<? extends T>... sources) {
        return combineLatestDelayError(sources, combiner, bufferSize());
    }

    /**
     * Combines a collection of source Publishers by emitting an item that aggregates the latest values of each of
     * the source Publishers each time an item is received from any of the source Publisher, where this
     * aggregation is defined by a specified function and delays any error from the sources until
     * all source Publishers terminate.
     * <p>
     * Note on method signature: since Java doesn't allow creating a generic array with {@code new T[]}, the
     * implementation of this operator has to create an {@code Object[]} instead. Unfortunately, a
     * {@code Function<Integer[], R>} passed to the method would trigger a {@code ClassCastException}.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * If there are no source Publishers provided, the resulting sequence completes immediately without emitting
     * any items and without any calls to the combiner function.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatestDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T>
     *            the common base type of source values
     * @param <R>
     *            the result type
     * @param sources
     *            the collection of source Publishers
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @param bufferSize
     *            the internal buffer size and prefetch amount applied to every source Publisher
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SchedulerSupport(SchedulerSupport.NONE)
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    public static <T, R> Flowable<R> combineLatestDelayError(Function<? super Object[], ? extends R> combiner,
            int bufferSize, Publisher<? extends T>... sources) {
        return combineLatestDelayError(sources, combiner, bufferSize);
    }

    /**
     * Combines a collection of source Publishers by emitting an item that aggregates the latest values of each of
     * the source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function and delays any error from the sources until
     * all source Publishers terminate.
     * <p>
     * Note on method signature: since Java doesn't allow creating a generic array with {@code new T[]}, the
     * implementation of this operator has to create an {@code Object[]} instead. Unfortunately, a
     * {@code Function<Integer[], R>} passed to the method would trigger a {@code ClassCastException}.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * If the provided array of source Publishers is empty, the resulting sequence completes immediately without emitting
     * any items and without any calls to the combiner function.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatestDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T>
     *            the common base type of source values
     * @param <R>
     *            the result type
     * @param sources
     *            the collection of source Publishers
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @param bufferSize
     *            the internal buffer size and prefetch amount applied to every source Flowable
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SchedulerSupport(SchedulerSupport.NONE)
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    public static <T, R> Flowable<R> combineLatestDelayError(Publisher<? extends T>[] sources,
            Function<? super Object[], ? extends R> combiner, int bufferSize) {
        ObjectHelper.requireNonNull(sources, "sources is null");
        ObjectHelper.requireNonNull(combiner, "combiner is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        if (sources.length == 0) {
            return empty();
        }
        return RxJavaPlugins.onAssembly(new FlowableCombineLatest<T, R>(sources, combiner, bufferSize, true));
    }

    /**
     * Combines a collection of source Publishers by emitting an item that aggregates the latest values of each of
     * the source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function and delays any error from the sources until
     * all source Publishers terminate.
     * <p>
     * Note on method signature: since Java doesn't allow creating a generic array with {@code new T[]}, the
     * implementation of this operator has to create an {@code Object[]} instead. Unfortunately, a
     * {@code Function<Integer[], R>} passed to the method would trigger a {@code ClassCastException}.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * If the provided iterable of source Publishers is empty, the resulting sequence completes immediately without emitting
     * any items and without any calls to the combiner function.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatestDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T>
     *            the common base type of source values
     * @param <R>
     *            the result type
     * @param sources
     *            the collection of source Publishers
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SchedulerSupport(SchedulerSupport.NONE)
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    public static <T, R> Flowable<R> combineLatestDelayError(Iterable<? extends Publisher<? extends T>> sources,
            Function<? super Object[], ? extends R> combiner) {
        return combineLatestDelayError(sources, combiner, bufferSize());
    }

    /**
     * Combines a collection of source Publishers by emitting an item that aggregates the latest values of each of
     * the source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function and delays any error from the sources until
     * all source Publishers terminate.
     * <p>
     * Note on method signature: since Java doesn't allow creating a generic array with {@code new T[]}, the
     * implementation of this operator has to create an {@code Object[]} instead. Unfortunately, a
     * {@code Function<Integer[], R>} passed to the method would trigger a {@code ClassCastException}.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * If the provided iterable of source Publishers is empty, the resulting sequence completes immediately without emitting
     * any items and without any calls to the combiner function.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatestDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T>
     *            the common base type of source values
     * @param <R>
     *            the result type
     * @param sources
     *            the collection of source Publishers
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @param bufferSize
     *            the internal buffer size and prefetch amount applied to every source Flowable
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SchedulerSupport(SchedulerSupport.NONE)
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    public static <T, R> Flowable<R> combineLatestDelayError(Iterable<? extends Publisher<? extends T>> sources,
            Function<? super Object[], ? extends R> combiner, int bufferSize) {
        ObjectHelper.requireNonNull(sources, "sources is null");
        ObjectHelper.requireNonNull(combiner, "combiner is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return RxJavaPlugins.onAssembly(new FlowableCombineLatest<T, R>(sources, combiner, bufferSize, true));
    }

    /**
     * Combines two source Publishers by emitting an item that aggregates the latest values of each of the
     * source Publishers each time an item is received from either of the source Publishers, where this
     * aggregation is defined by a specified function.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/combineLatest.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the element type of the first source
     * @param <T2> the element type of the second source
     * @param <R> the combined output type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            the second source Publisher
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, R> Flowable<R> combineLatest(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2,
            BiFunction<? super T1, ? super T2, ? extends R> combiner) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        Function<Object[], R> f = Functions.toFunction(combiner);
        return combineLatest(f, source1, source2);
    }

    /**
     * Combines three source Publishers by emitting an item that aggregates the latest values of each of the
     * source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/combineLatest.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the element type of the first source
     * @param <T2> the element type of the second source
     * @param <T3> the element type of the third source
     * @param <R> the combined output type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            the second source Publisher
     * @param source3
     *            the third source Publisher
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, T3, R> Flowable<R> combineLatest(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2,
            Publisher<? extends T3> source3,
            Function3<? super T1, ? super T2, ? super T3, ? extends R> combiner) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        return combineLatest(Functions.toFunction(combiner), source1, source2, source3);
    }

    /**
     * Combines four source Publishers by emitting an item that aggregates the latest values of each of the
     * source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/combineLatest.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the element type of the first source
     * @param <T2> the element type of the second source
     * @param <T3> the element type of the third source
     * @param <T4> the element type of the fourth source
     * @param <R> the combined output type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            the second source Publisher
     * @param source3
     *            the third source Publisher
     * @param source4
     *            the fourth source Publisher
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, T3, T4, R> Flowable<R> combineLatest(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2,
            Publisher<? extends T3> source3, Publisher<? extends T4> source4,
            Function4<? super T1, ? super T2, ? super T3, ? super T4, ? extends R> combiner) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        return combineLatest(Functions.toFunction(combiner), source1, source2, source3, source4);
    }

    /**
     * Combines five source Publishers by emitting an item that aggregates the latest values of each of the
     * source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/combineLatest.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the element type of the first source
     * @param <T2> the element type of the second source
     * @param <T3> the element type of the third source
     * @param <T4> the element type of the fourth source
     * @param <T5> the element type of the fifth source
     * @param <R> the combined output type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            the second source Publisher
     * @param source3
     *            the third source Publisher
     * @param source4
     *            the fourth source Publisher
     * @param source5
     *            the fifth source Publisher
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, T3, T4, T5, R> Flowable<R> combineLatest(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2,
            Publisher<? extends T3> source3, Publisher<? extends T4> source4,
            Publisher<? extends T5> source5,
            Function5<? super T1, ? super T2, ? super T3, ? super T4, ? super T5, ? extends R> combiner) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        ObjectHelper.requireNonNull(source5, "source5 is null");
        return combineLatest(Functions.toFunction(combiner), source1, source2, source3, source4, source5);
    }

    /**
     * Combines six source Publishers by emitting an item that aggregates the latest values of each of the
     * source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/combineLatest.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the element type of the first source
     * @param <T2> the element type of the second source
     * @param <T3> the element type of the third source
     * @param <T4> the element type of the fourth source
     * @param <T5> the element type of the fifth source
     * @param <T6> the element type of the sixth source
     * @param <R> the combined output type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            the second source Publisher
     * @param source3
     *            the third source Publisher
     * @param source4
     *            the fourth source Publisher
     * @param source5
     *            the fifth source Publisher
     * @param source6
     *            the sixth source Publisher
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, T3, T4, T5, T6, R> Flowable<R> combineLatest(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2,
            Publisher<? extends T3> source3, Publisher<? extends T4> source4,
            Publisher<? extends T5> source5, Publisher<? extends T6> source6,
            Function6<? super T1, ? super T2, ? super T3, ? super T4, ? super T5, ? super T6, ? extends R> combiner) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        ObjectHelper.requireNonNull(source5, "source5 is null");
        ObjectHelper.requireNonNull(source6, "source6 is null");
        return combineLatest(Functions.toFunction(combiner), source1, source2, source3, source4, source5, source6);
    }

    /**
     * Combines seven source Publishers by emitting an item that aggregates the latest values of each of the
     * source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/combineLatest.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the element type of the first source
     * @param <T2> the element type of the second source
     * @param <T3> the element type of the third source
     * @param <T4> the element type of the fourth source
     * @param <T5> the element type of the fifth source
     * @param <T6> the element type of the sixth source
     * @param <T7> the element type of the seventh source
     * @param <R> the combined output type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            the second source Publisher
     * @param source3
     *            the third source Publisher
     * @param source4
     *            the fourth source Publisher
     * @param source5
     *            the fifth source Publisher
     * @param source6
     *            the sixth source Publisher
     * @param source7
     *            the seventh source Publisher
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, T3, T4, T5, T6, T7, R> Flowable<R> combineLatest(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2,
            Publisher<? extends T3> source3, Publisher<? extends T4> source4,
            Publisher<? extends T5> source5, Publisher<? extends T6> source6,
            Publisher<? extends T7> source7,
            Function7<? super T1, ? super T2, ? super T3, ? super T4, ? super T5, ? super T6, ? super T7, ? extends R> combiner) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        ObjectHelper.requireNonNull(source5, "source5 is null");
        ObjectHelper.requireNonNull(source6, "source6 is null");
        ObjectHelper.requireNonNull(source7, "source7 is null");
        return combineLatest(Functions.toFunction(combiner), source1, source2, source3, source4, source5, source6, source7);
    }

    /**
     * Combines eight source Publishers by emitting an item that aggregates the latest values of each of the
     * source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/combineLatest.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the element type of the first source
     * @param <T2> the element type of the second source
     * @param <T3> the element type of the third source
     * @param <T4> the element type of the fourth source
     * @param <T5> the element type of the fifth source
     * @param <T6> the element type of the sixth source
     * @param <T7> the element type of the seventh source
     * @param <T8> the element type of the eighth source
     * @param <R> the combined output type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            the second source Publisher
     * @param source3
     *            the third source Publisher
     * @param source4
     *            the fourth source Publisher
     * @param source5
     *            the fifth source Publisher
     * @param source6
     *            the sixth source Publisher
     * @param source7
     *            the seventh source Publisher
     * @param source8
     *            the eighth source Publisher
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, T3, T4, T5, T6, T7, T8, R> Flowable<R> combineLatest(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2,
            Publisher<? extends T3> source3, Publisher<? extends T4> source4,
            Publisher<? extends T5> source5, Publisher<? extends T6> source6,
            Publisher<? extends T7> source7, Publisher<? extends T8> source8,
            Function8<? super T1, ? super T2, ? super T3, ? super T4, ? super T5, ? super T6, ? super T7, ? super T8, ? extends R> combiner) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        ObjectHelper.requireNonNull(source5, "source5 is null");
        ObjectHelper.requireNonNull(source6, "source6 is null");
        ObjectHelper.requireNonNull(source7, "source7 is null");
        ObjectHelper.requireNonNull(source8, "source8 is null");
        return combineLatest(Functions.toFunction(combiner), source1, source2, source3, source4, source5, source6, source7, source8);
    }

    /**
     * Combines nine source Publishers by emitting an item that aggregates the latest values of each of the
     * source Publishers each time an item is received from any of the source Publishers, where this
     * aggregation is defined by a specified function.
     * <p>
     * If any of the sources never produces an item but only terminates (normally or with an error), the
     * resulting sequence terminates immediately (normally or with all the errors accumulated till that point).
     * If that input source is also synchronous, other sources after it will not be subscribed to.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/combineLatest.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code Publisher} honors backpressure from downstream. The source {@code Publisher}s
     *   are requested in a bounded manner, however, their backpressure is not enforced (the operator won't signal
     *   {@code MissingBackpressureException}) and may lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code combineLatest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the element type of the first source
     * @param <T2> the element type of the second source
     * @param <T3> the element type of the third source
     * @param <T4> the element type of the fourth source
     * @param <T5> the element type of the fifth source
     * @param <T6> the element type of the sixth source
     * @param <T7> the element type of the seventh source
     * @param <T8> the element type of the eighth source
     * @param <T9> the element type of the ninth source
     * @param <R> the combined output type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            the second source Publisher
     * @param source3
     *            the third source Publisher
     * @param source4
     *            the fourth source Publisher
     * @param source5
     *            the fifth source Publisher
     * @param source6
     *            the sixth source Publisher
     * @param source7
     *            the seventh source Publisher
     * @param source8
     *            the eighth source Publisher
     * @param source9
     *            the ninth source Publisher
     * @param combiner
     *            the aggregation function used to combine the items emitted by the source Publishers
     * @return a Flowable that emits items that are the result of combining the items emitted by the source
     *         Publishers by means of the given aggregation function
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, R> Flowable<R> combineLatest(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2,
            Publisher<? extends T3> source3, Publisher<? extends T4> source4,
            Publisher<? extends T5> source5, Publisher<? extends T6> source6,
            Publisher<? extends T7> source7, Publisher<? extends T8> source8,
            Publisher<? extends T9> source9,
            Function9<? super T1, ? super T2, ? super T3, ? super T4, ? super T5, ? super T6, ? super T7, ? super T8, ? super T9, ? extends R> combiner) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        ObjectHelper.requireNonNull(source5, "source5 is null");
        ObjectHelper.requireNonNull(source6, "source6 is null");
        ObjectHelper.requireNonNull(source7, "source7 is null");
        ObjectHelper.requireNonNull(source8, "source8 is null");
        ObjectHelper.requireNonNull(source9, "source9 is null");
        return combineLatest(Functions.toFunction(combiner), source1, source2, source3, source4, source5, source6, source7, source8, source9);
    }

    /**
     * Concatenates elements of each Publisher provided via an Iterable sequence into a single sequence
     * of elements without interleaving them.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/concat.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The {@code Publisher}
     *  sources are expected to honor backpressure as well.
     *  If any of the source {@code Publisher}s violate this, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concat} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <T> the common value type of the sources
     * @param sources the Iterable sequence of Publishers
     * @return the new Flowable instance
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> concat(Iterable<? extends Publisher<? extends T>> sources) {
        ObjectHelper.requireNonNull(sources, "sources is null");
        // unlike general sources, fromIterable can only throw on a boundary because it is consumed only there
        return fromIterable(sources).concatMapDelayError((Function)Functions.identity(), 2, false);
    }

    /**
     * Returns a Flowable that emits the items emitted by each of the Publishers emitted by the source
     * Publisher, one after the other, without interleaving them.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/concat.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. Both the outer and inner {@code Publisher}
     *  sources are expected to honor backpressure as well. If the outer violates this, a
     *  {@code MissingBackpressureException} is signalled. If any of the inner {@code Publisher}s violates
     *  this, it <em>may</em> throw an {@code IllegalStateException} when an inner {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concat} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            a Publisher that emits Publishers
     * @return a Flowable that emits items all of the items emitted by the Publishers emitted by
     *         {@code Publishers}, one after the other, without interleaving them
     * @see <a href="http://reactivex.io/documentation/operators/concat.html">ReactiveX operators documentation: Concat</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> concat(Publisher<? extends Publisher<? extends T>> sources) {
        return concat(sources, bufferSize());
    }

    /**
     * Returns a Flowable that emits the items emitted by each of the Publishers emitted by the source
     * Publisher, one after the other, without interleaving them.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/concat.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. Both the outer and inner {@code Publisher}
     *  sources are expected to honor backpressure as well. If the outer violates this, a
     *  {@code MissingBackpressureException} is signalled. If any of the inner {@code Publisher}s violates
     *  this, it <em>may</em> throw an {@code IllegalStateException} when an inner {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concat} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            a Publisher that emits Publishers
     * @param prefetch
     *            the number of Publishers to prefetch from the sources sequence.
     * @return a Flowable that emits items all of the items emitted by the Publishers emitted by
     *         {@code Publishers}, one after the other, without interleaving them
     * @see <a href="http://reactivex.io/documentation/operators/concat.html">ReactiveX operators documentation: Concat</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> concat(Publisher<? extends Publisher<? extends T>> sources, int prefetch) {
        return fromPublisher(sources).concatMap((Function)Functions.identity(), prefetch);
    }

    /**
     * Returns a Flowable that emits the items emitted by two Publishers, one after the other, without
     * interleaving them.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/concat.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The {@code Publisher}
     *  sources are expected to honor backpressure as well.
     *  If any of the source {@code Publisher}s violate this, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concat} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param source1
     *            a Publisher to be concatenated
     * @param source2
     *            a Publisher to be concatenated
     * @return a Flowable that emits items emitted by the two source Publishers, one after the other,
     *         without interleaving them
     * @see <a href="http://reactivex.io/documentation/operators/concat.html">ReactiveX operators documentation: Concat</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> concat(Publisher<? extends T> source1, Publisher<? extends T> source2) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        return concatArray(source1, source2);
    }

    /**
     * Returns a Flowable that emits the items emitted by three Publishers, one after the other, without
     * interleaving them.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/concat.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The {@code Publisher}
     *  sources are expected to honor backpressure as well.
     *  If any of the source {@code Publisher}s violate this, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concat} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param source1
     *            a Publisher to be concatenated
     * @param source2
     *            a Publisher to be concatenated
     * @param source3
     *            a Publisher to be concatenated
     * @return a Flowable that emits items emitted by the three source Publishers, one after the other,
     *         without interleaving them
     * @see <a href="http://reactivex.io/documentation/operators/concat.html">ReactiveX operators documentation: Concat</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> concat(
            Publisher<? extends T> source1, Publisher<? extends T> source2,
            Publisher<? extends T> source3) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        return concatArray(source1, source2, source3);
    }

    /**
     * Returns a Flowable that emits the items emitted by four Publishers, one after the other, without
     * interleaving them.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/concat.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The {@code Publisher}
     *  sources are expected to honor backpressure as well.
     *  If any of the source {@code Publisher}s violate this, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concat} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param source1
     *            a Publisher to be concatenated
     * @param source2
     *            a Publisher to be concatenated
     * @param source3
     *            a Publisher to be concatenated
     * @param source4
     *            a Publisher to be concatenated
     * @return a Flowable that emits items emitted by the four source Publishers, one after the other,
     *         without interleaving them
     * @see <a href="http://reactivex.io/documentation/operators/concat.html">ReactiveX operators documentation: Concat</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> concat(
            Publisher<? extends T> source1, Publisher<? extends T> source2,
            Publisher<? extends T> source3, Publisher<? extends T> source4) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        return concatArray(source1, source2, source3, source4);
    }

    /**
     * Concatenates a variable number of Publisher sources.
     * <p>
     * Note: named this way because of overload conflict with concat(Publisher&lt;Publisher&gt;).
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/concat.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The {@code Publisher}
     *  sources are expected to honor backpressure as well.
     *  If any of the source {@code Publisher}s violate this, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concatArray} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param sources the array of sources
     * @param <T> the common base value type
     * @return the new Publisher instance
     * @throws NullPointerException if sources is null
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> concatArray(Publisher<? extends T>... sources) {
        if (sources.length == 0) {
            return empty();
        } else
        if (sources.length == 1) {
            return fromPublisher(sources[0]);
        }
        return RxJavaPlugins.onAssembly(new FlowableConcatArray<T>(sources, false));
    }

    /**
     * Concatenates a variable number of Publisher sources and delays errors from any of them
     * till all terminate.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/concat.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The {@code Publisher}
     *  sources are expected to honor backpressure as well.
     *  If any of the source {@code Publisher}s violate this, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concatArrayDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param sources the array of sources
     * @param <T> the common base value type
     * @return the new Flowable instance
     * @throws NullPointerException if sources is null
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> concatArrayDelayError(Publisher<? extends T>... sources) {
        if (sources.length == 0) {
            return empty();
        } else
        if (sources.length == 1) {
            return fromPublisher(sources[0]);
        }
        return RxJavaPlugins.onAssembly(new FlowableConcatArray<T>(sources, true));
    }

    /**
     * Concatenates a sequence of Publishers eagerly into a single stream of values.
     * <p>
     * Eager concatenation means that once a subscriber subscribes, this operator subscribes to all of the
     * source Publishers. The operator buffers the values emitted by these Publishers and then drains them
     * in order, each one after the previous one completes.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The {@code Publisher}
     *  sources are expected to honor backpressure as well.
     *  If any of the source {@code Publisher}s violate this, the operator will signal a
     *  {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This method does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <T> the value type
     * @param sources a sequence of Publishers that need to be eagerly concatenated
     * @return the new Publisher instance with the specified concatenation behavior
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> concatArrayEager(Publisher<? extends T>... sources) {
        return concatArrayEager(bufferSize(), bufferSize(), sources);
    }

    /**
     * Concatenates a sequence of Publishers eagerly into a single stream of values.
     * <p>
     * Eager concatenation means that once a subscriber subscribes, this operator subscribes to all of the
     * source Publishers. The operator buffers the values emitted by these Publishers and then drains them
     * in order, each one after the previous one completes.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The {@code Publisher}
     *  sources are expected to honor backpressure as well.
     *  If any of the source {@code Publisher}s violate this, the operator will signal a
     *  {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This method does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <T> the value type
     * @param sources a sequence of Publishers that need to be eagerly concatenated
     * @param maxConcurrency the maximum number of concurrent subscriptions at a time, Integer.MAX_VALUE
     *                       is interpreted as indication to subscribe to all sources at once
     * @param prefetch the number of elements to prefetch from each Publisher source
     * @return the new Publisher instance with the specified concatenation behavior
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static <T> Flowable<T> concatArrayEager(int maxConcurrency, int prefetch, Publisher<? extends T>... sources) {
        ObjectHelper.requireNonNull(sources, "sources is null");
        ObjectHelper.verifyPositive(maxConcurrency, "maxConcurrency");
        ObjectHelper.verifyPositive(prefetch, "prefetch");
        return RxJavaPlugins.onAssembly(new FlowableConcatMapEager(new FlowableFromArray(sources), Functions.identity(), maxConcurrency, prefetch, ErrorMode.IMMEDIATE));
    }

    /**
     * Concatenates the Iterable sequence of Publishers into a single sequence by subscribing to each Publisher,
     * one after the other, one at a time and delays any errors till the all inner Publishers terminate.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. Both the outer and inner {@code Publisher}
     *  sources are expected to honor backpressure as well. If the outer violates this, a
     *  {@code MissingBackpressureException} is signalled. If any of the inner {@code Publisher}s violates
     *  this, it <em>may</em> throw an {@code IllegalStateException} when an inner {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concatDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources the Iterable sequence of Publishers
     * @return the new Publisher with the concatenating behavior
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> concatDelayError(Iterable<? extends Publisher<? extends T>> sources) {
        ObjectHelper.requireNonNull(sources, "sources is null");
        return fromIterable(sources).concatMapDelayError((Function)Functions.identity());
    }

    /**
     * Concatenates the Publisher sequence of Publishers into a single sequence by subscribing to each inner Publisher,
     * one after the other, one at a time and delays any errors till the all inner and the outer Publishers terminate.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>{@code concatDelayError} fully supports backpressure.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concatDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources the Publisher sequence of Publishers
     * @return the new Publisher with the concatenating behavior
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> concatDelayError(Publisher<? extends Publisher<? extends T>> sources) {
        return concatDelayError(sources, bufferSize(), true);
    }

    /**
     * Concatenates the Publisher sequence of Publishers into a single sequence by subscribing to each inner Publisher,
     * one after the other, one at a time and delays any errors till the all inner and the outer Publishers terminate.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>{@code concatDelayError} fully supports backpressure.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concatDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources the Publisher sequence of Publishers
     * @param prefetch the number of elements to prefetch from the outer Publisher
     * @param tillTheEnd if true exceptions from the outer and all inner Publishers are delayed to the end
     *                   if false, exception from the outer Publisher is delayed till the current Publisher terminates
     * @return the new Publisher with the concatenating behavior
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> concatDelayError(Publisher<? extends Publisher<? extends T>> sources, int prefetch, boolean tillTheEnd) {
        return fromPublisher(sources).concatMapDelayError((Function)Functions.identity(), prefetch, tillTheEnd);
    }

    /**
     * Concatenates a Publisher sequence of Publishers eagerly into a single stream of values.
     * <p>
     * Eager concatenation means that once a subscriber subscribes, this operator subscribes to all of the
     * emitted source Publishers as they are observed. The operator buffers the values emitted by these
     * Publishers and then drains them in order, each one after the previous one completes.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>Backpressure is honored towards the downstream and both the outer and inner Publishers are
     *  expected to support backpressure. Violating this assumption, the operator will
     *  signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This method does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <T> the value type
     * @param sources a sequence of Publishers that need to be eagerly concatenated
     * @return the new Publisher instance with the specified concatenation behavior
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> concatEager(Publisher<? extends Publisher<? extends T>> sources) {
        return concatEager(sources, bufferSize(), bufferSize());
    }

    /**
     * Concatenates a Publisher sequence of Publishers eagerly into a single stream of values.
     * <p>
     * Eager concatenation means that once a subscriber subscribes, this operator subscribes to all of the
     * emitted source Publishers as they are observed. The operator buffers the values emitted by these
     * Publishers and then drains them in order, each one after the previous one completes.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>Backpressure is honored towards the downstream and both the outer and inner Publishers are
     *  expected to support backpressure. Violating this assumption, the operator will
     *  signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This method does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <T> the value type
     * @param sources a sequence of Publishers that need to be eagerly concatenated
     * @param maxConcurrency the maximum number of concurrently running inner Publishers; Integer.MAX_VALUE
     *                       is interpreted as all inner Publishers can be active at the same time
     * @param prefetch the number of elements to prefetch from each inner Publisher source
     * @return the new Publisher instance with the specified concatenation behavior
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static <T> Flowable<T> concatEager(Publisher<? extends Publisher<? extends T>> sources, int maxConcurrency, int prefetch) {
        ObjectHelper.requireNonNull(sources, "sources is null");
        ObjectHelper.verifyPositive(maxConcurrency, "maxConcurrency");
        ObjectHelper.verifyPositive(prefetch, "prefetch");
        return RxJavaPlugins.onAssembly(new FlowableConcatMapEagerPublisher(sources, Functions.identity(), maxConcurrency, prefetch, ErrorMode.IMMEDIATE));
    }

    /**
     * Concatenates a sequence of Publishers eagerly into a single stream of values.
     * <p>
     * Eager concatenation means that once a subscriber subscribes, this operator subscribes to all of the
     * source Publishers. The operator buffers the values emitted by these Publishers and then drains them
     * in order, each one after the previous one completes.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>Backpressure is honored towards the downstream and the inner Publishers are
     *  expected to support backpressure. Violating this assumption, the operator will
     *  signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This method does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <T> the value type
     * @param sources a sequence of Publishers that need to be eagerly concatenated
     * @return the new Publisher instance with the specified concatenation behavior
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> concatEager(Iterable<? extends Publisher<? extends T>> sources) {
        return concatEager(sources, bufferSize(), bufferSize());
    }

    /**
     * Concatenates a sequence of Publishers eagerly into a single stream of values.
     * <p>
     * Eager concatenation means that once a subscriber subscribes, this operator subscribes to all of the
     * source Publishers. The operator buffers the values emitted by these Publishers and then drains them
     * in order, each one after the previous one completes.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>Backpressure is honored towards the downstream and both the outer and inner Publishers are
     *  expected to support backpressure. Violating this assumption, the operator will
     *  signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This method does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <T> the value type
     * @param sources a sequence of Publishers that need to be eagerly concatenated
     * @param maxConcurrency the maximum number of concurrently running inner Publishers; Integer.MAX_VALUE
     *                       is interpreted as all inner Publishers can be active at the same time
     * @param prefetch the number of elements to prefetch from each inner Publisher source
     * @return the new Publisher instance with the specified concatenation behavior
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static <T> Flowable<T> concatEager(Iterable<? extends Publisher<? extends T>> sources, int maxConcurrency, int prefetch) {
        ObjectHelper.requireNonNull(sources, "sources is null");
        ObjectHelper.verifyPositive(maxConcurrency, "maxConcurrency");
        ObjectHelper.verifyPositive(prefetch, "prefetch");
        return RxJavaPlugins.onAssembly(new FlowableConcatMapEager(new FlowableFromIterable(sources), Functions.identity(), maxConcurrency, prefetch, ErrorMode.IMMEDIATE));
    }

    /**
     * Provides an API (via a cold Flowable) that bridges the reactive world with the callback-style,
     * generally non-backpressured world.
     * <p>
     * Example:
     * <pre><code>
     * Flowable.&lt;Event&gt;create(emitter -&gt; {
     *     Callback listener = new Callback() {
     *         &#64;Override
     *         public void onEvent(Event e) {
     *             emitter.onNext(e);
     *             if (e.isLast()) {
     *                 emitter.onComplete();
     *             }
     *         }
     *
     *         &#64;Override
     *         public void onFailure(Exception e) {
     *             emitter.onError(e);
     *         }
     *     };
     *
     *     AutoCloseable c = api.someMethod(listener);
     *
     *     emitter.setCancellable(c::close);
     *
     * }, BackpressureStrategy.BUFFER);
     * </code></pre>
     * <p>
     * You should call the FlowableEmitter onNext, onError and onComplete methods in a serialized fashion. The
     * rest of its methods are thread-safe.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The backpressure behavior is determined by the {@code mode} parameter.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code create} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the element type
     * @param source the emitter that is called when a Subscriber subscribes to the returned {@code Flowable}
     * @param mode the backpressure mode to apply if the downstream Subscriber doesn't request (fast) enough
     * @return the new Flowable instance
     * @see FlowableOnSubscribe
     * @see BackpressureStrategy
     * @see Cancellable
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.SPECIAL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> create(FlowableOnSubscribe<T> source, BackpressureStrategy mode) {
        ObjectHelper.requireNonNull(source, "source is null");
        ObjectHelper.requireNonNull(mode, "mode is null");
        return RxJavaPlugins.onAssembly(new FlowableCreate<T>(source, mode));
    }

    /**
     * Returns a Flowable that calls a Publisher factory to create a Publisher for each new Subscriber
     * that subscribes. That is, for each subscriber, the actual Publisher that subscriber observes is
     * determined by the factory function.
     * <p>
     * <img width="640" height="340" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/defer.png" alt="">
     * <p>
     * The defer Subscriber allows you to defer or delay emitting items from a Publisher until such time as an
     * Subscriber subscribes to the Publisher. This allows a {@link Subscriber} to easily obtain updates or a
     * refreshed version of the sequence.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator itself doesn't interfere with backpressure which is determined by the {@code Publisher}
     *  returned by the {@code supplier}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code defer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param supplier
     *            the Publisher factory function to invoke for each {@link Subscriber} that subscribes to the
     *            resulting Publisher
     * @param <T>
     *            the type of the items emitted by the Publisher
     * @return a Flowable whose {@link Subscriber}s' subscriptions trigger an invocation of the given
     *         Publisher factory function
     * @see <a href="http://reactivex.io/documentation/operators/defer.html">ReactiveX operators documentation: Defer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> defer(Callable<? extends Publisher<? extends T>> supplier) {
        ObjectHelper.requireNonNull(supplier, "supplier is null");
        return RxJavaPlugins.onAssembly(new FlowableDefer<T>(supplier));
    }

    /**
     * Returns a Flowable that emits no items to the {@link Subscriber} and immediately invokes its
     * {@link Subscriber#onComplete onComplete} method.
     * <p>
     * <img width="640" height="190" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/empty.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This source doesn't produce any elements and effectively ignores downstream backpressure.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code empty} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T>
     *            the type of the items (ostensibly) emitted by the Publisher
     * @return a Flowable that emits no items to the {@link Subscriber} but immediately invokes the
     *         {@link Subscriber}'s {@link Subscriber#onComplete() onComplete} method
     * @see <a href="http://reactivex.io/documentation/operators/empty-never-throw.html">ReactiveX operators documentation: Empty</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    @SuppressWarnings("unchecked")
    public static <T> Flowable<T> empty() {
        return RxJavaPlugins.onAssembly((Flowable<T>) FlowableEmpty.INSTANCE);
    }

    /**
     * Returns a Flowable that invokes a {@link Subscriber}'s {@link Subscriber#onError onError} method when the
     * Subscriber subscribes to it.
     * <p>
     * <img width="640" height="190" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/error.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This source doesn't produce any elements and effectively ignores downstream backpressure.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code error} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param supplier
     *            a Callable factory to return a Throwable for each individual Subscriber
     * @param <T>
     *            the type of the items (ostensibly) emitted by the Publisher
     * @return a Flowable that invokes the {@link Subscriber}'s {@link Subscriber#onError onError} method when
     *         the Subscriber subscribes to it
     * @see <a href="http://reactivex.io/documentation/operators/empty-never-throw.html">ReactiveX operators documentation: Throw</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> error(Callable<? extends Throwable> supplier) {
        ObjectHelper.requireNonNull(supplier, "errorSupplier is null");
        return RxJavaPlugins.onAssembly(new FlowableError<T>(supplier));
    }

    /**
     * Returns a Flowable that invokes a {@link Subscriber}'s {@link Subscriber#onError onError} method when the
     * Subscriber subscribes to it.
     * <p>
     * <img width="640" height="190" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/error.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This source doesn't produce any elements and effectively ignores downstream backpressure.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code error} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param throwable
     *            the particular Throwable to pass to {@link Subscriber#onError onError}
     * @param <T>
     *            the type of the items (ostensibly) emitted by the Publisher
     * @return a Flowable that invokes the {@link Subscriber}'s {@link Subscriber#onError onError} method when
     *         the Subscriber subscribes to it
     * @see <a href="http://reactivex.io/documentation/operators/empty-never-throw.html">ReactiveX operators documentation: Throw</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> error(final Throwable throwable) {
        ObjectHelper.requireNonNull(throwable, "throwable is null");
        return error(Functions.justCallable(throwable));
    }

    /**
     * Converts an Array into a Publisher that emits the items in the Array.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/from.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and iterates the given {@code array}
     *  on demand (i.e., when requested).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code fromArray} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param items
     *            the array of elements
     * @param <T>
     *            the type of items in the Array and the type of items to be emitted by the resulting Publisher
     * @return a Flowable that emits each item in the source Array
     * @see <a href="http://reactivex.io/documentation/operators/from.html">ReactiveX operators documentation: From</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> fromArray(T... items) {
        ObjectHelper.requireNonNull(items, "items is null");
        if (items.length == 0) {
            return empty();
        }
        if (items.length == 1) {
            return just(items[0]);
        }
        return RxJavaPlugins.onAssembly(new FlowableFromArray<T>(items));
    }

    /**
     * Returns a Flowable that, when a Subscriber subscribes to it, invokes a function you specify and then
     * emits the value returned from that function.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/fromCallable.png" alt="">
     * <p>
     * This allows you to defer the execution of the function you specify until a Subscriber subscribes to the
     * Publisher. That is to say, it makes the function "lazy."
     * <dl>
     *   <dt><b>Backpressure:</b></dt>
     *   <dd>The operator honors backpressure from downstream.</dd>
     *   <dt><b>Scheduler:</b></dt>
     *   <dd>{@code fromCallable} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param supplier
     *         a function, the execution of which should be deferred; {@code fromCallable} will invoke this
     *         function only when a Subscriber subscribes to the Publisher that {@code fromCallable} returns
     * @param <T>
     *         the type of the item emitted by the Publisher
     * @return a Flowable whose {@link Subscriber}s' subscriptions trigger an invocation of the given function
     * @see #defer(Callable)
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> fromCallable(Callable<? extends T> supplier) {
        ObjectHelper.requireNonNull(supplier, "supplier is null");
        return RxJavaPlugins.onAssembly(new FlowableFromCallable<T>(supplier));
    }

    /**
     * Converts a {@link Future} into a Publisher.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/from.Future.png" alt="">
     * <p>
     * You can convert any object that supports the {@link Future} interface into a Publisher that emits the
     * return value of the {@link Future#get} method of that object, by passing the object into the {@code from}
     * method.
     * <p>
     * <em>Important note:</em> This Publisher is blocking on the thread it gets subscribed on; you cannot cancel it.
     * <p>
     * Unlike 1.x, cancelling the Flowable won't cancel the future. If necessary, one can use composition to achieve the
     * cancellation effect: {@code futurePublisher.doOnCancel(() -> future.cancel(true));}.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code fromFuture} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param future
     *            the source {@link Future}
     * @param <T>
     *            the type of object that the {@link Future} returns, and also the type of item to be emitted by
     *            the resulting Publisher
     * @return a Flowable that emits the item from the source {@link Future}
     * @see <a href="http://reactivex.io/documentation/operators/from.html">ReactiveX operators documentation: From</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> fromFuture(Future<? extends T> future) {
        ObjectHelper.requireNonNull(future, "future is null");
        return RxJavaPlugins.onAssembly(new FlowableFromFuture<T>(future, 0L, null));
    }

    /**
     * Converts a {@link Future} into a Publisher, with a timeout on the Future.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/from.Future.png" alt="">
     * <p>
     * You can convert any object that supports the {@link Future} interface into a Publisher that emits the
     * return value of the {@link Future#get} method of that object, by passing the object into the {@code fromFuture}
     * method.
     * <p>
     * Unlike 1.x, cancelling the Flowable won't cancel the future. If necessary, one can use composition to achieve the
     * cancellation effect: {@code futurePublisher.doOnCancel(() -> future.cancel(true));}.
     * <p>
     * <em>Important note:</em> This Publisher is blocking on the thread it gets subscribed on; you cannot cancel it.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code fromFuture} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param future
     *            the source {@link Future}
     * @param timeout
     *            the maximum time to wait before calling {@code get}
     * @param unit
     *            the {@link TimeUnit} of the {@code timeout} argument
     * @param <T>
     *            the type of object that the {@link Future} returns, and also the type of item to be emitted by
     *            the resulting Publisher
     * @return a Flowable that emits the item from the source {@link Future}
     * @see <a href="http://reactivex.io/documentation/operators/from.html">ReactiveX operators documentation: From</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> fromFuture(Future<? extends T> future, long timeout, TimeUnit unit) {
        ObjectHelper.requireNonNull(future, "future is null");
        ObjectHelper.requireNonNull(unit, "unit is null");
        return RxJavaPlugins.onAssembly(new FlowableFromFuture<T>(future, timeout, unit));
    }

    /**
     * Converts a {@link Future} into a Publisher, with a timeout on the Future.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/from.Future.png" alt="">
     * <p>
     * You can convert any object that supports the {@link Future} interface into a Publisher that emits the
     * return value of the {@link Future#get} method of that object, by passing the object into the {@code from}
     * method.
     * <p>
     * Unlike 1.x, cancelling the Flowable won't cancel the future. If necessary, one can use composition to achieve the
     * cancellation effect: {@code futurePublisher.doOnCancel(() -> future.cancel(true));}.
     * <p>
     * <em>Important note:</em> This Publisher is blocking; you cannot cancel it.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code fromFuture} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param future
     *            the source {@link Future}
     * @param timeout
     *            the maximum time to wait before calling {@code get}
     * @param unit
     *            the {@link TimeUnit} of the {@code timeout} argument
     * @param scheduler
     *            the {@link Scheduler} to wait for the Future on. Use a Scheduler such as
     *            {@link Schedulers#io()} that can block and wait on the Future
     * @param <T>
     *            the type of object that the {@link Future} returns, and also the type of item to be emitted by
     *            the resulting Publisher
     * @return a Flowable that emits the item from the source {@link Future}
     * @see <a href="http://reactivex.io/documentation/operators/from.html">ReactiveX operators documentation: From</a>
     */
    @SuppressWarnings({ "unchecked", "cast" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public static <T> Flowable<T> fromFuture(Future<? extends T> future, long timeout, TimeUnit unit, Scheduler scheduler) {
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return fromFuture((Future<T>)future, timeout, unit).subscribeOn(scheduler);
    }

    /**
     * Converts a {@link Future}, operating on a specified {@link Scheduler}, into a Publisher.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/from.Future.s.png" alt="">
     * <p>
     * You can convert any object that supports the {@link Future} interface into a Publisher that emits the
     * return value of the {@link Future#get} method of that object, by passing the object into the {@code from}
     * method.
     * <p>
     * Unlike 1.x, cancelling the Flowable won't cancel the future. If necessary, one can use composition to achieve the
     * cancellation effect: {@code futurePublisher.doOnCancel(() -> future.cancel(true));}.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param future
     *            the source {@link Future}
     * @param scheduler
     *            the {@link Scheduler} to wait for the Future on. Use a Scheduler such as
     *            {@link Schedulers#io()} that can block and wait on the Future
     * @param <T>
     *            the type of object that the {@link Future} returns, and also the type of item to be emitted by
     *            the resulting Publisher
     * @return a Flowable that emits the item from the source {@link Future}
     * @see <a href="http://reactivex.io/documentation/operators/from.html">ReactiveX operators documentation: From</a>
     */
    @SuppressWarnings({ "cast", "unchecked" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public static <T> Flowable<T> fromFuture(Future<? extends T> future, Scheduler scheduler) {
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return fromFuture((Future<T>)future).subscribeOn(scheduler);
    }

    /**
     * Converts an {@link Iterable} sequence into a Publisher that emits the items in the sequence.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/from.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and iterates the given {@code iterable}
     *  on demand (i.e., when requested).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code fromIterable} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param source
     *            the source {@link Iterable} sequence
     * @param <T>
     *            the type of items in the {@link Iterable} sequence and the type of items to be emitted by the
     *            resulting Publisher
     * @return a Flowable that emits each item in the source {@link Iterable} sequence
     * @see <a href="http://reactivex.io/documentation/operators/from.html">ReactiveX operators documentation: From</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> fromIterable(Iterable<? extends T> source) {
        ObjectHelper.requireNonNull(source, "source is null");
        return RxJavaPlugins.onAssembly(new FlowableFromIterable<T>(source));
    }

    /**
     * Converts an arbitrary Reactive-Streams Publisher into a Flowable if not already a
     * Flowable.
     * <p>
     * The {@link Publisher} must follow the
     * <a href="https://github.com/reactive-streams/reactive-streams-jvm#reactive-streams">Reactive-Streams specification</a>.
     * Violating the specification may result in undefined behavior.
     * <p>
     * If possible, use {@link #create(FlowableOnSubscribe, BackpressureStrategy)} to create a
     * source-like {@code Flowable} instead.
     * <p>
     * Note that even though {@link Publisher} appears to be a functional interface, it
     * is not recommended to implement it through a lambda as the specification requires
     * state management that is not achievable with a stateless lambda.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator is a pass-through for backpressure and its behavior is determined by the
     *  backpressure behavior of the wrapped publisher.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code fromPublisher} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <T> the value type of the flow
     * @param source the Publisher to convert
     * @return the new Flowable instance
     * @throws NullPointerException if publisher is null
     * @see #create(FlowableOnSubscribe, BackpressureStrategy)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    @SuppressWarnings("unchecked")
    public static <T> Flowable<T> fromPublisher(final Publisher<? extends T> source) {
        if (source instanceof Flowable) {
            return RxJavaPlugins.onAssembly((Flowable<T>)source);
        }
        ObjectHelper.requireNonNull(source, "publisher is null");

        return RxJavaPlugins.onAssembly(new FlowableFromPublisher<T>(source));
    }

    /**
     * Returns a cold, synchronous, stateless and backpressure-aware generator of values.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code generate} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the generated value type
     * @param generator the Consumer called whenever a particular downstream Subscriber has
     * requested a value. The callback then should call {@code onNext}, {@code onError} or
     * {@code onComplete} to signal a value or a terminal event. Signalling multiple {@code onNext}
     * in a call will make the operator signal {@code IllegalStateException}.
     * @return the new Flowable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> generate(final Consumer<Emitter<T>> generator) {
        ObjectHelper.requireNonNull(generator, "generator is null");
        return generate(Functions.nullSupplier(),
                FlowableInternalHelper.<T, Object>simpleGenerator(generator),
                Functions.emptyConsumer());
    }

    /**
     * Returns a cold, synchronous, stateful and backpressure-aware generator of values.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code generate} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <S> the type of the per-Subscriber state
     * @param <T> the generated value type
     * @param initialState the Callable to generate the initial state for each Subscriber
     * @param generator the Consumer called with the current state whenever a particular downstream Subscriber has
     * requested a value. The callback then should call {@code onNext}, {@code onError} or
     * {@code onComplete} to signal a value or a terminal event. Signalling multiple {@code onNext}
     * in a call will make the operator signal {@code IllegalStateException}.
     * @return the new Flowable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T, S> Flowable<T> generate(Callable<S> initialState, final BiConsumer<S, Emitter<T>> generator) {
        ObjectHelper.requireNonNull(generator, "generator is null");
        return generate(initialState, FlowableInternalHelper.<T, S>simpleBiGenerator(generator),
                Functions.emptyConsumer());
    }

    /**
     * Returns a cold, synchronous, stateful and backpressure-aware generator of values.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code generate} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <S> the type of the per-Subscriber state
     * @param <T> the generated value type
     * @param initialState the Callable to generate the initial state for each Subscriber
     * @param generator the Consumer called with the current state whenever a particular downstream Subscriber has
     * requested a value. The callback then should call {@code onNext}, {@code onError} or
     * {@code onComplete} to signal a value or a terminal event. Signalling multiple {@code onNext}
     * in a call will make the operator signal {@code IllegalStateException}.
     * @param disposeState the Consumer that is called with the current state when the generator
     * terminates the sequence or it gets cancelled
     * @return the new Flowable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T, S> Flowable<T> generate(Callable<S> initialState, final BiConsumer<S, Emitter<T>> generator,
            Consumer<? super S> disposeState) {
        ObjectHelper.requireNonNull(generator, "generator is null");
        return generate(initialState, FlowableInternalHelper.<T, S>simpleBiGenerator(generator), disposeState);
    }

    /**
     * Returns a cold, synchronous, stateful and backpressure-aware generator of values.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code generate} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <S> the type of the per-Subscriber state
     * @param <T> the generated value type
     * @param initialState the Callable to generate the initial state for each Subscriber
     * @param generator the Function called with the current state whenever a particular downstream Subscriber has
     * requested a value. The callback then should call {@code onNext}, {@code onError} or
     * {@code onComplete} to signal a value or a terminal event and should return a (new) state for
     * the next invocation. Signalling multiple {@code onNext}
     * in a call will make the operator signal {@code IllegalStateException}.
     * @return the new Flowable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T, S> Flowable<T> generate(Callable<S> initialState, BiFunction<S, Emitter<T>, S> generator) {
        return generate(initialState, generator, Functions.emptyConsumer());
    }

    /**
     * Returns a cold, synchronous, stateful and backpressure-aware generator of values.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code generate} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <S> the type of the per-Subscriber state
     * @param <T> the generated value type
     * @param initialState the Callable to generate the initial state for each Subscriber
     * @param generator the Function called with the current state whenever a particular downstream Subscriber has
     * requested a value. The callback then should call {@code onNext}, {@code onError} or
     * {@code onComplete} to signal a value or a terminal event and should return a (new) state for
     * the next invocation. Signalling multiple {@code onNext}
     * in a call will make the operator signal {@code IllegalStateException}.
     * @param disposeState the Consumer that is called with the current state when the generator
     * terminates the sequence or it gets cancelled
     * @return the new Flowable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T, S> Flowable<T> generate(Callable<S> initialState, BiFunction<S, Emitter<T>, S> generator, Consumer<? super S> disposeState) {
        ObjectHelper.requireNonNull(initialState, "initialState is null");
        ObjectHelper.requireNonNull(generator, "generator is null");
        ObjectHelper.requireNonNull(disposeState, "disposeState is null");
        return RxJavaPlugins.onAssembly(new FlowableGenerate<T, S>(initialState, generator, disposeState));
    }

    /**
     * Returns a Flowable that emits a {@code 0L} after the {@code initialDelay} and ever increasing numbers
     * after each {@code period} of time thereafter.
     * <p>
     * <img width="640" height="200" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timer.p.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator generates values based on time and ignores downstream backpressure which
     *  may lead to {@code MissingBackpressureException} at some point in the chain.
     *  Consumers should consider applying one of the {@code onBackpressureXXX} operators as well.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code interval} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param initialDelay
     *            the initial delay time to wait before emitting the first value of 0L
     * @param period
     *            the period of time between emissions of the subsequent numbers
     * @param unit
     *            the time unit for both {@code initialDelay} and {@code period}
     * @return a Flowable that emits a 0L after the {@code initialDelay} and ever increasing numbers after
     *         each {@code period} of time thereafter
     * @see <a href="http://reactivex.io/documentation/operators/interval.html">ReactiveX operators documentation: Interval</a>
     * @since 1.0.12
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public static Flowable<Long> interval(long initialDelay, long period, TimeUnit unit) {
        return interval(initialDelay, period, unit, Schedulers.computation());
    }

    /**
     * Returns a Flowable that emits a {@code 0L} after the {@code initialDelay} and ever increasing numbers
     * after each {@code period} of time thereafter, on a specified {@link Scheduler}.
     * <p>
     * <img width="640" height="200" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timer.ps.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator generates values based on time and ignores downstream backpressure which
     *  may lead to {@code MissingBackpressureException} at some point in the chain.
     *  Consumers should consider applying one of the {@code onBackpressureXXX} operators as well.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param initialDelay
     *            the initial delay time to wait before emitting the first value of 0L
     * @param period
     *            the period of time between emissions of the subsequent numbers
     * @param unit
     *            the time unit for both {@code initialDelay} and {@code period}
     * @param scheduler
     *            the Scheduler on which the waiting happens and items are emitted
     * @return a Flowable that emits a 0L after the {@code initialDelay} and ever increasing numbers after
     *         each {@code period} of time thereafter, while running on the given Scheduler
     * @see <a href="http://reactivex.io/documentation/operators/interval.html">ReactiveX operators documentation: Interval</a>
     * @since 1.0.12
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public static Flowable<Long> interval(long initialDelay, long period, TimeUnit unit, Scheduler scheduler) {
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return RxJavaPlugins.onAssembly(new FlowableInterval(Math.max(0L, initialDelay), Math.max(0L, period), unit, scheduler));
    }

    /**
     * Returns a Flowable that emits a sequential number every specified interval of time.
     * <p>
     * <img width="640" height="195" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/interval.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator signals a {@code MissingBackpressureException} if the downstream
     *  is not ready to receive the next value.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code interval} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param period
     *            the period size in time units (see below)
     * @param unit
     *            time units to use for the interval size
     * @return a Flowable that emits a sequential number each time interval
     * @see <a href="http://reactivex.io/documentation/operators/interval.html">ReactiveX operators documentation: Interval</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public static Flowable<Long> interval(long period, TimeUnit unit) {
        return interval(period, period, unit, Schedulers.computation());
    }

    /**
     * Returns a Flowable that emits a sequential number every specified interval of time, on a
     * specified Scheduler.
     * <p>
     * <img width="640" height="200" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/interval.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator generates values based on time and ignores downstream backpressure which
     *  may lead to {@code MissingBackpressureException} at some point in the chain.
     *  Consumers should consider applying one of the {@code onBackpressureXXX} operators as well.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param period
     *            the period size in time units (see below)
     * @param unit
     *            time units to use for the interval size
     * @param scheduler
     *            the Scheduler to use for scheduling the items
     * @return a Flowable that emits a sequential number each time interval
     * @see <a href="http://reactivex.io/documentation/operators/interval.html">ReactiveX operators documentation: Interval</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public static Flowable<Long> interval(long period, TimeUnit unit, Scheduler scheduler) {
        return interval(period, period, unit, scheduler);
    }

    /**
     * Signals a range of long values, the first after some initial delay and the rest periodically after.
     * <p>
     * The sequence completes immediately after the last value (start + count - 1) has been reached.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator signals a {@code MissingBackpressureException} if the downstream can't keep up.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code intervalRange} by default operates on the {@link Schedulers#computation() computation} {@link Scheduler}.</dd>
     * </dl>
     * @param start that start value of the range
     * @param count the number of values to emit in total, if zero, the operator emits an onComplete after the initial delay.
     * @param initialDelay the initial delay before signalling the first value (the start)
     * @param period the period between subsequent values
     * @param unit the unit of measure of the initialDelay and period amounts
     * @return the new Flowable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public static Flowable<Long> intervalRange(long start, long count, long initialDelay, long period, TimeUnit unit) {
        return intervalRange(start, count, initialDelay, period, unit, Schedulers.computation());
    }

    /**
     * Signals a range of long values, the first after some initial delay and the rest periodically after.
     * <p>
     * The sequence completes immediately after the last value (start + count - 1) has been reached.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator signals a {@code MissingBackpressureException} if the downstream can't keep up.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>you provide the {@link Scheduler}.</dd>
     * </dl>
     * @param start that start value of the range
     * @param count the number of values to emit in total, if zero, the operator emits an onComplete after the initial delay.
     * @param initialDelay the initial delay before signalling the first value (the start)
     * @param period the period between subsequent values
     * @param unit the unit of measure of the initialDelay and period amounts
     * @param scheduler the target scheduler where the values and terminal signals will be emitted
     * @return the new Flowable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public static Flowable<Long> intervalRange(long start, long count, long initialDelay, long period, TimeUnit unit, Scheduler scheduler) {
        if (count < 0L) {
            throw new IllegalArgumentException("count >= 0 required but it was " + count);
        }
        if (count == 0L) {
            return Flowable.<Long>empty().delay(initialDelay, unit, scheduler);
        }

        long end = start + (count - 1);
        if (start > 0 && end < 0) {
            throw new IllegalArgumentException("Overflow! start + count is bigger than Long.MAX_VALUE");
        }
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");

        return RxJavaPlugins.onAssembly(new FlowableIntervalRange(start, end, Math.max(0L, initialDelay), Math.max(0L, period), unit, scheduler));
    }

    /**
     * Returns a Flowable that emits a single item and then completes.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/just.png" alt="">
     * <p>
     * To convert any object into a Publisher that emits that object, pass that object into the {@code just}
     * method.
     * <p>
     * This is similar to the {@link #fromArray(java.lang.Object[])} method, except that {@code from} will convert
     * an {@link Iterable} object into a Publisher that emits each of the items in the Iterable, one at a
     * time, while the {@code just} method converts an Iterable into a Publisher that emits the entire
     * Iterable as a single item.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code just} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param item
     *            the item to emit
     * @param <T>
     *            the type of that item
     * @return a Flowable that emits {@code value} as a single item and then completes
     * @see <a href="http://reactivex.io/documentation/operators/just.html">ReactiveX operators documentation: Just</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> just(T item) {
        ObjectHelper.requireNonNull(item, "item is null");
        return RxJavaPlugins.onAssembly(new FlowableJust<T>(item));
    }

    /**
     * Converts two items into a Publisher that emits those items.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/just.m.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and signals each value on-demand (i.e., when requested).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code just} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param item1
     *            first item
     * @param item2
     *            second item
     * @param <T>
     *            the type of these items
     * @return a Flowable that emits each item
     * @see <a href="http://reactivex.io/documentation/operators/just.html">ReactiveX operators documentation: Just</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> just(T item1, T item2) {
        ObjectHelper.requireNonNull(item1, "The first item is null");
        ObjectHelper.requireNonNull(item2, "The second item is null");

        return fromArray(item1, item2);
    }

    /**
     * Converts three items into a Publisher that emits those items.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/just.m.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and signals each value on-demand (i.e., when requested).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code just} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param item1
     *            first item
     * @param item2
     *            second item
     * @param item3
     *            third item
     * @param <T>
     *            the type of these items
     * @return a Flowable that emits each item
     * @see <a href="http://reactivex.io/documentation/operators/just.html">ReactiveX operators documentation: Just</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> just(T item1, T item2, T item3) {
        ObjectHelper.requireNonNull(item1, "The first item is null");
        ObjectHelper.requireNonNull(item2, "The second item is null");
        ObjectHelper.requireNonNull(item3, "The third item is null");

        return fromArray(item1, item2, item3);
    }

    /**
     * Converts four items into a Publisher that emits those items.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/just.m.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and signals each value on-demand (i.e., when requested).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code just} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param item1
     *            first item
     * @param item2
     *            second item
     * @param item3
     *            third item
     * @param item4
     *            fourth item
     * @param <T>
     *            the type of these items
     * @return a Flowable that emits each item
     * @see <a href="http://reactivex.io/documentation/operators/just.html">ReactiveX operators documentation: Just</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> just(T item1, T item2, T item3, T item4) {
        ObjectHelper.requireNonNull(item1, "The first item is null");
        ObjectHelper.requireNonNull(item2, "The second item is null");
        ObjectHelper.requireNonNull(item3, "The third item is null");
        ObjectHelper.requireNonNull(item4, "The fourth item is null");

        return fromArray(item1, item2, item3, item4);
    }

    /**
     * Converts five items into a Publisher that emits those items.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/just.m.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and signals each value on-demand (i.e., when requested).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code just} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param item1
     *            first item
     * @param item2
     *            second item
     * @param item3
     *            third item
     * @param item4
     *            fourth item
     * @param item5
     *            fifth item
     * @param <T>
     *            the type of these items
     * @return a Flowable that emits each item
     * @see <a href="http://reactivex.io/documentation/operators/just.html">ReactiveX operators documentation: Just</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> just(T item1, T item2, T item3, T item4, T item5) {
        ObjectHelper.requireNonNull(item1, "The first item is null");
        ObjectHelper.requireNonNull(item2, "The second item is null");
        ObjectHelper.requireNonNull(item3, "The third item is null");
        ObjectHelper.requireNonNull(item4, "The fourth item is null");
        ObjectHelper.requireNonNull(item5, "The fifth item is null");

        return fromArray(item1, item2, item3, item4, item5);
    }

    /**
     * Converts six items into a Publisher that emits those items.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/just.m.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and signals each value on-demand (i.e., when requested).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code just} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param item1
     *            first item
     * @param item2
     *            second item
     * @param item3
     *            third item
     * @param item4
     *            fourth item
     * @param item5
     *            fifth item
     * @param item6
     *            sixth item
     * @param <T>
     *            the type of these items
     * @return a Flowable that emits each item
     * @see <a href="http://reactivex.io/documentation/operators/just.html">ReactiveX operators documentation: Just</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> just(T item1, T item2, T item3, T item4, T item5, T item6) {
        ObjectHelper.requireNonNull(item1, "The first item is null");
        ObjectHelper.requireNonNull(item2, "The second item is null");
        ObjectHelper.requireNonNull(item3, "The third item is null");
        ObjectHelper.requireNonNull(item4, "The fourth item is null");
        ObjectHelper.requireNonNull(item5, "The fifth item is null");
        ObjectHelper.requireNonNull(item6, "The sixth item is null");

        return fromArray(item1, item2, item3, item4, item5, item6);
    }

    /**
     * Converts seven items into a Publisher that emits those items.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/just.m.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and signals each value on-demand (i.e., when requested).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code just} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param item1
     *            first item
     * @param item2
     *            second item
     * @param item3
     *            third item
     * @param item4
     *            fourth item
     * @param item5
     *            fifth item
     * @param item6
     *            sixth item
     * @param item7
     *            seventh item
     * @param <T>
     *            the type of these items
     * @return a Flowable that emits each item
     * @see <a href="http://reactivex.io/documentation/operators/just.html">ReactiveX operators documentation: Just</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> just(T item1, T item2, T item3, T item4, T item5, T item6, T item7) {
        ObjectHelper.requireNonNull(item1, "The first item is null");
        ObjectHelper.requireNonNull(item2, "The second item is null");
        ObjectHelper.requireNonNull(item3, "The third item is null");
        ObjectHelper.requireNonNull(item4, "The fourth item is null");
        ObjectHelper.requireNonNull(item5, "The fifth item is null");
        ObjectHelper.requireNonNull(item6, "The sixth item is null");
        ObjectHelper.requireNonNull(item7, "The seventh item is null");

        return fromArray(item1, item2, item3, item4, item5, item6, item7);
    }

    /**
     * Converts eight items into a Publisher that emits those items.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/just.m.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and signals each value on-demand (i.e., when requested).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code just} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param item1
     *            first item
     * @param item2
     *            second item
     * @param item3
     *            third item
     * @param item4
     *            fourth item
     * @param item5
     *            fifth item
     * @param item6
     *            sixth item
     * @param item7
     *            seventh item
     * @param item8
     *            eighth item
     * @param <T>
     *            the type of these items
     * @return a Flowable that emits each item
     * @see <a href="http://reactivex.io/documentation/operators/just.html">ReactiveX operators documentation: Just</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> just(T item1, T item2, T item3, T item4, T item5, T item6, T item7, T item8) {
        ObjectHelper.requireNonNull(item1, "The first item is null");
        ObjectHelper.requireNonNull(item2, "The second item is null");
        ObjectHelper.requireNonNull(item3, "The third item is null");
        ObjectHelper.requireNonNull(item4, "The fourth item is null");
        ObjectHelper.requireNonNull(item5, "The fifth item is null");
        ObjectHelper.requireNonNull(item6, "The sixth item is null");
        ObjectHelper.requireNonNull(item7, "The seventh item is null");
        ObjectHelper.requireNonNull(item8, "The eighth item is null");

        return fromArray(item1, item2, item3, item4, item5, item6, item7, item8);
    }

    /**
     * Converts nine items into a Publisher that emits those items.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/just.m.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and signals each value on-demand (i.e., when requested).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code just} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param item1
     *            first item
     * @param item2
     *            second item
     * @param item3
     *            third item
     * @param item4
     *            fourth item
     * @param item5
     *            fifth item
     * @param item6
     *            sixth item
     * @param item7
     *            seventh item
     * @param item8
     *            eighth item
     * @param item9
     *            ninth item
     * @param <T>
     *            the type of these items
     * @return a Flowable that emits each item
     * @see <a href="http://reactivex.io/documentation/operators/just.html">ReactiveX operators documentation: Just</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> just(T item1, T item2, T item3, T item4, T item5, T item6, T item7, T item8, T item9) {
        ObjectHelper.requireNonNull(item1, "The first item is null");
        ObjectHelper.requireNonNull(item2, "The second item is null");
        ObjectHelper.requireNonNull(item3, "The third item is null");
        ObjectHelper.requireNonNull(item4, "The fourth item is null");
        ObjectHelper.requireNonNull(item5, "The fifth item is null");
        ObjectHelper.requireNonNull(item6, "The sixth item is null");
        ObjectHelper.requireNonNull(item7, "The seventh item is null");
        ObjectHelper.requireNonNull(item8, "The eighth item is null");
        ObjectHelper.requireNonNull(item9, "The ninth is null");

        return fromArray(item1, item2, item3, item4, item5, item6, item7, item8, item9);
    }

    /**
     * Converts ten items into a Publisher that emits those items.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/just.m.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and signals each value on-demand (i.e., when requested).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code just} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param item1
     *            first item
     * @param item2
     *            second item
     * @param item3
     *            third item
     * @param item4
     *            fourth item
     * @param item5
     *            fifth item
     * @param item6
     *            sixth item
     * @param item7
     *            seventh item
     * @param item8
     *            eighth item
     * @param item9
     *            ninth item
     * @param item10
     *            tenth item
     * @param <T>
     *            the type of these items
     * @return a Flowable that emits each item
     * @see <a href="http://reactivex.io/documentation/operators/just.html">ReactiveX operators documentation: Just</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> just(T item1, T item2, T item3, T item4, T item5, T item6, T item7, T item8, T item9, T item10) {
        ObjectHelper.requireNonNull(item1, "The first item is null");
        ObjectHelper.requireNonNull(item2, "The second item is null");
        ObjectHelper.requireNonNull(item3, "The third item is null");
        ObjectHelper.requireNonNull(item4, "The fourth item is null");
        ObjectHelper.requireNonNull(item5, "The fifth item is null");
        ObjectHelper.requireNonNull(item6, "The sixth item is null");
        ObjectHelper.requireNonNull(item7, "The seventh item is null");
        ObjectHelper.requireNonNull(item8, "The eighth item is null");
        ObjectHelper.requireNonNull(item9, "The ninth item is null");
        ObjectHelper.requireNonNull(item10, "The tenth item is null");

        return fromArray(item1, item2, item3, item4, item5, item6, item7, item8, item9, item10);
    }

    /**
     * Flattens an Iterable of Publishers into one Publisher, without any transformation, while limiting the
     * number of concurrent subscriptions to these Publishers.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/merge.png" alt="">
     * <p>
     * You can combine the items emitted by multiple Publishers so that they appear as a single Publisher, by
     * using the {@code merge} method.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code merge} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            the Iterable of Publishers
     * @param maxConcurrency
     *            the maximum number of Publishers that may be subscribed to concurrently
     * @param bufferSize
     *            the number of items to prefetch from each inner Publisher
     * @return a Flowable that emits items that are the result of flattening the items emitted by the
     *         Publishers in the Iterable
     * @throws IllegalArgumentException
     *             if {@code maxConcurrency} is less than or equal to 0
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> merge(Iterable<? extends Publisher<? extends T>> sources, int maxConcurrency, int bufferSize) {
        return fromIterable(sources).flatMap((Function)Functions.identity(), false, maxConcurrency, bufferSize);
    }

    /**
     * Flattens an Iterable of Publishers into one Publisher, without any transformation, while limiting the
     * number of concurrent subscriptions to these Publishers.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/merge.png" alt="">
     * <p>
     * You can combine the items emitted by multiple Publishers so that they appear as a single Publisher, by
     * using the {@code merge} method.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code mergeArray} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            the array of Publishers
     * @param maxConcurrency
     *            the maximum number of Publishers that may be subscribed to concurrently
     * @param bufferSize
     *            the number of items to prefetch from each inner Publisher
     * @return a Flowable that emits items that are the result of flattening the items emitted by the
     *         Publishers in the Iterable
     * @throws IllegalArgumentException
     *             if {@code maxConcurrency} is less than or equal to 0
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> mergeArray(int maxConcurrency, int bufferSize, Publisher<? extends T>... sources) {
        return fromArray(sources).flatMap((Function)Functions.identity(), false, maxConcurrency, bufferSize);
    }

    /**
     * Flattens an Iterable of Publishers into one Publisher, without any transformation.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/merge.png" alt="">
     * <p>
     * You can combine the items emitted by multiple Publishers so that they appear as a single Publisher, by
     * using the {@code merge} method.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code merge} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            the Iterable of Publishers
     * @return a Flowable that emits items that are the result of flattening the items emitted by the
     *         Publishers in the Iterable
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> merge(Iterable<? extends Publisher<? extends T>> sources) {
        return fromIterable(sources).flatMap((Function)Functions.identity());
    }

    /**
     * Flattens an Iterable of Publishers into one Publisher, without any transformation, while limiting the
     * number of concurrent subscriptions to these Publishers.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/merge.png" alt="">
     * <p>
     * You can combine the items emitted by multiple Publishers so that they appear as a single Publisher, by
     * using the {@code merge} method.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code merge} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            the Iterable of Publishers
     * @param maxConcurrency
     *            the maximum number of Publishers that may be subscribed to concurrently
     * @return a Flowable that emits items that are the result of flattening the items emitted by the
     *         Publishers in the Iterable
     * @throws IllegalArgumentException
     *             if {@code maxConcurrency} is less than or equal to 0
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> merge(Iterable<? extends Publisher<? extends T>> sources, int maxConcurrency) {
        return fromIterable(sources).flatMap((Function)Functions.identity(), maxConcurrency);
    }

    /**
     * Flattens a Publisher that emits Publishers into a single Publisher that emits the items emitted by
     * those Publishers, without any transformation.
     * <p>
     * <img width="640" height="370" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/merge.oo.png" alt="">
     * <p>
     * You can combine the items emitted by multiple Publishers so that they appear as a single Publisher, by
     * using the {@code merge} method.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The outer {@code Publisher} is consumed
     *  in unbounded mode (i.e., no backpressure is applied to it). The inner {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code merge} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            a Publisher that emits Publishers
     * @return a Flowable that emits items that are the result of flattening the Publishers emitted by the
     *         {@code source} Publisher
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> merge(Publisher<? extends Publisher<? extends T>> sources) {
        return merge(sources, bufferSize());
    }

    /**
     * Flattens a Publisher that emits Publishers into a single Publisher that emits the items emitted by
     * those Publishers, without any transformation, while limiting the maximum number of concurrent
     * subscriptions to these Publishers.
     * <p>
     * <img width="640" height="370" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/merge.oo.png" alt="">
     * <p>
     * You can combine the items emitted by multiple Publishers so that they appear as a single Publisher, by
     * using the {@code merge} method.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. Both the outer and inner {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code merge} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            a Publisher that emits Publishers
     * @param maxConcurrency
     *            the maximum number of Publishers that may be subscribed to concurrently
     * @return a Flowable that emits items that are the result of flattening the Publishers emitted by the
     *         {@code source} Publisher
     * @throws IllegalArgumentException
     *             if {@code maxConcurrency} is less than or equal to 0
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     * @since 1.1.0
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> merge(Publisher<? extends Publisher<? extends T>> sources, int maxConcurrency) {
        return fromPublisher(sources).flatMap((Function)Functions.identity(), maxConcurrency);
    }

    /**
     * Flattens an Array of Publishers into one Publisher, without any transformation.
     * <p>
     * <img width="640" height="370" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/merge.io.png" alt="">
     * <p>
     * You can combine items emitted by multiple Publishers so that they appear as a single Publisher, by
     * using the {@code merge} method.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code mergeArray} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            the array of Publishers
     * @return a Flowable that emits all of the items emitted by the Publishers in the Array
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> mergeArray(Publisher<? extends T>... sources) {
        return fromArray(sources).flatMap((Function)Functions.identity(), sources.length);
    }

    /**
     * Flattens two Publishers into a single Publisher, without any transformation.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/merge.png" alt="">
     * <p>
     * You can combine items emitted by multiple Publishers so that they appear as a single Publisher, by
     * using the {@code merge} method.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code merge} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param source1
     *            a Publisher to be merged
     * @param source2
     *            a Publisher to be merged
     * @return a Flowable that emits all of the items emitted by the source Publishers
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> merge(Publisher<? extends T> source1, Publisher<? extends T> source2) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        return fromArray(source1, source2).flatMap((Function)Functions.identity(), false, 2);
    }

    /**
     * Flattens three Publishers into a single Publisher, without any transformation.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/merge.png" alt="">
     * <p>
     * You can combine items emitted by multiple Publishers so that they appear as a single Publisher, by
     * using the {@code merge} method.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code merge} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param source1
     *            a Publisher to be merged
     * @param source2
     *            a Publisher to be merged
     * @param source3
     *            a Publisher to be merged
     * @return a Flowable that emits all of the items emitted by the source Publishers
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> merge(Publisher<? extends T> source1, Publisher<? extends T> source2, Publisher<? extends T> source3) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        return fromArray(source1, source2, source3).flatMap((Function)Functions.identity(), false, 3);
    }

    /**
     * Flattens four Publishers into a single Publisher, without any transformation.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/merge.png" alt="">
     * <p>
     * You can combine items emitted by multiple Publishers so that they appear as a single Publisher, by
     * using the {@code merge} method.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code merge} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param source1
     *            a Publisher to be merged
     * @param source2
     *            a Publisher to be merged
     * @param source3
     *            a Publisher to be merged
     * @param source4
     *            a Publisher to be merged
     * @return a Flowable that emits all of the items emitted by the source Publishers
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> merge(
            Publisher<? extends T> source1, Publisher<? extends T> source2,
            Publisher<? extends T> source3, Publisher<? extends T> source4) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        return fromArray(source1, source2, source3, source4).flatMap((Function)Functions.identity(), false, 4);
    }

    /**
     * Flattens an Iterable of Publishers into one Publisher, in a way that allows a Subscriber to receive all
     * successfully emitted items from each of the source Publishers without being interrupted by an error
     * notification from one of them.
     * <p>
     * This behaves like {@link #merge(Publisher)} except that if any of the merged Publishers notify of an
     * error via {@link Subscriber#onError onError}, {@code mergeDelayError} will refrain from propagating that
     * error notification until all of the merged Publishers have finished emitting items.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeDelayError.png" alt="">
     * <p>
     * Even if multiple merged Publishers send {@code onError} notifications, {@code mergeDelayError} will only
     * invoke the {@code onError} method of its Subscribers once.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. All inner {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code mergeDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            the Iterable of Publishers
     * @return a Flowable that emits items that are the result of flattening the items emitted by the
     *         Publishers in the Iterable
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> mergeDelayError(Iterable<? extends Publisher<? extends T>> sources) {
        return fromIterable(sources).flatMap((Function)Functions.identity(), true);
    }


    /**
     * Flattens an Iterable of Publishers into one Publisher, in a way that allows a Subscriber to receive all
     * successfully emitted items from each of the source Publishers without being interrupted by an error
     * notification from one of them, while limiting the number of concurrent subscriptions to these Publishers.
     * <p>
     * This behaves like {@link #merge(Publisher)} except that if any of the merged Publishers notify of an
     * error via {@link Subscriber#onError onError}, {@code mergeDelayError} will refrain from propagating that
     * error notification until all of the merged Publishers have finished emitting items.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeDelayError.png" alt="">
     * <p>
     * Even if multiple merged Publishers send {@code onError} notifications, {@code mergeDelayError} will only
     * invoke the {@code onError} method of its Subscribers once.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. All inner {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code mergeDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            the Iterable of Publishers
     * @param maxConcurrency
     *            the maximum number of Publishers that may be subscribed to concurrently
     * @param bufferSize
     *            the number of items to prefetch from each inner Publisher
     * @return a Flowable that emits items that are the result of flattening the items emitted by the
     *         Publishers in the Iterable
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> mergeDelayError(Iterable<? extends Publisher<? extends T>> sources, int maxConcurrency, int bufferSize) {
        return fromIterable(sources).flatMap((Function)Functions.identity(), true, maxConcurrency, bufferSize);
    }

    /**
     * Flattens an array of Publishers into one Publisher, in a way that allows a Subscriber to receive all
     * successfully emitted items from each of the source Publishers without being interrupted by an error
     * notification from one of them, while limiting the number of concurrent subscriptions to these Publishers.
     * <p>
     * This behaves like {@link #merge(Publisher)} except that if any of the merged Publishers notify of an
     * error via {@link Subscriber#onError onError}, {@code mergeDelayError} will refrain from propagating that
     * error notification until all of the merged Publishers have finished emitting items.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeDelayError.png" alt="">
     * <p>
     * Even if multiple merged Publishers send {@code onError} notifications, {@code mergeDelayError} will only
     * invoke the {@code onError} method of its Subscribers once.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. All source {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code mergeArrayDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            the array of Publishers
     * @param maxConcurrency
     *            the maximum number of Publishers that may be subscribed to concurrently
     * @param bufferSize
     *            the number of items to prefetch from each inner Publisher
     * @return a Flowable that emits items that are the result of flattening the items emitted by the
     *         Publishers in the Iterable
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> mergeArrayDelayError(int maxConcurrency, int bufferSize, Publisher<? extends T>... sources) {
        return fromArray(sources).flatMap((Function)Functions.identity(), true, maxConcurrency, bufferSize);
    }

    /**
     * Flattens an Iterable of Publishers into one Publisher, in a way that allows a Subscriber to receive all
     * successfully emitted items from each of the source Publishers without being interrupted by an error
     * notification from one of them, while limiting the number of concurrent subscriptions to these Publishers.
     * <p>
     * This behaves like {@link #merge(Publisher)} except that if any of the merged Publishers notify of an
     * error via {@link Subscriber#onError onError}, {@code mergeDelayError} will refrain from propagating that
     * error notification until all of the merged Publishers have finished emitting items.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeDelayError.png" alt="">
     * <p>
     * Even if multiple merged Publishers send {@code onError} notifications, {@code mergeDelayError} will only
     * invoke the {@code onError} method of its Subscribers once.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. All inner {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code mergeDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            the Iterable of Publishers
     * @param maxConcurrency
     *            the maximum number of Publishers that may be subscribed to concurrently
     * @return a Flowable that emits items that are the result of flattening the items emitted by the
     *         Publishers in the Iterable
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> mergeDelayError(Iterable<? extends Publisher<? extends T>> sources, int maxConcurrency) {
        return fromIterable(sources).flatMap((Function)Functions.identity(), true, maxConcurrency);
    }

    /**
     * Flattens a Publisher that emits Publishers into one Publisher, in a way that allows a Subscriber to
     * receive all successfully emitted items from all of the source Publishers without being interrupted by
     * an error notification from one of them.
     * <p>
     * This behaves like {@link #merge(Publisher)} except that if any of the merged Publishers notify of an
     * error via {@link Subscriber#onError onError}, {@code mergeDelayError} will refrain from propagating that
     * error notification until all of the merged Publishers have finished emitting items.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeDelayError.png" alt="">
     * <p>
     * Even if multiple merged Publishers send {@code onError} notifications, {@code mergeDelayError} will only
     * invoke the {@code onError} method of its Subscribers once.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The outer {@code Publisher} is consumed
     *  in unbounded mode (i.e., no backpressure is applied to it). The inner {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code mergeDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            a Publisher that emits Publishers
     * @return a Flowable that emits all of the items emitted by the Publishers emitted by the
     *         {@code source} Publisher
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> mergeDelayError(Publisher<? extends Publisher<? extends T>> sources) {
        return mergeDelayError(sources, bufferSize());
    }

    /**
     * Flattens a Publisher that emits Publishers into one Publisher, in a way that allows a Subscriber to
     * receive all successfully emitted items from all of the source Publishers without being interrupted by
     * an error notification from one of them, while limiting the
     * number of concurrent subscriptions to these Publishers.
     * <p>
     * This behaves like {@link #merge(Publisher)} except that if any of the merged Publishers notify of an
     * error via {@link Subscriber#onError onError}, {@code mergeDelayError} will refrain from propagating that
     * error notification until all of the merged Publishers have finished emitting items.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeDelayError.png" alt="">
     * <p>
     * Even if multiple merged Publishers send {@code onError} notifications, {@code mergeDelayError} will only
     * invoke the {@code onError} method of its Subscribers once.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. Both the outer and inner {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code mergeDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            a Publisher that emits Publishers
     * @param maxConcurrency
     *            the maximum number of Publishers that may be subscribed to concurrently
     * @return a Flowable that emits all of the items emitted by the Publishers emitted by the
     *         {@code source} Publisher
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     * @since 2.0
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> mergeDelayError(Publisher<? extends Publisher<? extends T>> sources, int maxConcurrency) {
        return fromPublisher(sources).flatMap((Function)Functions.identity(), true, maxConcurrency);
    }

    /**
     * Flattens an array of Publishers into one Flowable, in a way that allows a Subscriber to receive all
     * successfully emitted items from each of the source Publishers without being interrupted by an error
     * notification from one of them.
     * <p>
     * This behaves like {@link #merge(Publisher)} except that if any of the merged Publishers notify of an
     * error via {@link Subscriber#onError onError}, {@code mergeDelayError} will refrain from propagating that
     * error notification until all of the merged Publishers have finished emitting items.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeDelayError.png" alt="">
     * <p>
     * Even if multiple merged Publishers send {@code onError} notifications, {@code mergeDelayError} will only
     * invoke the {@code onError} method of its Subscribers once.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. Both the outer and inner {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code mergeArrayDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param sources
     *            the Iterable of Publishers
     * @return a Flowable that emits items that are the result of flattening the items emitted by the
     *         Publishers in the Iterable
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> mergeArrayDelayError(Publisher<? extends T>... sources) {
        return fromArray(sources).flatMap((Function)Functions.identity(), true, sources.length);
    }

    /**
     * Flattens two Publishers into one Publisher, in a way that allows a Subscriber to receive all
     * successfully emitted items from each of the source Publishers without being interrupted by an error
     * notification from one of them.
     * <p>
     * This behaves like {@link #merge(Publisher, Publisher)} except that if any of the merged Publishers
     * notify of an error via {@link Subscriber#onError onError}, {@code mergeDelayError} will refrain from
     * propagating that error notification until all of the merged Publishers have finished emitting items.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeDelayError.png" alt="">
     * <p>
     * Even if both merged Publishers send {@code onError} notifications, {@code mergeDelayError} will only
     * invoke the {@code onError} method of its Subscribers once.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code mergeDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param source1
     *            a Publisher to be merged
     * @param source2
     *            a Publisher to be merged
     * @return a Flowable that emits all of the items that are emitted by the two source Publishers
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> mergeDelayError(Publisher<? extends T> source1, Publisher<? extends T> source2) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        return fromArray(source1, source2).flatMap((Function)Functions.identity(), true, 2);
    }

    /**
     * Flattens three Publishers into one Publisher, in a way that allows a Subscriber to receive all
     * successfully emitted items from all of the source Publishers without being interrupted by an error
     * notification from one of them.
     * <p>
     * This behaves like {@link #merge(Publisher, Publisher, Publisher)} except that if any of the merged
     * Publishers notify of an error via {@link Subscriber#onError onError}, {@code mergeDelayError} will refrain
     * from propagating that error notification until all of the merged Publishers have finished emitting
     * items.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeDelayError.png" alt="">
     * <p>
     * Even if multiple merged Publishers send {@code onError} notifications, {@code mergeDelayError} will only
     * invoke the {@code onError} method of its Subscribers once.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code mergeDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param source1
     *            a Publisher to be merged
     * @param source2
     *            a Publisher to be merged
     * @param source3
     *            a Publisher to be merged
     * @return a Flowable that emits all of the items that are emitted by the source Publishers
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> mergeDelayError(Publisher<? extends T> source1, Publisher<? extends T> source2, Publisher<? extends T> source3) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        return fromArray(source1, source2, source3).flatMap((Function)Functions.identity(), true, 3);
    }


    /**
     * Flattens four Publishers into one Publisher, in a way that allows a Subscriber to receive all
     * successfully emitted items from all of the source Publishers without being interrupted by an error
     * notification from one of them.
     * <p>
     * This behaves like {@link #merge(Publisher, Publisher, Publisher, Publisher)} except that if any of
     * the merged Publishers notify of an error via {@link Subscriber#onError onError}, {@code mergeDelayError}
     * will refrain from propagating that error notification until all of the merged Publishers have finished
     * emitting items.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeDelayError.png" alt="">
     * <p>
     * Even if multiple merged Publishers send {@code onError} notifications, {@code mergeDelayError} will only
     * invoke the {@code onError} method of its Subscribers once.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code mergeDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element base type
     * @param source1
     *            a Publisher to be merged
     * @param source2
     *            a Publisher to be merged
     * @param source3
     *            a Publisher to be merged
     * @param source4
     *            a Publisher to be merged
     * @return a Flowable that emits all of the items that are emitted by the source Publishers
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> mergeDelayError(
            Publisher<? extends T> source1, Publisher<? extends T> source2,
            Publisher<? extends T> source3, Publisher<? extends T> source4) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        return fromArray(source1, source2, source3, source4).flatMap((Function)Functions.identity(), true, 4);
    }

    /**
     * Returns a Flowable that never sends any items or notifications to a {@link Subscriber}.
     * <p>
     * <img width="640" height="185" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/never.png" alt="">
     * <p>
     * This Publisher is useful primarily for testing purposes.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This source doesn't produce any elements and effectively ignores downstream backpressure.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code never} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T>
     *            the type of items (not) emitted by the Publisher
     * @return a Flowable that never emits any items or sends any notifications to a {@link Subscriber}
     * @see <a href="http://reactivex.io/documentation/operators/empty-never-throw.html">ReactiveX operators documentation: Never</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    @SuppressWarnings("unchecked")
    public static <T> Flowable<T> never() {
        return RxJavaPlugins.onAssembly((Flowable<T>) FlowableNever.INSTANCE);
    }

    /**
     * Returns a Flowable that emits a sequence of Integers within a specified range.
     * <p>
     * <img width="640" height="195" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/range.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and signals values on-demand (i.e., when requested).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code range} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param start
     *            the value of the first Integer in the sequence
     * @param count
     *            the number of sequential Integers to generate
     * @return a Flowable that emits a range of sequential Integers
     * @throws IllegalArgumentException
     *             if {@code count} is less than zero, or if {@code start} + {@code count} &minus; 1 exceeds
     *             {@code Integer.MAX_VALUE}
     * @see <a href="http://reactivex.io/documentation/operators/range.html">ReactiveX operators documentation: Range</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static Flowable<Integer> range(int start, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count >= 0 required but it was " + count);
        } else
        if (count == 0) {
            return empty();
        } else
        if (count == 1) {
            return just(start);
        } else
        if ((long)start + (count - 1) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Integer overflow");
        }
        return RxJavaPlugins.onAssembly(new FlowableRange(start, count));
    }

    /**
     * Returns a Flowable that emits a sequence of Longs within a specified range.
     * <p>
     * <img width="640" height="195" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/range.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and signals values on-demand (i.e., when requested).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code rangeLong} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param start
     *            the value of the first Long in the sequence
     * @param count
     *            the number of sequential Longs to generate
     * @return a Flowable that emits a range of sequential Longs
     * @throws IllegalArgumentException
     *             if {@code count} is less than zero, or if {@code start} + {@code count} &minus; 1 exceeds
     *             {@code Long.MAX_VALUE}
     * @see <a href="http://reactivex.io/documentation/operators/range.html">ReactiveX operators documentation: Range</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static Flowable<Long> rangeLong(long start, long count) {
        if (count < 0) {
            throw new IllegalArgumentException("count >= 0 required but it was " + count);
        }

        if (count == 0) {
            return empty();
        }

        if (count == 1) {
            return just(start);
        }

        long end = start + (count - 1);
        if (start > 0 && end < 0) {
            throw new IllegalArgumentException("Overflow! start + count is bigger than Long.MAX_VALUE");
        }

        return RxJavaPlugins.onAssembly(new FlowableRangeLong(start, count));
    }

    /**
     * Returns a Single that emits a Boolean value that indicates whether two Publisher sequences are the
     * same by comparing the items emitted by each Publisher pairwise.
     * <p>
     * <img width="640" height="385" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/sequenceEqual.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator honors downstream backpressure and expects both of its sources
     *  to honor backpressure as well. If violated, the operator will emit a MissingBackpressureException.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code sequenceEqual} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param source1
     *            the first Publisher to compare
     * @param source2
     *            the second Publisher to compare
     * @param <T>
     *            the type of items emitted by each Publisher
     * @return a Flowable that emits a Boolean value that indicates whether the two sequences are the same
     * @see <a href="http://reactivex.io/documentation/operators/sequenceequal.html">ReactiveX operators documentation: SequenceEqual</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Single<Boolean> sequenceEqual(Publisher<? extends T> source1, Publisher<? extends T> source2) {
        return sequenceEqual(source1, source2, ObjectHelper.equalsPredicate(), bufferSize());
    }

    /**
     * Returns a Single that emits a Boolean value that indicates whether two Publisher sequences are the
     * same by comparing the items emitted by each Publisher pairwise based on the results of a specified
     * equality function.
     * <p>
     * <img width="640" height="385" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/sequenceEqual.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator signals a {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code sequenceEqual} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param source1
     *            the first Publisher to compare
     * @param source2
     *            the second Publisher to compare
     * @param isEqual
     *            a function used to compare items emitted by each Publisher
     * @param <T>
     *            the type of items emitted by each Publisher
     * @return a Single that emits a Boolean value that indicates whether the two Publisher sequences
     *         are the same according to the specified function
     * @see <a href="http://reactivex.io/documentation/operators/sequenceequal.html">ReactiveX operators documentation: SequenceEqual</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Single<Boolean> sequenceEqual(Publisher<? extends T> source1, Publisher<? extends T> source2,
            BiPredicate<? super T, ? super T> isEqual) {
        return sequenceEqual(source1, source2, isEqual, bufferSize());
    }

    /**
     * Returns a Single that emits a Boolean value that indicates whether two Publisher sequences are the
     * same by comparing the items emitted by each Publisher pairwise based on the results of a specified
     * equality function.
     * <p>
     * <img width="640" height="385" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/sequenceEqual.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator signals a {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code sequenceEqual} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param source1
     *            the first Publisher to compare
     * @param source2
     *            the second Publisher to compare
     * @param isEqual
     *            a function used to compare items emitted by each Publisher
     * @param bufferSize
     *            the number of items to prefetch from the first and second source Publisher
     * @param <T>
     *            the type of items emitted by each Publisher
     * @return a Single that emits a Boolean value that indicates whether the two Publisher sequences
     *         are the same according to the specified function
     * @see <a href="http://reactivex.io/documentation/operators/sequenceequal.html">ReactiveX operators documentation: SequenceEqual</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Single<Boolean> sequenceEqual(Publisher<? extends T> source1, Publisher<? extends T> source2,
            BiPredicate<? super T, ? super T> isEqual, int bufferSize) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(isEqual, "isEqual is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return RxJavaPlugins.onAssembly(new FlowableSequenceEqualSingle<T>(source1, source2, isEqual, bufferSize));
    }

    /**
     * Returns a Single that emits a Boolean value that indicates whether two Publisher sequences are the
     * same by comparing the items emitted by each Publisher pairwise.
     * <p>
     * <img width="640" height="385" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/sequenceEqual.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator honors downstream backpressure and expects both of its sources
     *  to honor backpressure as well. If violated, the operator will emit a MissingBackpressureException.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code sequenceEqual} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param source1
     *            the first Publisher to compare
     * @param source2
     *            the second Publisher to compare
     * @param bufferSize
     *            the number of items to prefetch from the first and second source Publisher
     * @param <T>
     *            the type of items emitted by each Publisher
     * @return a Single that emits a Boolean value that indicates whether the two sequences are the same
     * @see <a href="http://reactivex.io/documentation/operators/sequenceequal.html">ReactiveX operators documentation: SequenceEqual</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Single<Boolean> sequenceEqual(Publisher<? extends T> source1, Publisher<? extends T> source2, int bufferSize) {
        return sequenceEqual(source1, source2, ObjectHelper.equalsPredicate(), bufferSize);
    }

    /**
     * Converts a Publisher that emits Publishers into a Publisher that emits the items emitted by the
     * most recently emitted of those Publishers.
     * <p>
     * <img width="640" height="370" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/switchDo.png" alt="">
     * <p>
     * {@code switchOnNext} subscribes to a Publisher that emits Publishers. Each time it observes one of
     * these emitted Publishers, the Publisher returned by {@code switchOnNext} begins emitting the items
     * emitted by that Publisher. When a new Publisher is emitted, {@code switchOnNext} stops emitting items
     * from the earlier-emitted Publisher and begins emitting items from the new one.
     * <p>
     * The resulting Publisher completes if both the outer Publisher and the last inner Publisher, if any, complete.
     * If the outer Publisher signals an onError, the inner Publisher is cancelled and the error delivered in-sequence.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The outer {@code Publisher} is consumed in an
     *  unbounded manner (i.e., without backpressure) and the inner {@code Publisher}s are expected to honor
     *  backpressure but it is not enforced; the operator won't signal a {@code MissingBackpressureException}
     *  but the violation <em>may</em> lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code switchOnNext} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the item type
     * @param sources
     *            the source Publisher that emits Publishers
     * @param bufferSize
     *            the number of items to prefetch from the inner Publishers
     * @return a Flowable that emits the items emitted by the Publisher most recently emitted by the source
     *         Publisher
     * @see <a href="http://reactivex.io/documentation/operators/switch.html">ReactiveX operators documentation: Switch</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> switchOnNext(Publisher<? extends Publisher<? extends T>> sources, int bufferSize) {
        return fromPublisher(sources).switchMap((Function)Functions.identity(), bufferSize);
    }

    /**
     * Converts a Publisher that emits Publishers into a Publisher that emits the items emitted by the
     * most recently emitted of those Publishers.
     * <p>
     * <img width="640" height="370" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/switchDo.png" alt="">
     * <p>
     * {@code switchOnNext} subscribes to a Publisher that emits Publishers. Each time it observes one of
     * these emitted Publishers, the Publisher returned by {@code switchOnNext} begins emitting the items
     * emitted by that Publisher. When a new Publisher is emitted, {@code switchOnNext} stops emitting items
     * from the earlier-emitted Publisher and begins emitting items from the new one.
     * <p>
     * The resulting Publisher completes if both the outer Publisher and the last inner Publisher, if any, complete.
     * If the outer Publisher signals an onError, the inner Publisher is cancelled and the error delivered in-sequence.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The outer {@code Publisher} is consumed in an
     *  unbounded manner (i.e., without backpressure) and the inner {@code Publisher}s are expected to honor
     *  backpressure but it is not enforced; the operator won't signal a {@code MissingBackpressureException}
     *  but the violation <em>may</em> lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code switchOnNext} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the item type
     * @param sources
     *            the source Publisher that emits Publishers
     * @return a Flowable that emits the items emitted by the Publisher most recently emitted by the source
     *         Publisher
     * @see <a href="http://reactivex.io/documentation/operators/switch.html">ReactiveX operators documentation: Switch</a>
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> switchOnNext(Publisher<? extends Publisher<? extends T>> sources) {
        return fromPublisher(sources).switchMap((Function)Functions.identity());
    }

    /**
     * Converts a Publisher that emits Publishers into a Publisher that emits the items emitted by the
     * most recently emitted of those Publishers and delays any exception until all Publishers terminate.
     * <p>
     * <img width="640" height="370" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/switchDo.png" alt="">
     * <p>
     * {@code switchOnNext} subscribes to a Publisher that emits Publishers. Each time it observes one of
     * these emitted Publishers, the Publisher returned by {@code switchOnNext} begins emitting the items
     * emitted by that Publisher. When a new Publisher is emitted, {@code switchOnNext} stops emitting items
     * from the earlier-emitted Publisher and begins emitting items from the new one.
     * <p>
     * The resulting Publisher completes if both the main Publisher and the last inner Publisher, if any, complete.
     * If the main Publisher signals an onError, the termination of the last inner Publisher will emit that error as is
     * or wrapped into a CompositeException along with the other possible errors the former inner Publishers signalled.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The outer {@code Publisher} is consumed in an
     *  unbounded manner (i.e., without backpressure) and the inner {@code Publisher}s are expected to honor
     *  backpressure but it is not enforced; the operator won't signal a {@code MissingBackpressureException}
     *  but the violation <em>may</em> lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code switchOnNextDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the item type
     * @param sources
     *            the source Publisher that emits Publishers
     * @return a Flowable that emits the items emitted by the Publisher most recently emitted by the source
     *         Publisher
     * @see <a href="http://reactivex.io/documentation/operators/switch.html">ReactiveX operators documentation: Switch</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> switchOnNextDelayError(Publisher<? extends Publisher<? extends T>> sources) {
        return switchOnNextDelayError(sources, bufferSize());
    }

    /**
     * Converts a Publisher that emits Publishers into a Publisher that emits the items emitted by the
     * most recently emitted of those Publishers and delays any exception until all Publishers terminate.
     * <p>
     * <img width="640" height="370" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/switchDo.png" alt="">
     * <p>
     * {@code switchOnNext} subscribes to a Publisher that emits Publishers. Each time it observes one of
     * these emitted Publishers, the Publisher returned by {@code switchOnNext} begins emitting the items
     * emitted by that Publisher. When a new Publisher is emitted, {@code switchOnNext} stops emitting items
     * from the earlier-emitted Publisher and begins emitting items from the new one.
     * <p>
     * The resulting Publisher completes if both the main Publisher and the last inner Publisher, if any, complete.
     * If the main Publisher signals an onError, the termination of the last inner Publisher will emit that error as is
     * or wrapped into a CompositeException along with the other possible errors the former inner Publishers signalled.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The outer {@code Publisher} is consumed in an
     *  unbounded manner (i.e., without backpressure) and the inner {@code Publisher}s are expected to honor
     *  backpressure but it is not enforced; the operator won't signal a {@code MissingBackpressureException}
     *  but the violation <em>may</em> lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code switchOnNextDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the item type
     * @param sources
     *            the source Publisher that emits Publishers
     * @param prefetch
     *            the number of items to prefetch from the inner Publishers
     * @return a Flowable that emits the items emitted by the Publisher most recently emitted by the source
     *         Publisher
     * @see <a href="http://reactivex.io/documentation/operators/switch.html">ReactiveX operators documentation: Switch</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> switchOnNextDelayError(Publisher<? extends Publisher<? extends T>> sources, int prefetch) {
        return fromPublisher(sources).switchMapDelayError(Functions.<Publisher<? extends T>>identity(), prefetch);
    }

    /**
     * Returns a Flowable that emits {@code 0L} after a specified delay, and then completes.
     * <p>
     * <img width="640" height="200" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timer.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time. If the downstream needs a slower rate
     *      it should slow the timer or use something like {@link #onBackpressureDrop}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code timer} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param delay
     *            the initial delay before emitting a single {@code 0L}
     * @param unit
     *            time units to use for {@code delay}
     * @return a Flowable that emits {@code 0L} after a specified delay, and then completes
     * @see <a href="http://reactivex.io/documentation/operators/timer.html">ReactiveX operators documentation: Timer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public static Flowable<Long> timer(long delay, TimeUnit unit) {
        return timer(delay, unit, Schedulers.computation());
    }

    /**
     * Returns a Flowable that emits {@code 0L} after a specified delay, on a specified Scheduler, and then
     * completes.
     * <p>
     * <img width="640" height="200" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timer.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time. If the downstream needs a slower rate
     *      it should slow the timer or use something like {@link #onBackpressureDrop}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param delay
     *            the initial delay before emitting a single 0L
     * @param unit
     *            time units to use for {@code delay}
     * @param scheduler
     *            the {@link Scheduler} to use for scheduling the item
     * @return a Flowable that emits {@code 0L} after a specified delay, on a specified Scheduler, and then
     *         completes
     * @see <a href="http://reactivex.io/documentation/operators/timer.html">ReactiveX operators documentation: Timer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public static Flowable<Long> timer(long delay, TimeUnit unit, Scheduler scheduler) {
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");

        return RxJavaPlugins.onAssembly(new FlowableTimer(Math.max(0L, delay), unit, scheduler));
    }

    /**
     * Create a Flowable by wrapping a Publisher <em>which has to be implemented according
     * to the Reactive-Streams specification by handling backpressure and
     * cancellation correctly; no safeguards are provided by the Flowable itself</em>.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator is a pass-through for backpressure and the behavior is determined by the
     *  provided Publisher implementation.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code unsafeCreate} by default doesn't operate on any particular {@link Scheduler}.</dd>
     * </dl>
     * @param <T> the value type emitted
     * @param onSubscribe the Publisher instance to wrap
     * @return the new Flowable instance
     * @throws IllegalArgumentException if {@code onSubscribe} is a subclass of {@code Flowable}; such
     * instances don't need conversion and is possibly a port remnant from 1.x or one should use {@link #hide()}
     * instead.
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.NONE)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T> Flowable<T> unsafeCreate(Publisher<T> onSubscribe) {
        ObjectHelper.requireNonNull(onSubscribe, "onSubscribe is null");
        if (onSubscribe instanceof Flowable) {
            throw new IllegalArgumentException("unsafeCreate(Flowable) should be upgraded");
        }
        return RxJavaPlugins.onAssembly(new FlowableFromPublisher<T>(onSubscribe));
    }

    /**
     * Constructs a Publisher that creates a dependent resource object which is disposed of on cancellation.
     * <p>
     * <img width="640" height="400" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/using.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator is a pass-through for backpressure and otherwise depends on the
     *  backpressure support of the Publisher returned by the {@code resourceFactory}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code using} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the element type of the generated Publisher
     * @param <D> the type of the resource associated with the output sequence
     * @param resourceSupplier
     *            the factory function to create a resource object that depends on the Publisher
     * @param sourceSupplier
     *            the factory function to create a Publisher
     * @param resourceDisposer
     *            the function that will dispose of the resource
     * @return the Publisher whose lifetime controls the lifetime of the dependent resource object
     * @see <a href="http://reactivex.io/documentation/operators/using.html">ReactiveX operators documentation: Using</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T, D> Flowable<T> using(Callable<? extends D> resourceSupplier,
            Function<? super D, ? extends Publisher<? extends T>> sourceSupplier, Consumer<? super D> resourceDisposer) {
        return using(resourceSupplier, sourceSupplier, resourceDisposer, true);
    }

    /**
     * Constructs a Publisher that creates a dependent resource object which is disposed of just before
     * termination if you have set {@code disposeEagerly} to {@code true} and cancellation does not occur
     * before termination. Otherwise resource disposal will occur on cancellation.  Eager disposal is
     * particularly appropriate for a synchronous Publisher that reuses resources. {@code disposeAction} will
     * only be called once per subscription.
     * <p>
     * <img width="640" height="400" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/using.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator is a pass-through for backpressure and otherwise depends on the
     *  backpressure support of the Publisher returned by the {@code resourceFactory}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code using} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the element type of the generated Publisher
     * @param <D> the type of the resource associated with the output sequence
     * @param resourceSupplier
     *            the factory function to create a resource object that depends on the Publisher
     * @param sourceSupplier
     *            the factory function to create a Publisher
     * @param resourceDisposer
     *            the function that will dispose of the resource
     * @param eager
     *            if {@code true} then disposal will happen either on cancellation or just before emission of
     *            a terminal event ({@code onComplete} or {@code onError}).
     * @return the Publisher whose lifetime controls the lifetime of the dependent resource object
     * @see <a href="http://reactivex.io/documentation/operators/using.html">ReactiveX operators documentation: Using</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T, D> Flowable<T> using(Callable<? extends D> resourceSupplier,
            Function<? super D, ? extends Publisher<? extends T>> sourceSupplier,
                    Consumer<? super D> resourceDisposer, boolean eager) {
        ObjectHelper.requireNonNull(resourceSupplier, "resourceSupplier is null");
        ObjectHelper.requireNonNull(sourceSupplier, "sourceSupplier is null");
        ObjectHelper.requireNonNull(resourceDisposer, "disposer is null");
        return RxJavaPlugins.onAssembly(new FlowableUsing<T, D>(resourceSupplier, sourceSupplier, resourceDisposer, eager));
    }

    /**
     * Returns a Flowable that emits the results of a specified combiner function applied to combinations of
     * items emitted, in sequence, by an Iterable of other Publishers.
     * <p>
     * {@code zip} applies this function in strict sequence, so the first item emitted by the new Publisher
     * will be the result of the function applied to the first item emitted by each of the source Publishers;
     * the second item emitted by the new Publisher will be the result of the function applied to the second
     * item emitted by each of those Publishers; and so forth.
     * <p>
     * The resulting {@code Publisher<R>} returned from {@code zip} will invoke {@code onNext} as many times as
     * the number of {@code onNext} invocations of the source Publisher that emits the fewest items.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>zip(Arrays.asList(range(1, 5).doOnComplete(action1), range(6, 5).doOnComplete(action2)), (a) -&gt; a)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zip} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common value type
     * @param <R> the zipped result type
     * @param sources
     *            an Iterable of source Publishers
     * @param zipper
     *            a function that, when applied to an item emitted by each of the source Publishers, results in
     *            an item that will be emitted by the resulting Publisher
     * @return a Flowable that emits the zipped results
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T, R> Flowable<R> zip(Iterable<? extends Publisher<? extends T>> sources, Function<? super Object[], ? extends R> zipper) {
        ObjectHelper.requireNonNull(zipper, "zipper is null");
        ObjectHelper.requireNonNull(sources, "sources is null");
        return RxJavaPlugins.onAssembly(new FlowableZip<T, R>(null, sources, zipper, bufferSize(), false));
    }

    /**
     * Returns a Flowable that emits the results of a specified combiner function applied to combinations of
     * <i>n</i> items emitted, in sequence, by the <i>n</i> Publishers emitted by a specified Publisher.
     * <p>
     * {@code zip} applies this function in strict sequence, so the first item emitted by the new Publisher
     * will be the result of the function applied to the first item emitted by each of the Publishers emitted
     * by the source Publisher; the second item emitted by the new Publisher will be the result of the
     * function applied to the second item emitted by each of those Publishers; and so forth.
     * <p>
     * The resulting {@code Publisher<R>} returned from {@code zip} will invoke {@code onNext} as many times as
     * the number of {@code onNext} invocations of the source Publisher that emits the fewest items.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancel the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>zip(just(range(1, 5).doOnComplete(action1), range(6, 5).doOnComplete(action2)), (a) -&gt; a)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     * <p>
     * <img width="640" height="370" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.o.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zip} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the value type of the inner Publishers
     * @param <R> the zipped result type
     * @param sources
     *            a Publisher of source Publishers
     * @param zipper
     *            a function that, when applied to an item emitted by each of the Publishers emitted by
     *            {@code ws}, results in an item that will be emitted by the resulting Publisher
     * @return a Flowable that emits the zipped results
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @SuppressWarnings({ "rawtypes", "unchecked", "cast" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T, R> Flowable<R> zip(Publisher<? extends Publisher<? extends T>> sources,
            final Function<? super Object[], ? extends R> zipper) {
        ObjectHelper.requireNonNull(zipper, "zipper is null");
        return fromPublisher(sources).toList().flatMapPublisher((Function)FlowableInternalHelper.<T, R>zipIterable(zipper));
    }

    /**
     * Returns a Flowable that emits the results of a specified combiner function applied to combinations of
     * two items emitted, in sequence, by two other Publishers.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <p>
     * {@code zip} applies this function in strict sequence, so the first item emitted by the new Publisher
     * will be the result of the function applied to the first item emitted by {@code o1} and the first item
     * emitted by {@code o2}; the second item emitted by the new Publisher will be the result of the function
     * applied to the second item emitted by {@code o1} and the second item emitted by {@code o2}; and so forth.
     * <p>
     * The resulting {@code Publisher<R>} returned from {@code zip} will invoke {@link Subscriber#onNext onNext}
     * as many times as the number of {@code onNext} invocations of the source Publisher that emits the fewest
     * items.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>zip(range(1, 5).doOnComplete(action1), range(6, 5).doOnComplete(action2), (a, b) -&gt; a + b)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zip} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the value type of the first source
     * @param <T2> the value type of the second source
     * @param <R> the zipped result type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            a second source Publisher
     * @param zipper
     *            a function that, when applied to an item emitted by each of the source Publishers, results
     *            in an item that will be emitted by the resulting Publisher
     * @return a Flowable that emits the zipped results
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, R> Flowable<R> zip(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2,
            BiFunction<? super T1, ? super T2, ? extends R> zipper) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        return zipArray(Functions.toFunction(zipper), false, bufferSize(), source1, source2);
    }

    /**
     * Returns a Flowable that emits the results of a specified combiner function applied to combinations of
     * two items emitted, in sequence, by two other Publishers.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <p>
     * {@code zip} applies this function in strict sequence, so the first item emitted by the new Publisher
     * will be the result of the function applied to the first item emitted by {@code o1} and the first item
     * emitted by {@code o2}; the second item emitted by the new Publisher will be the result of the function
     * applied to the second item emitted by {@code o1} and the second item emitted by {@code o2}; and so forth.
     * <p>
     * The resulting {@code Publisher<R>} returned from {@code zip} will invoke {@link Subscriber#onNext onNext}
     * as many times as the number of {@code onNext} invocations of the source Publisher that emits the fewest
     * items.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>zip(range(1, 5).doOnComplete(action1), range(6, 5).doOnComplete(action2), (a, b) -&gt; a + b)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zip} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the value type of the first source
     * @param <T2> the value type of the second source
     * @param <R> the zipped result type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            a second source Publisher
     * @param zipper
     *            a function that, when applied to an item emitted by each of the source Publishers, results
     *            in an item that will be emitted by the resulting Publisher
     * @param delayError delay errors from any of the source Publishers till the other terminates
     * @return a Flowable that emits the zipped results
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, R> Flowable<R> zip(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2,
            BiFunction<? super T1, ? super T2, ? extends R> zipper, boolean delayError) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        return zipArray(Functions.toFunction(zipper), delayError, bufferSize(), source1, source2);
    }


    /**
     * Returns a Flowable that emits the results of a specified combiner function applied to combinations of
     * two items emitted, in sequence, by two other Publishers.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <p>
     * {@code zip} applies this function in strict sequence, so the first item emitted by the new Publisher
     * will be the result of the function applied to the first item emitted by {@code o1} and the first item
     * emitted by {@code o2}; the second item emitted by the new Publisher will be the result of the function
     * applied to the second item emitted by {@code o1} and the second item emitted by {@code o2}; and so forth.
     * <p>
     * The resulting {@code Publisher<R>} returned from {@code zip} will invoke {@link Subscriber#onNext onNext}
     * as many times as the number of {@code onNext} invocations of the source Publisher that emits the fewest
     * items.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>zip(range(1, 5).doOnComplete(action1), range(6, 5).doOnComplete(action2), (a, b) -&gt; a + b)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zip} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the value type of the first source
     * @param <T2> the value type of the second source
     * @param <R> the zipped result type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            a second source Publisher
     * @param zipper
     *            a function that, when applied to an item emitted by each of the source Publishers, results
     *            in an item that will be emitted by the resulting Publisher
     * @param delayError delay errors from any of the source Publishers till the other terminates
     * @param bufferSize the number of elements to prefetch from each source Publisher
     * @return a Flowable that emits the zipped results
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, R> Flowable<R> zip(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2,
            BiFunction<? super T1, ? super T2, ? extends R> zipper, boolean delayError, int bufferSize) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        return zipArray(Functions.toFunction(zipper), delayError, bufferSize, source1, source2);
    }

    /**
     * Returns a Flowable that emits the results of a specified combiner function applied to combinations of
     * three items emitted, in sequence, by three other Publishers.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <p>
     * {@code zip} applies this function in strict sequence, so the first item emitted by the new Publisher
     * will be the result of the function applied to the first item emitted by {@code o1}, the first item
     * emitted by {@code o2}, and the first item emitted by {@code o3}; the second item emitted by the new
     * Publisher will be the result of the function applied to the second item emitted by {@code o1}, the
     * second item emitted by {@code o2}, and the second item emitted by {@code o3}; and so forth.
     * <p>
     * The resulting {@code Publisher<R>} returned from {@code zip} will invoke {@link Subscriber#onNext onNext}
     * as many times as the number of {@code onNext} invocations of the source Publisher that emits the fewest
     * items.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>zip(range(1, 5).doOnComplete(action1), range(6, 5).doOnComplete(action2), ..., (a, b, c) -&gt; a + b)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zip} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the value type of the first source
     * @param <T2> the value type of the second source
     * @param <T3> the value type of the third source
     * @param <R> the zipped result type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            a second source Publisher
     * @param source3
     *            a third source Publisher
     * @param zipper
     *            a function that, when applied to an item emitted by each of the source Publishers, results in
     *            an item that will be emitted by the resulting Publisher
     * @return a Flowable that emits the zipped results
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, T3, R> Flowable<R> zip(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2, Publisher<? extends T3> source3,
            Function3<? super T1, ? super T2, ? super T3, ? extends R> zipper) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        return zipArray(Functions.toFunction(zipper), false, bufferSize(), source1, source2, source3);
    }

    /**
     * Returns a Flowable that emits the results of a specified combiner function applied to combinations of
     * four items emitted, in sequence, by four other Publishers.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <p>
     * {@code zip} applies this function in strict sequence, so the first item emitted by the new Publisher
     * will be the result of the function applied to the first item emitted by {@code o1}, the first item
     * emitted by {@code o2}, the first item emitted by {@code o3}, and the first item emitted by {@code 04};
     * the second item emitted by the new Publisher will be the result of the function applied to the second
     * item emitted by each of those Publishers; and so forth.
     * <p>
     * The resulting {@code Publisher<R>} returned from {@code zip} will invoke {@link Subscriber#onNext onNext}
     * as many times as the number of {@code onNext} invocations of the source Publisher that emits the fewest
     * items.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>zip(range(1, 5).doOnComplete(action1), range(6, 5).doOnComplete(action2), ..., (a, b, c, d) -&gt; a + b)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zip} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the value type of the first source
     * @param <T2> the value type of the second source
     * @param <T3> the value type of the third source
     * @param <T4> the value type of the fourth source
     * @param <R> the zipped result type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            a second source Publisher
     * @param source3
     *            a third source Publisher
     * @param source4
     *            a fourth source Publisher
     * @param zipper
     *            a function that, when applied to an item emitted by each of the source Publishers, results in
     *            an item that will be emitted by the resulting Publisher
     * @return a Flowable that emits the zipped results
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, T3, T4, R> Flowable<R> zip(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2, Publisher<? extends T3> source3,
            Publisher<? extends T4> source4,
            Function4<? super T1, ? super T2, ? super T3, ? super T4, ? extends R> zipper) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        return zipArray(Functions.toFunction(zipper), false, bufferSize(), source1, source2, source3, source4);
    }

    /**
     * Returns a Flowable that emits the results of a specified combiner function applied to combinations of
     * five items emitted, in sequence, by five other Publishers.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <p>
     * {@code zip} applies this function in strict sequence, so the first item emitted by the new Publisher
     * will be the result of the function applied to the first item emitted by {@code o1}, the first item
     * emitted by {@code o2}, the first item emitted by {@code o3}, the first item emitted by {@code o4}, and
     * the first item emitted by {@code o5}; the second item emitted by the new Publisher will be the result of
     * the function applied to the second item emitted by each of those Publishers; and so forth.
     * <p>
     * The resulting {@code Publisher<R>} returned from {@code zip} will invoke {@link Subscriber#onNext onNext}
     * as many times as the number of {@code onNext} invocations of the source Publisher that emits the fewest
     * items.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>zip(range(1, 5).doOnComplete(action1), range(6, 5).doOnComplete(action2), ..., (a, b, c, d, e) -&gt; a + b)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zip} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the value type of the first source
     * @param <T2> the value type of the second source
     * @param <T3> the value type of the third source
     * @param <T4> the value type of the fourth source
     * @param <T5> the value type of the fifth source
     * @param <R> the zipped result type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            a second source Publisher
     * @param source3
     *            a third source Publisher
     * @param source4
     *            a fourth source Publisher
     * @param source5
     *            a fifth source Publisher
     * @param zipper
     *            a function that, when applied to an item emitted by each of the source Publishers, results in
     *            an item that will be emitted by the resulting Publisher
     * @return a Flowable that emits the zipped results
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, T3, T4, T5, R> Flowable<R> zip(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2, Publisher<? extends T3> source3,
            Publisher<? extends T4> source4, Publisher<? extends T5> source5,
            Function5<? super T1, ? super T2, ? super T3, ? super T4, ? super T5, ? extends R> zipper) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        ObjectHelper.requireNonNull(source5, "source5 is null");
        return zipArray(Functions.toFunction(zipper), false, bufferSize(), source1, source2, source3, source4, source5);
    }

    /**
     * Returns a Flowable that emits the results of a specified combiner function applied to combinations of
     * six items emitted, in sequence, by six other Publishers.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <p>
     * {@code zip} applies this function in strict sequence, so the first item emitted by the new Publisher
     * will be the result of the function applied to the first item emitted by each source Publisher, the
     * second item emitted by the new Publisher will be the result of the function applied to the second item
     * emitted by each of those Publishers, and so forth.
     * <p>
     * The resulting {@code Publisher<R>} returned from {@code zip} will invoke {@link Subscriber#onNext onNext}
     * as many times as the number of {@code onNext} invocations of the source Publisher that emits the fewest
     * items.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>zip(range(1, 5).doOnComplete(action1), range(6, 5).doOnComplete(action2), ..., (a, b, c, d, e, f) -&gt; a + b)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zip} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the value type of the first source
     * @param <T2> the value type of the second source
     * @param <T3> the value type of the third source
     * @param <T4> the value type of the fourth source
     * @param <T5> the value type of the fifth source
     * @param <T6> the value type of the sixth source
     * @param <R> the zipped result type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            a second source Publisher
     * @param source3
     *            a third source Publisher
     * @param source4
     *            a fourth source Publisher
     * @param source5
     *            a fifth source Publisher
     * @param source6
     *            a sixth source Publisher
     * @param zipper
     *            a function that, when applied to an item emitted by each of the source Publishers, results in
     *            an item that will be emitted by the resulting Publisher
     * @return a Flowable that emits the zipped results
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, T3, T4, T5, T6, R> Flowable<R> zip(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2, Publisher<? extends T3> source3,
            Publisher<? extends T4> source4, Publisher<? extends T5> source5, Publisher<? extends T6> source6,
            Function6<? super T1, ? super T2, ? super T3, ? super T4, ? super T5, ? super T6, ? extends R> zipper) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        ObjectHelper.requireNonNull(source5, "source5 is null");
        ObjectHelper.requireNonNull(source6, "source6 is null");
        return zipArray(Functions.toFunction(zipper), false, bufferSize(), source1, source2, source3, source4, source5, source6);
    }

    /**
     * Returns a Flowable that emits the results of a specified combiner function applied to combinations of
     * seven items emitted, in sequence, by seven other Publishers.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <p>
     * {@code zip} applies this function in strict sequence, so the first item emitted by the new Publisher
     * will be the result of the function applied to the first item emitted by each source Publisher, the
     * second item emitted by the new Publisher will be the result of the function applied to the second item
     * emitted by each of those Publishers, and so forth.
     * <p>
     * The resulting {@code Publisher<R>} returned from {@code zip} will invoke {@link Subscriber#onNext onNext}
     * as many times as the number of {@code onNext} invocations of the source Publisher that emits the fewest
     * items.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>zip(range(1, 5).doOnComplete(action1), range(6, 5).doOnComplete(action2), ..., (a, b, c, d, e, f, g) -&gt; a + b)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zip} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the value type of the first source
     * @param <T2> the value type of the second source
     * @param <T3> the value type of the third source
     * @param <T4> the value type of the fourth source
     * @param <T5> the value type of the fifth source
     * @param <T6> the value type of the sixth source
     * @param <T7> the value type of the seventh source
     * @param <R> the zipped result type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            a second source Publisher
     * @param source3
     *            a third source Publisher
     * @param source4
     *            a fourth source Publisher
     * @param source5
     *            a fifth source Publisher
     * @param source6
     *            a sixth source Publisher
     * @param source7
     *            a seventh source Publisher
     * @param zipper
     *            a function that, when applied to an item emitted by each of the source Publishers, results in
     *            an item that will be emitted by the resulting Publisher
     * @return a Flowable that emits the zipped results
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, T3, T4, T5, T6, T7, R> Flowable<R> zip(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2, Publisher<? extends T3> source3,
            Publisher<? extends T4> source4, Publisher<? extends T5> source5, Publisher<? extends T6> source6,
            Publisher<? extends T7> source7,
            Function7<? super T1, ? super T2, ? super T3, ? super T4, ? super T5, ? super T6, ? super T7, ? extends R> zipper) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        ObjectHelper.requireNonNull(source5, "source5 is null");
        ObjectHelper.requireNonNull(source6, "source6 is null");
        ObjectHelper.requireNonNull(source7, "source7 is null");
        return zipArray(Functions.toFunction(zipper), false, bufferSize(), source1, source2, source3, source4, source5, source6, source7);
    }

    /**
     * Returns a Flowable that emits the results of a specified combiner function applied to combinations of
     * eight items emitted, in sequence, by eight other Publishers.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <p>
     * {@code zip} applies this function in strict sequence, so the first item emitted by the new Publisher
     * will be the result of the function applied to the first item emitted by each source Publisher, the
     * second item emitted by the new Publisher will be the result of the function applied to the second item
     * emitted by each of those Publishers, and so forth.
     * <p>
     * The resulting {@code Publisher<R>} returned from {@code zip} will invoke {@link Subscriber#onNext onNext}
     * as many times as the number of {@code onNext} invocations of the source Publisher that emits the fewest
     * items.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>zip(range(1, 5).doOnComplete(action1), range(6, 5).doOnComplete(action2), ..., (a, b, c, d, e, f, g, h) -&gt; a + b)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zip} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the value type of the first source
     * @param <T2> the value type of the second source
     * @param <T3> the value type of the third source
     * @param <T4> the value type of the fourth source
     * @param <T5> the value type of the fifth source
     * @param <T6> the value type of the sixth source
     * @param <T7> the value type of the seventh source
     * @param <T8> the value type of the eighth source
     * @param <R> the zipped result type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            a second source Publisher
     * @param source3
     *            a third source Publisher
     * @param source4
     *            a fourth source Publisher
     * @param source5
     *            a fifth source Publisher
     * @param source6
     *            a sixth source Publisher
     * @param source7
     *            a seventh source Publisher
     * @param source8
     *            an eighth source Publisher
     * @param zipper
     *            a function that, when applied to an item emitted by each of the source Publishers, results in
     *            an item that will be emitted by the resulting Publisher
     * @return a Flowable that emits the zipped results
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, T3, T4, T5, T6, T7, T8, R> Flowable<R> zip(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2, Publisher<? extends T3> source3,
            Publisher<? extends T4> source4, Publisher<? extends T5> source5, Publisher<? extends T6> source6,
            Publisher<? extends T7> source7, Publisher<? extends T8> source8,
            Function8<? super T1, ? super T2, ? super T3, ? super T4, ? super T5, ? super T6, ? super T7, ? super T8, ? extends R> zipper) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        ObjectHelper.requireNonNull(source5, "source5 is null");
        ObjectHelper.requireNonNull(source6, "source6 is null");
        ObjectHelper.requireNonNull(source7, "source7 is null");
        ObjectHelper.requireNonNull(source8, "source8 is null");
        return zipArray(Functions.toFunction(zipper), false, bufferSize(), source1, source2, source3, source4, source5, source6, source7, source8);
    }

    /**
     * Returns a Flowable that emits the results of a specified combiner function applied to combinations of
     * nine items emitted, in sequence, by nine other Publishers.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <p>
     * {@code zip} applies this function in strict sequence, so the first item emitted by the new Publisher
     * will be the result of the function applied to the first item emitted by each source Publisher, the
     * second item emitted by the new Publisher will be the result of the function applied to the second item
     * emitted by each of those Publishers, and so forth.
     * <p>
     * The resulting {@code Publisher<R>} returned from {@code zip} will invoke {@link Subscriber#onNext onNext}
     * as many times as the number of {@code onNext} invocations of the source Publisher that emits the fewest
     * items.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>zip(range(1, 5).doOnComplete(action1), range(6, 5).doOnComplete(action2), ..., (a, b, c, d, e, f, g, h, i) -&gt; a + b)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zip} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the value type of the first source
     * @param <T2> the value type of the second source
     * @param <T3> the value type of the third source
     * @param <T4> the value type of the fourth source
     * @param <T5> the value type of the fifth source
     * @param <T6> the value type of the sixth source
     * @param <T7> the value type of the seventh source
     * @param <T8> the value type of the eighth source
     * @param <T9> the value type of the ninth source
     * @param <R> the zipped result type
     * @param source1
     *            the first source Publisher
     * @param source2
     *            a second source Publisher
     * @param source3
     *            a third source Publisher
     * @param source4
     *            a fourth source Publisher
     * @param source5
     *            a fifth source Publisher
     * @param source6
     *            a sixth source Publisher
     * @param source7
     *            a seventh source Publisher
     * @param source8
     *            an eighth source Publisher
     * @param source9
     *            a ninth source Publisher
     * @param zipper
     *            a function that, when applied to an item emitted by each of the source Publishers, results in
     *            an item that will be emitted by the resulting Publisher
     * @return a Flowable that emits the zipped results
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, R> Flowable<R> zip(
            Publisher<? extends T1> source1, Publisher<? extends T2> source2, Publisher<? extends T3> source3,
            Publisher<? extends T4> source4, Publisher<? extends T5> source5, Publisher<? extends T6> source6,
            Publisher<? extends T7> source7, Publisher<? extends T8> source8, Publisher<? extends T9> source9,
            Function9<? super T1, ? super T2, ? super T3, ? super T4, ? super T5, ? super T6, ? super T7, ? super T8, ? super T9, ? extends R> zipper) {

        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        ObjectHelper.requireNonNull(source5, "source5 is null");
        ObjectHelper.requireNonNull(source6, "source6 is null");
        ObjectHelper.requireNonNull(source7, "source7 is null");
        ObjectHelper.requireNonNull(source8, "source8 is null");
        ObjectHelper.requireNonNull(source9, "source9 is null");
        return zipArray(Functions.toFunction(zipper), false, bufferSize(), source1, source2, source3, source4, source5, source6, source7, source8, source9);
    }

    /**
     * Returns a Flowable that emits the results of a specified combiner function applied to combinations of
     * items emitted, in sequence, by an array of other Publishers.
     * <p>
     * {@code zip} applies this function in strict sequence, so the first item emitted by the new Publisher
     * will be the result of the function applied to the first item emitted by each of the source Publishers;
     * the second item emitted by the new Publisher will be the result of the function applied to the second
     * item emitted by each of those Publishers; and so forth.
     * <p>
     * The resulting {@code Publisher<R>} returned from {@code zip} will invoke {@code onNext} as many times as
     * the number of {@code onNext} invocations of the source Publisher that emits the fewest items.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>zip(new Publisher[]{range(1, 5).doOnComplete(action1), range(6, 5).doOnComplete(action2)}, (a) -&gt;
     * a)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zipArray} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T> the common element type
     * @param <R> the result type
     * @param sources
     *            an array of source Publishers
     * @param zipper
     *            a function that, when applied to an item emitted by each of the source Publishers, results in
     *            an item that will be emitted by the resulting Publisher
     * @param delayError
     *            delay errors signalled by any of the source Publisher until all Publishers terminate
     * @param bufferSize
     *            the number of elements to prefetch from each source Publisher
     * @return a Flowable that emits the zipped results
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T, R> Flowable<R> zipArray(Function<? super Object[], ? extends R> zipper,
            boolean delayError, int bufferSize, Publisher<? extends T>... sources) {
        if (sources.length == 0) {
            return empty();
        }
        ObjectHelper.requireNonNull(zipper, "zipper is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return RxJavaPlugins.onAssembly(new FlowableZip<T, R>(sources, null, zipper, bufferSize, delayError));
    }

    /**
     * Returns a Flowable that emits the results of a specified combiner function applied to combinations of
     * items emitted, in sequence, by an Iterable of other Publishers.
     * <p>
     * {@code zip} applies this function in strict sequence, so the first item emitted by the new Publisher
     * will be the result of the function applied to the first item emitted by each of the source Publishers;
     * the second item emitted by the new Publisher will be the result of the function applied to the second
     * item emitted by each of those Publishers; and so forth.
     * <p>
     * The resulting {@code Publisher<R>} returned from {@code zip} will invoke {@code onNext} as many times as
     * the number of {@code onNext} invocations of the source Publisher that emits the fewest items.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>zip(Arrays.asList(range(1, 5).doOnComplete(action1), range(6, 5).doOnComplete(action2)), (a) -&gt; a)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zipIterable} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     *
     * @param sources
     *            an Iterable of source Publishers
     * @param zipper
     *            a function that, when applied to an item emitted by each of the source Publishers, results in
     *            an item that will be emitted by the resulting Publisher
     * @param delayError
     *            delay errors signalled by any of the source Publisher until all Publishers terminate
     * @param bufferSize
     *            the number of elements to prefetch from each source Publisher
     * @param <T> the common source value type
     * @param <R> the zipped result type
     * @return a Flowable that emits the zipped results
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public static <T, R> Flowable<R> zipIterable(Iterable<? extends Publisher<? extends T>> sources,
            Function<? super Object[], ? extends R> zipper, boolean delayError,
            int bufferSize) {
        ObjectHelper.requireNonNull(zipper, "zipper is null");
        ObjectHelper.requireNonNull(sources, "sources is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return RxJavaPlugins.onAssembly(new FlowableZip<T, R>(null, sources, zipper, bufferSize, delayError));
    }

    // ***************************************************************************************************
    // Instance operators
    // ***************************************************************************************************

    /**
     * Returns a Single that emits a Boolean that indicates whether all of the items emitted by the source
     * Publisher satisfy a condition.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/all.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded
     *  manner (i.e., without applying backpressure).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code all} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param predicate
     *            a function that evaluates an item and returns a Boolean
     * @return a Single that emits {@code true} if all items emitted by the source Publisher satisfy the
     *         predicate; otherwise, {@code false}
     * @see <a href="http://reactivex.io/documentation/operators/all.html">ReactiveX operators documentation: All</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<Boolean> all(Predicate<? super T> predicate) {
        ObjectHelper.requireNonNull(predicate, "predicate is null");
        return RxJavaPlugins.onAssembly(new FlowableAllSingle<T>(this, predicate));
    }

    /**
     * Mirrors the Publisher (current or provided) that first either emits an item or sends a termination
     * notification.
     * <p>
     * <img width="640" height="385" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/amb.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator itself doesn't interfere with backpressure which is determined by the winning
     *  {@code Publisher}'s backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code ambWith} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param other
     *            a Publisher competing to react first. A subscription to this provided Publisher will occur after subscribing
     *            to the current Publisher.
     * @return a Flowable that emits the same sequence as whichever of the source Publishers first
     *         emitted an item or sent a termination notification
     * @see <a href="http://reactivex.io/documentation/operators/amb.html">ReactiveX operators documentation: Amb</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> ambWith(Publisher<? extends T> other) {
        ObjectHelper.requireNonNull(other, "other is null");
        return ambArray(this, other);
    }

    /**
     * Returns a Single that emits {@code true} if any item emitted by the source Publisher satisfies a
     * specified condition, otherwise {@code false}. <em>Note:</em> this always emits {@code false} if the
     * source Publisher is empty.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/exists.png" alt="">
     * <p>
     * In Rx.Net this is the {@code any} Subscriber but we renamed it in RxJava to better match Java naming
     * idioms.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code any} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param predicate
     *            the condition to test items emitted by the source Publisher
     * @return a Single that emits a Boolean that indicates whether any item emitted by the source
     *         Publisher satisfies the {@code predicate}
     * @see <a href="http://reactivex.io/documentation/operators/contains.html">ReactiveX operators documentation: Contains</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<Boolean> any(Predicate<? super T> predicate) {
        ObjectHelper.requireNonNull(predicate, "predicate is null");
        return RxJavaPlugins.onAssembly(new FlowableAnySingle<T>(this, predicate));
    }

    /**
     * Calls the specified converter function during assembly time and returns its resulting value.
     * <p>
     * This allows fluent conversion to any other type.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The backpressure behavior depends on what happens in the {@code converter} function.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code as} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the resulting object type
     * @param converter the function that receives the current Flowable instance and returns a value
     * @return the converted value
     * @throws NullPointerException if converter is null
     * @since 2.1.7 - experimental
     */
    @Experimental
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.SPECIAL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> R as(@NonNull FlowableConverter<T, ? extends R> converter) {
        return ObjectHelper.requireNonNull(converter, "converter is null").apply(this);
    }

    /**
     * Returns the first item emitted by this {@code Flowable}, or throws
     * {@code NoSuchElementException} if it emits no items.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Flowable} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingFirst} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return the first item emitted by this {@code Flowable}
     * @throws NoSuchElementException
     *             if this {@code Flowable} emits no items
     * @see <a href="http://reactivex.io/documentation/operators/first.html">ReactiveX documentation: First</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final T blockingFirst() {
        BlockingFirstSubscriber<T> s = new BlockingFirstSubscriber<T>();
        subscribe(s);
        T v = s.blockingGet();
        if (v != null) {
            return v;
        }
        throw new NoSuchElementException();
    }

    /**
     * Returns the first item emitted by this {@code Flowable}, or a default value if it emits no
     * items.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Flowable} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingFirst} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param defaultItem
     *            a default value to return if this {@code Flowable} emits no items
     * @return the first item emitted by this {@code Flowable}, or the default value if it emits no
     *         items
     * @see <a href="http://reactivex.io/documentation/operators/first.html">ReactiveX documentation: First</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final T blockingFirst(T defaultItem) {
        BlockingFirstSubscriber<T> s = new BlockingFirstSubscriber<T>();
        subscribe(s);
        T v = s.blockingGet();
        return v != null ? v : defaultItem;
    }

    /**
     * Invokes a method on each item emitted by this {@code Flowable} and blocks until the Flowable
     * completes.
     * <p>
     * <em>Note:</em> This will block even if the underlying Flowable is asynchronous.
     * <p>
     * <img width="640" height="330" src="https://github.com/ReactiveX/RxJava/wiki/images/rx-operators/B.forEach.png" alt="">
     * <p>
     * This is similar to {@link Flowable#subscribe(Subscriber)}, but it blocks. Because it blocks it does not
     * need the {@link Subscriber#onComplete()} or {@link Subscriber#onError(Throwable)} methods. If the
     * underlying Flowable terminates with an error, rather than calling {@code onError}, this method will
     * throw an exception.
     *
     * <p>The difference between this method and {@link #subscribe(Consumer)} is that the {@code onNext} action
     * is executed on the emission thread instead of the current thread.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Flowable} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingForEach} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onNext
     *            the {@link Consumer} to invoke for each item emitted by the {@code Flowable}
     * @throws RuntimeException
     *             if an error occurs
     * @see <a href="http://reactivex.io/documentation/operators/subscribe.html">ReactiveX documentation: Subscribe</a>
     * @see #subscribe(Consumer)
     */
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final void blockingForEach(Consumer<? super T> onNext) {
        Iterator<T> it = blockingIterable().iterator();
        while (it.hasNext()) {
            try {
                onNext.accept(it.next());
            } catch (Throwable e) {
                Exceptions.throwIfFatal(e);
                ((Disposable)it).dispose();
                throw ExceptionHelper.wrapOrThrow(e);
            }
        }
    }

    /**
     * Converts this {@code Flowable} into an {@link Iterable}.
     * <p>
     * <img width="640" height="315" src="https://github.com/ReactiveX/RxJava/wiki/images/rx-operators/B.toIterable.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects the upstream to honor backpressure otherwise the returned
     *  Iterable's iterator will throw a {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingIterable} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return an {@link Iterable} version of this {@code Flowable}
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX documentation: To</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Iterable<T> blockingIterable() {
        return blockingIterable(bufferSize());
    }

    /**
     * Converts this {@code Flowable} into an {@link Iterable}.
     * <p>
     * <img width="640" height="315" src="https://github.com/ReactiveX/RxJava/wiki/images/rx-operators/B.toIterable.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects the upstream to honor backpressure otherwise the returned
     *  Iterable's iterator will throw a {@code MissingBackpressureException}.
     *  </dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingIterable} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param bufferSize the number of items to prefetch from the current Flowable
     * @return an {@link Iterable} version of this {@code Flowable}
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX documentation: To</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Iterable<T> blockingIterable(int bufferSize) {
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return new BlockingFlowableIterable<T>(this, bufferSize);
    }

    /**
     * Returns the last item emitted by this {@code Flowable}, or throws
     * {@code NoSuchElementException} if this {@code Flowable} emits no items.
     * <p>
     * <img width="640" height="315" src="https://github.com/ReactiveX/RxJava/wiki/images/rx-operators/B.last.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Flowable} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingLast} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return the last item emitted by this {@code Flowable}
     * @throws NoSuchElementException
     *             if this {@code Flowable} emits no items
     * @see <a href="http://reactivex.io/documentation/operators/last.html">ReactiveX documentation: Last</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final T blockingLast() {
        BlockingLastSubscriber<T> s = new BlockingLastSubscriber<T>();
        subscribe(s);
        T v = s.blockingGet();
        if (v != null) {
            return v;
        }
        throw new NoSuchElementException();
    }

    /**
     * Returns the last item emitted by this {@code Flowable}, or a default value if it emits no
     * items.
     * <p>
     * <img width="640" height="310" src="https://github.com/ReactiveX/RxJava/wiki/images/rx-operators/B.lastOrDefault.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Flowable} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingLast} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param defaultItem
     *            a default value to return if this {@code Flowable} emits no items
     * @return the last item emitted by the {@code Flowable}, or the default value if it emits no
     *         items
     * @see <a href="http://reactivex.io/documentation/operators/last.html">ReactiveX documentation: Last</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final T blockingLast(T defaultItem) {
        BlockingLastSubscriber<T> s = new BlockingLastSubscriber<T>();
        subscribe(s);
        T v = s.blockingGet();
        return v != null ? v : defaultItem;
    }

    /**
     * Returns an {@link Iterable} that returns the latest item emitted by this {@code Flowable},
     * waiting if necessary for one to become available.
     * <p>
     * If this {@code Flowable} produces items faster than {@code Iterator.next} takes them,
     * {@code onNext} events might be skipped, but {@code onError} or {@code onComplete} events are not.
     * <p>
     * Note also that an {@code onNext} directly followed by {@code onComplete} might hide the {@code onNext}
     * event.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Flowable} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingLatest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return an Iterable that always returns the latest item emitted by this {@code Flowable}
     * @see <a href="http://reactivex.io/documentation/operators/first.html">ReactiveX documentation: First</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Iterable<T> blockingLatest() {
        return new BlockingFlowableLatest<T>(this);
    }

    /**
     * Returns an {@link Iterable} that always returns the item most recently emitted by this
     * {@code Flowable}.
     * <p>
     * <img width="640" height="490" src="https://github.com/ReactiveX/RxJava/wiki/images/rx-operators/B.mostRecent.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Flowable} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingMostRecent} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param initialItem
     *            the initial item that the {@link Iterable} sequence will yield if this
     *            {@code Flowable} has not yet emitted an item
     * @return an {@link Iterable} that on each iteration returns the item that this {@code Flowable}
     *         has most recently emitted
     * @see <a href="http://reactivex.io/documentation/operators/first.html">ReactiveX documentation: First</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Iterable<T> blockingMostRecent(T initialItem) {
        return new BlockingFlowableMostRecent<T>(this, initialItem);
    }

    /**
     * Returns an {@link Iterable} that blocks until this {@code Flowable} emits another item, then
     * returns that item.
     * <p>
     * <img width="640" height="490" src="https://github.com/ReactiveX/RxJava/wiki/images/rx-operators/B.next.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Flowable} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingNext} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return an {@link Iterable} that blocks upon each iteration until this {@code Flowable} emits
     *         a new item, whereupon the Iterable returns that item
     * @see <a href="http://reactivex.io/documentation/operators/takelast.html">ReactiveX documentation: TakeLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Iterable<T> blockingNext() {
        return new BlockingFlowableNext<T>(this);
    }

    /**
     * If this {@code Flowable} completes after emitting a single item, return that item, otherwise
     * throw a {@code NoSuchElementException}.
     * <p>
     * <img width="640" height="315" src="https://github.com/ReactiveX/RxJava/wiki/images/rx-operators/B.single.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Flowable} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingSingle} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return the single item emitted by this {@code Flowable}
     * @see <a href="http://reactivex.io/documentation/operators/first.html">ReactiveX documentation: First</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final T blockingSingle() {
        return singleOrError().blockingGet();
    }

    /**
     * If this {@code Flowable} completes after emitting a single item, return that item; if it emits
     * more than one item, throw an {@code IllegalArgumentException}; if it emits no items, return a default
     * value.
     * <p>
     * <img width="640" height="315" src="https://github.com/ReactiveX/RxJava/wiki/images/rx-operators/B.singleOrDefault.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Flowable} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingSingle} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param defaultItem
     *            a default value to return if this {@code Flowable} emits no items
     * @return the single item emitted by this {@code Flowable}, or the default value if it emits no
     *         items
     * @see <a href="http://reactivex.io/documentation/operators/first.html">ReactiveX documentation: First</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final T blockingSingle(T defaultItem) {
        return single(defaultItem).blockingGet();
    }

    /**
     * Returns a {@link Future} representing the single value emitted by this {@code Flowable}.
     * <p>
     * If the {@link Flowable} emits more than one item, {@link java.util.concurrent.Future} will receive an
     * {@link java.lang.IllegalArgumentException}. If the {@link Flowable} is empty, {@link java.util.concurrent.Future}
     * will receive an {@link java.util.NoSuchElementException}.
     * <p>
     * If the {@code Flowable} may emit more than one item, use {@code Flowable.toList().toFuture()}.
     * <p>
     * <img width="640" height="395" src="https://github.com/ReactiveX/RxJava/wiki/images/rx-operators/B.toFuture.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Flowable} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toFuture} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a {@link Future} that expects a single item to be emitted by this {@code Flowable}
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX documentation: To</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Future<T> toFuture() {
        return subscribeWith(new FutureSubscriber<T>());
    }

    /**
     * Runs the source observable to a terminal event, ignoring any values and rethrowing any exception.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Flowable} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingSubscribe} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @since 2.0
     */
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final void blockingSubscribe() {
        FlowableBlockingSubscribe.subscribe(this);
    }

    /**
     * Subscribes to the source and calls the given callbacks <strong>on the current thread</strong>.
     * <p>
     * If the Flowable emits an error, it is wrapped into an
     * {@link io.reactivex.exceptions.OnErrorNotImplementedException OnErrorNotImplementedException}
     * and routed to the RxJavaPlugins.onError handler.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Flowable} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingSubscribe} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param onNext the callback action for each source value
     * @since 2.0
     */
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final void blockingSubscribe(Consumer<? super T> onNext) {
        FlowableBlockingSubscribe.subscribe(this, onNext, Functions.ON_ERROR_MISSING, Functions.EMPTY_ACTION);
    }

    /**
     * Subscribes to the source and calls the given callbacks <strong>on the current thread</strong>.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Flowable} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingSubscribe} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param onNext the callback action for each source value
     * @param onError the callback action for an error event
     * @since 2.0
     */
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final void blockingSubscribe(Consumer<? super T> onNext, Consumer<? super Throwable> onError) {
        FlowableBlockingSubscribe.subscribe(this, onNext, onError, Functions.EMPTY_ACTION);
    }


    /**
     * Subscribes to the source and calls the given callbacks <strong>on the current thread</strong>.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Flowable} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingSubscribe} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param onNext the callback action for each source value
     * @param onError the callback action for an error event
     * @param onComplete the callback action for the completion event.
     * @since 2.0
     */
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final void blockingSubscribe(Consumer<? super T> onNext, Consumer<? super Throwable> onError, Action onComplete) {
        FlowableBlockingSubscribe.subscribe(this, onNext, onError, onComplete);
    }

    /**
     * Subscribes to the source and calls the Subscriber methods <strong>on the current thread</strong>.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The supplied {@code Subscriber} determines how backpressure is applied.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code blockingSubscribe} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * The cancellation and backpressure is composed through.
     * @param subscriber the subscriber to forward events and calls to in the current thread
     * @since 2.0
     */
    @BackpressureSupport(BackpressureKind.SPECIAL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final void blockingSubscribe(Subscriber<? super T> subscriber) {
        FlowableBlockingSubscribe.subscribe(this, subscriber);
    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping buffers, each containing {@code count} items. When the source
     * Publisher completes or encounters an error, the resulting Publisher emits the current buffer and
     * propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer3.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and expects the source {@code Publisher} to honor it as
     *  well, although not enforced; violation <em>may</em> lead to {@code MissingBackpressureException} somewhere
     *  downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code buffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param count
     *            the maximum number of items in each buffer before it should be emitted
     * @return a Flowable that emits connected, non-overlapping buffers, each containing at most
     *         {@code count} items from the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<List<T>> buffer(int count) {
        return buffer(count, count);
    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher emits buffers every {@code skip} items, each containing {@code count} items. When the source
     * Publisher completes or encounters an error, the resulting Publisher emits the current buffer and
     * propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer4.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and expects the source {@code Publisher} to honor it as
     *  well, although not enforced; violation <em>may</em> lead to {@code MissingBackpressureException} somewhere
     *  downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code buffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param count
     *            the maximum size of each buffer before it should be emitted
     * @param skip
     *            how many items emitted by the source Publisher should be skipped before starting a new
     *            buffer. Note that when {@code skip} and {@code count} are equal, this is the same operation as
     *            {@link #buffer(int)}.
     * @return a Flowable that emits buffers for every {@code skip} item from the source Publisher and
     *         containing at most {@code count} items
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<List<T>> buffer(int count, int skip) {
        return buffer(count, skip, ArrayListSupplier.<T>asCallable());
    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher emits buffers every {@code skip} items, each containing {@code count} items. When the source
     * Publisher completes or encounters an error, the resulting Publisher emits the current buffer and
     * propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer4.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and expects the source {@code Publisher} to honor it as
     *  well, although not enforced; violation <em>may</em> lead to {@code MissingBackpressureException} somewhere
     *  downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code buffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the collection subclass type to buffer into
     * @param count
     *            the maximum size of each buffer before it should be emitted
     * @param skip
     *            how many items emitted by the source Publisher should be skipped before starting a new
     *            buffer. Note that when {@code skip} and {@code count} are equal, this is the same operation as
     *            {@link #buffer(int)}.
     * @param bufferSupplier
     *            a factory function that returns an instance of the collection subclass to be used and returned
     *            as the buffer
     * @return a Flowable that emits buffers for every {@code skip} item from the source Publisher and
     *         containing at most {@code count} items
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U extends Collection<? super T>> Flowable<U> buffer(int count, int skip, Callable<U> bufferSupplier) {
        ObjectHelper.verifyPositive(count, "count");
        ObjectHelper.verifyPositive(skip, "skip");
        ObjectHelper.requireNonNull(bufferSupplier, "bufferSupplier is null");
        return RxJavaPlugins.onAssembly(new FlowableBuffer<T, U>(this, count, skip, bufferSupplier));
    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping buffers, each containing {@code count} items. When the source
     * Publisher completes or encounters an error, the resulting Publisher emits the current buffer and
     * propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer3.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and expects the source {@code Publisher} to honor it as
     *  well, although not enforced; violation <em>may</em> lead to {@code MissingBackpressureException} somewhere
     *  downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code buffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the collection subclass type to buffer into
     * @param count
     *            the maximum number of items in each buffer before it should be emitted
     * @param bufferSupplier
     *            a factory function that returns an instance of the collection subclass to be used and returned
     *            as the buffer
     * @return a Flowable that emits connected, non-overlapping buffers, each containing at most
     *         {@code count} items from the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U extends Collection<? super T>> Flowable<U> buffer(int count, Callable<U> bufferSupplier) {
        return buffer(count, count, bufferSupplier);
    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher starts a new buffer periodically, as determined by the {@code timeskip} argument. It emits
     * each buffer after a fixed timespan, specified by the {@code timespan} argument. When the source
     * Publisher completes or encounters an error, the resulting Publisher emits the current buffer and
     * propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer7.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time. It requests {@code Long.MAX_VALUE}
     *      upstream and does not obey downstream requests.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code buffer} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each buffer collects items before it is emitted
     * @param timeskip
     *            the period of time after which a new buffer will be created
     * @param unit
     *            the unit of time that applies to the {@code timespan} and {@code timeskip} arguments
     * @return a Flowable that emits new buffers of items emitted by the source Publisher periodically after
     *         a fixed timespan has elapsed
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<List<T>> buffer(long timespan, long timeskip, TimeUnit unit) {
        return buffer(timespan, timeskip, unit, Schedulers.computation(), ArrayListSupplier.<T>asCallable());
    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher starts a new buffer periodically, as determined by the {@code timeskip} argument, and on the
     * specified {@code scheduler}. It emits each buffer after a fixed timespan, specified by the
     * {@code timespan} argument. When the source Publisher completes or encounters an error, the resulting
     * Publisher emits the current buffer and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer7.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time. It requests {@code Long.MAX_VALUE}
     *      upstream and does not obey downstream requests.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each buffer collects items before it is emitted
     * @param timeskip
     *            the period of time after which a new buffer will be created
     * @param unit
     *            the unit of time that applies to the {@code timespan} and {@code timeskip} arguments
     * @param scheduler
     *            the {@link Scheduler} to use when determining the end and start of a buffer
     * @return a Flowable that emits new buffers of items emitted by the source Publisher periodically after
     *         a fixed timespan has elapsed
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<List<T>> buffer(long timespan, long timeskip, TimeUnit unit, Scheduler scheduler) {
        return buffer(timespan, timeskip, unit, scheduler, ArrayListSupplier.<T>asCallable());
    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher starts a new buffer periodically, as determined by the {@code timeskip} argument, and on the
     * specified {@code scheduler}. It emits each buffer after a fixed timespan, specified by the
     * {@code timespan} argument. When the source Publisher completes or encounters an error, the resulting
     * Publisher emits the current buffer and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer7.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time. It requests {@code Long.MAX_VALUE}
     *      upstream and does not obey downstream requests.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param <U> the collection subclass type to buffer into
     * @param timespan
     *            the period of time each buffer collects items before it is emitted
     * @param timeskip
     *            the period of time after which a new buffer will be created
     * @param unit
     *            the unit of time that applies to the {@code timespan} and {@code timeskip} arguments
     * @param scheduler
     *            the {@link Scheduler} to use when determining the end and start of a buffer
     * @param bufferSupplier
     *            a factory function that returns an instance of the collection subclass to be used and returned
     *            as the buffer
     * @return a Flowable that emits new buffers of items emitted by the source Publisher periodically after
     *         a fixed timespan has elapsed
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final <U extends Collection<? super T>> Flowable<U> buffer(long timespan, long timeskip, TimeUnit unit,
            Scheduler scheduler, Callable<U> bufferSupplier) {
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        ObjectHelper.requireNonNull(bufferSupplier, "bufferSupplier is null");
        return RxJavaPlugins.onAssembly(new FlowableBufferTimed<T, U>(this, timespan, timeskip, unit, scheduler, bufferSupplier, Integer.MAX_VALUE, false));
    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping buffers, each of a fixed duration specified by the
     * {@code timespan} argument. When the source Publisher completes or encounters an error, the resulting
     * Publisher emits the current buffer and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer5.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time. It requests {@code Long.MAX_VALUE}
     *      upstream and does not obey downstream requests.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code buffer} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each buffer collects items before it is emitted and replaced with a new
     *            buffer
     * @param unit
     *            the unit of time that applies to the {@code timespan} argument
     * @return a Flowable that emits connected, non-overlapping buffers of items emitted by the source
     *         Publisher within a fixed duration
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<List<T>> buffer(long timespan, TimeUnit unit) {
        return buffer(timespan, unit, Schedulers.computation(), Integer.MAX_VALUE);
    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping buffers, each of a fixed duration specified by the
     * {@code timespan} argument or a maximum size specified by the {@code count} argument (whichever is reached
     * first). When the source Publisher completes or encounters an error, the resulting Publisher emits the
     * current buffer and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer6.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time. It requests {@code Long.MAX_VALUE}
     *      upstream and does not obey downstream requests.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code buffer} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each buffer collects items before it is emitted and replaced with a new
     *            buffer
     * @param unit
     *            the unit of time which applies to the {@code timespan} argument
     * @param count
     *            the maximum size of each buffer before it is emitted
     * @return a Flowable that emits connected, non-overlapping buffers of items emitted by the source
     *         Publisher, after a fixed duration or when the buffer reaches maximum capacity (whichever occurs
     *         first)
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<List<T>> buffer(long timespan, TimeUnit unit, int count) {
        return buffer(timespan, unit, Schedulers.computation(), count);
    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping buffers, each of a fixed duration specified by the
     * {@code timespan} argument as measured on the specified {@code scheduler}, or a maximum size specified by
     * the {@code count} argument (whichever is reached first). When the source Publisher completes or
     * encounters an error, the resulting Publisher emits the current buffer and propagates the notification
     * from the source Publisher.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer6.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time. It requests {@code Long.MAX_VALUE}
     *      upstream and does not obey downstream requests.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each buffer collects items before it is emitted and replaced with a new
     *            buffer
     * @param unit
     *            the unit of time which applies to the {@code timespan} argument
     * @param scheduler
     *            the {@link Scheduler} to use when determining the end and start of a buffer
     * @param count
     *            the maximum size of each buffer before it is emitted
     * @return a Flowable that emits connected, non-overlapping buffers of items emitted by the source
     *         Publisher after a fixed duration or when the buffer reaches maximum capacity (whichever occurs
     *         first)
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<List<T>> buffer(long timespan, TimeUnit unit, Scheduler scheduler, int count) {
        return buffer(timespan, unit, scheduler, count, ArrayListSupplier.<T>asCallable(), false);
    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping buffers, each of a fixed duration specified by the
     * {@code timespan} argument as measured on the specified {@code scheduler}, or a maximum size specified by
     * the {@code count} argument (whichever is reached first). When the source Publisher completes or
     * encounters an error, the resulting Publisher emits the current buffer and propagates the notification
     * from the source Publisher.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer6.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time. It requests {@code Long.MAX_VALUE}
     *      upstream and does not obey downstream requests.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param <U> the collection subclass type to buffer into
     * @param timespan
     *            the period of time each buffer collects items before it is emitted and replaced with a new
     *            buffer
     * @param unit
     *            the unit of time which applies to the {@code timespan} argument
     * @param scheduler
     *            the {@link Scheduler} to use when determining the end and start of a buffer
     * @param count
     *            the maximum size of each buffer before it is emitted
     * @param bufferSupplier
     *            a factory function that returns an instance of the collection subclass to be used and returned
     *            as the buffer
     * @param restartTimerOnMaxSize if true the time window is restarted when the max capacity of the current buffer
     *            is reached
     * @return a Flowable that emits connected, non-overlapping buffers of items emitted by the source
     *         Publisher after a fixed duration or when the buffer reaches maximum capacity (whichever occurs
     *         first)
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final <U extends Collection<? super T>> Flowable<U> buffer(
            long timespan, TimeUnit unit,
            Scheduler scheduler, int count,
            Callable<U> bufferSupplier,
            boolean restartTimerOnMaxSize) {
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        ObjectHelper.requireNonNull(bufferSupplier, "bufferSupplier is null");
        ObjectHelper.verifyPositive(count, "count");
        return RxJavaPlugins.onAssembly(new FlowableBufferTimed<T, U>(this, timespan, timespan, unit, scheduler, bufferSupplier, count, restartTimerOnMaxSize));
    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping buffers, each of a fixed duration specified by the
     * {@code timespan} argument and on the specified {@code scheduler}. When the source Publisher completes or
     * encounters an error, the resulting Publisher emits the current buffer and propagates the notification
     * from the source Publisher.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer5.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time. It requests {@code Long.MAX_VALUE}
     *      upstream and does not obey downstream requests.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each buffer collects items before it is emitted and replaced with a new
     *            buffer
     * @param unit
     *            the unit of time which applies to the {@code timespan} argument
     * @param scheduler
     *            the {@link Scheduler} to use when determining the end and start of a buffer
     * @return a Flowable that emits connected, non-overlapping buffers of items emitted by the source
     *         Publisher within a fixed duration
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<List<T>> buffer(long timespan, TimeUnit unit, Scheduler scheduler) {
        return buffer(timespan, unit, scheduler, Integer.MAX_VALUE, ArrayListSupplier.<T>asCallable(), false);
    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher emits buffers that it creates when the specified {@code openingIndicator} Publisher emits an
     * item, and closes when the Publisher returned from {@code closingIndicator} emits an item.
     * <p>
     * <img width="640" height="470" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer2.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it is instead controlled by the given Publishers and
     *      buffers data. It requests {@code Long.MAX_VALUE} upstream and does not obey downstream requests.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code buffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <TOpening> the element type of the buffer-opening Publisher
     * @param <TClosing> the element type of the individual buffer-closing Publishers
     * @param openingIndicator
     *            the Publisher that, when it emits an item, causes a new buffer to be created
     * @param closingIndicator
     *            the {@link Function} that is used to produce a Publisher for every buffer created. When this
     *            Publisher emits an item, the associated buffer is emitted.
     * @return a Flowable that emits buffers, containing items from the source Publisher, that are created
     *         and closed when the specified Publishers emit items
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <TOpening, TClosing> Flowable<List<T>> buffer(
            Flowable<? extends TOpening> openingIndicator,
            Function<? super TOpening, ? extends Publisher<? extends TClosing>> closingIndicator) {
        return buffer(openingIndicator, closingIndicator, ArrayListSupplier.<T>asCallable());
    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher emits buffers that it creates when the specified {@code openingIndicator} Publisher emits an
     * item, and closes when the Publisher returned from {@code closingIndicator} emits an item.
     * <p>
     * <img width="640" height="470" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer2.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it is instead controlled by the given Publishers and
     *      buffers data. It requests {@code Long.MAX_VALUE} upstream and does not obey downstream requests.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code buffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the collection subclass type to buffer into
     * @param <TOpening> the element type of the buffer-opening Publisher
     * @param <TClosing> the element type of the individual buffer-closing Publishers
     * @param openingIndicator
     *            the Publisher that, when it emits an item, causes a new buffer to be created
     * @param closingIndicator
     *            the {@link Function} that is used to produce a Publisher for every buffer created. When this
     *            Publisher emits an item, the associated buffer is emitted.
     * @param bufferSupplier
     *            a factory function that returns an instance of the collection subclass to be used and returned
     *            as the buffer
     * @return a Flowable that emits buffers, containing items from the source Publisher, that are created
     *         and closed when the specified Publishers emit items
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <TOpening, TClosing, U extends Collection<? super T>> Flowable<U> buffer(
            Flowable<? extends TOpening> openingIndicator,
            Function<? super TOpening, ? extends Publisher<? extends TClosing>> closingIndicator,
            Callable<U> bufferSupplier) {
        ObjectHelper.requireNonNull(openingIndicator, "openingIndicator is null");
        ObjectHelper.requireNonNull(closingIndicator, "closingIndicator is null");
        ObjectHelper.requireNonNull(bufferSupplier, "bufferSupplier is null");
        return RxJavaPlugins.onAssembly(new FlowableBufferBoundary<T, U, TOpening, TClosing>(this, openingIndicator, closingIndicator, bufferSupplier));
    }

    /**
     * Returns a Flowable that emits non-overlapping buffered items from the source Publisher each time the
     * specified boundary Publisher emits an item.
     * <p>
     * <img width="640" height="395" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer8.png" alt="">
     * <p>
     * Completion of either the source or the boundary Publisher causes the returned Publisher to emit the
     * latest buffer and complete.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it is instead controlled by the {@code Publisher}
     *      {@code boundary} and buffers data. It requests {@code Long.MAX_VALUE} upstream and does not obey
     *      downstream requests.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code buffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <B>
     *            the boundary value type (ignored)
     * @param boundaryIndicator
     *            the boundary Publisher
     * @return a Flowable that emits buffered items from the source Publisher when the boundary Publisher
     *         emits an item
     * @see #buffer(Publisher, int)
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <B> Flowable<List<T>> buffer(Publisher<B> boundaryIndicator) {
        return buffer(boundaryIndicator, ArrayListSupplier.<T>asCallable());
    }

    /**
     * Returns a Flowable that emits non-overlapping buffered items from the source Publisher each time the
     * specified boundary Publisher emits an item.
     * <p>
     * <img width="640" height="395" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer8.png" alt="">
     * <p>
     * Completion of either the source or the boundary Publisher causes the returned Publisher to emit the
     * latest buffer and complete.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it is instead controlled by the {@code Publisher}
     *      {@code boundary} and buffers data. It requests {@code Long.MAX_VALUE} upstream and does not obey
     *      downstream requests.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code buffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <B>
     *            the boundary value type (ignored)
     * @param boundaryIndicator
     *            the boundary Publisher
     * @param initialCapacity
     *            the initial capacity of each buffer chunk
     * @return a Flowable that emits buffered items from the source Publisher when the boundary Publisher
     *         emits an item
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     * @see #buffer(Publisher)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <B> Flowable<List<T>> buffer(Publisher<B> boundaryIndicator, final int initialCapacity) {
        ObjectHelper.verifyPositive(initialCapacity, "initialCapacity");
        return buffer(boundaryIndicator, Functions.<T>createArrayList(initialCapacity));
    }

    /**
     * Returns a Flowable that emits non-overlapping buffered items from the source Publisher each time the
     * specified boundary Publisher emits an item.
     * <p>
     * <img width="640" height="395" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer8.png" alt="">
     * <p>
     * Completion of either the source or the boundary Publisher causes the returned Publisher to emit the
     * latest buffer and complete.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it is instead controlled by the {@code Publisher}
     *      {@code boundary} and buffers data. It requests {@code Long.MAX_VALUE} upstream and does not obey
     *      downstream requests.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code buffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the collection subclass type to buffer into
     * @param <B>
     *            the boundary value type (ignored)
     * @param boundaryIndicator
     *            the boundary Publisher
     * @param bufferSupplier
     *            a factory function that returns an instance of the collection subclass to be used and returned
     *            as the buffer
     * @return a Flowable that emits buffered items from the source Publisher when the boundary Publisher
     *         emits an item
     * @see #buffer(Publisher, int)
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <B, U extends Collection<? super T>> Flowable<U> buffer(Publisher<B> boundaryIndicator, Callable<U> bufferSupplier) {
        ObjectHelper.requireNonNull(boundaryIndicator, "boundaryIndicator is null");
        ObjectHelper.requireNonNull(bufferSupplier, "bufferSupplier is null");
        return RxJavaPlugins.onAssembly(new FlowableBufferExactBoundary<T, U, B>(this, boundaryIndicator, bufferSupplier));
    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping buffers. It emits the current buffer and replaces it with a
     * new buffer whenever the Publisher produced by the specified {@code closingIndicator} emits an item.
     * <p>
     * <img width="640" height="395" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer1.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it is instead controlled by the given Publishers and
     *      buffers data. It requests {@code Long.MAX_VALUE} upstream and does not obey downstream requests.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code buffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <B> the value type of the boundary-providing Publisher
     * @param boundaryIndicatorSupplier
     *            a {@link Callable} that produces a Publisher that governs the boundary between buffers.
     *            Whenever the source {@code Publisher} emits an item, {@code buffer} emits the current buffer and
     *            begins to fill a new one
     * @return a Flowable that emits a connected, non-overlapping buffer of items from the source Publisher
     *         each time the Publisher created with the {@code closingIndicator} argument emits an item
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <B> Flowable<List<T>> buffer(Callable<? extends Publisher<B>> boundaryIndicatorSupplier) {
        return buffer(boundaryIndicatorSupplier, ArrayListSupplier.<T>asCallable());

    }

    /**
     * Returns a Flowable that emits buffers of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping buffers. It emits the current buffer and replaces it with a
     * new buffer whenever the Publisher produced by the specified {@code closingIndicator} emits an item.
     * <p>
     * <img width="640" height="395" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/buffer1.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it is instead controlled by the given Publishers and
     *      buffers data. It requests {@code Long.MAX_VALUE} upstream and does not obey downstream requests.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code buffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the collection subclass type to buffer into
     * @param <B> the value type of the boundary-providing Publisher
     * @param boundaryIndicatorSupplier
     *            a {@link Callable} that produces a Publisher that governs the boundary between buffers.
     *            Whenever the source {@code Publisher} emits an item, {@code buffer} emits the current buffer and
     *            begins to fill a new one
     * @param bufferSupplier
     *            a factory function that returns an instance of the collection subclass to be used and returned
     *            as the buffer
     * @return a Flowable that emits a connected, non-overlapping buffer of items from the source Publisher
     *         each time the Publisher created with the {@code closingIndicator} argument emits an item
     * @see <a href="http://reactivex.io/documentation/operators/buffer.html">ReactiveX operators documentation: Buffer</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <B, U extends Collection<? super T>> Flowable<U> buffer(Callable<? extends Publisher<B>> boundaryIndicatorSupplier,
            Callable<U> bufferSupplier) {
        ObjectHelper.requireNonNull(boundaryIndicatorSupplier, "boundaryIndicatorSupplier is null");
        ObjectHelper.requireNonNull(bufferSupplier, "bufferSupplier is null");
        return RxJavaPlugins.onAssembly(new FlowableBufferBoundarySupplier<T, U, B>(this, boundaryIndicatorSupplier, bufferSupplier));
    }

    /**
     * Returns a Flowable that subscribes to this Publisher lazily, caches all of its events
     * and replays them, in the same order as received, to all the downstream subscribers.
     * <p>
     * <img width="640" height="410" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/cache.png" alt="">
     * <p>
     * This is useful when you want a Publisher to cache responses and you can't control the
     * subscribe/cancel behavior of all the {@link Subscriber}s.
     * <p>
     * The operator subscribes only when the first downstream subscriber subscribes and maintains
     * a single subscription towards this Publisher. In contrast, the operator family of {@link #replay()}
     * that return a {@link ConnectableFlowable} require an explicit call to {@link ConnectableFlowable#connect()}.
     * <p>
     * <em>Note:</em> You sacrifice the ability to cancel the origin when you use the {@code cache}
     * Subscriber so be careful not to use this Subscriber on Publishers that emit an infinite or very large number
     * of items that will use up memory.
     * A possible workaround is to apply `takeUntil` with a predicate or
     * another source before (and perhaps after) the application of cache().
     * <pre><code>
     * AtomicBoolean shouldStop = new AtomicBoolean();
     *
     * source.takeUntil(v -&gt; shouldStop.get())
     *       .cache()
     *       .takeUntil(v -&gt; shouldStop.get())
     *       .subscribe(...);
     * </code></pre>
     * Since the operator doesn't allow clearing the cached values either, the possible workaround is
     * to forget all references to it via {@link #onTerminateDetach()} applied along with the previous
     * workaround:
     * <pre><code>
     * AtomicBoolean shouldStop = new AtomicBoolean();
     *
     * source.takeUntil(v -&gt; shouldStop.get())
     *       .onTerminateDetach()
     *       .cache()
     *       .takeUntil(v -&gt; shouldStop.get())
     *       .onTerminateDetach()
     *       .subscribe(...);
     * </code></pre>
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes this Publisher in an unbounded fashion but respects the backpressure
     *  of each downstream Subscriber individually.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code cache} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a Flowable that, when first subscribed to, caches all of its items and notifications for the
     *         benefit of subsequent subscribers
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> cache() {
        return cacheWithInitialCapacity(16);
    }

    /**
     * Returns a Flowable that subscribes to this Publisher lazily, caches all of its events
     * and replays them, in the same order as received, to all the downstream subscribers.
     * <p>
     * <img width="640" height="410" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/cache.png" alt="">
     * <p>
     * This is useful when you want a Publisher to cache responses and you can't control the
     * subscribe/cancel behavior of all the {@link Subscriber}s.
     * <p>
     * The operator subscribes only when the first downstream subscriber subscribes and maintains
     * a single subscription towards this Publisher. In contrast, the operator family of {@link #replay()}
     * that return a {@link ConnectableFlowable} require an explicit call to {@link ConnectableFlowable#connect()}.
     * <p>
     * <em>Note:</em> You sacrifice the ability to cancel the origin when you use the {@code cache}
     * Subscriber so be careful not to use this Subscriber on Publishers that emit an infinite or very large number
     * of items that will use up memory.
     * A possible workaround is to apply `takeUntil` with a predicate or
     * another source before (and perhaps after) the application of cache().
     * <pre><code>
     * AtomicBoolean shouldStop = new AtomicBoolean();
     *
     * source.takeUntil(v -&gt; shouldStop.get())
     *       .cache()
     *       .takeUntil(v -&gt; shouldStop.get())
     *       .subscribe(...);
     * </code></pre>
     * Since the operator doesn't allow clearing the cached values either, the possible workaround is
     * to forget all references to it via {@link #onTerminateDetach()} applied along with the previous
     * workaround:
     * <pre><code>
     * AtomicBoolean shouldStop = new AtomicBoolean();
     *
     * source.takeUntil(v -&gt; shouldStop.get())
     *       .onTerminateDetach()
     *       .cache()
     *       .takeUntil(v -&gt; shouldStop.get())
     *       .onTerminateDetach()
     *       .subscribe(...);
     * </code></pre>
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes this Publisher in an unbounded fashion but respects the backpressure
     *  of each downstream Subscriber individually.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code cacheWithInitialCapacity} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * <p>
     * <em>Note:</em> The capacity hint is not an upper bound on cache size. For that, consider
     * {@link #replay(int)} in combination with {@link ConnectableFlowable#autoConnect()} or similar.
     *
     * @param initialCapacity hint for number of items to cache (for optimizing underlying data structure)
     * @return a Flowable that, when first subscribed to, caches all of its items and notifications for the
     *         benefit of subsequent subscribers
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> cacheWithInitialCapacity(int initialCapacity) {
        ObjectHelper.verifyPositive(initialCapacity, "initialCapacity");
        return RxJavaPlugins.onAssembly(new FlowableCache<T>(this, initialCapacity));
    }

    /**
     * Returns a Flowable that emits the items emitted by the source Publisher, converted to the specified
     * type.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/cast.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code cast} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the output value type cast to
     * @param clazz
     *            the target class type that {@code cast} will cast the items emitted by the source Publisher
     *            into before emitting them from the resulting Publisher
     * @return a Flowable that emits each item from the source Publisher after converting it to the
     *         specified type
     * @see <a href="http://reactivex.io/documentation/operators/map.html">ReactiveX operators documentation: Map</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U> Flowable<U> cast(final Class<U> clazz) {
        ObjectHelper.requireNonNull(clazz, "clazz is null");
        return map(Functions.castFunction(clazz));
    }

    /**
     * Collects items emitted by the source Publisher into a single mutable data structure and returns
     * a Single that emits this structure.
     * <p>
     * <img width="640" height="330" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/collect.png" alt="">
     * <p>
     * This is a simplified version of {@code reduce} that does not need to return the state on each pass.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure because by intent it will receive all values and reduce
     *      them to a single {@code onNext}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code collect} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the accumulator and output type
     * @param initialItemSupplier
     *           the mutable data structure that will collect the items
     * @param collector
     *           a function that accepts the {@code state} and an emitted item, and modifies {@code state}
     *           accordingly
     * @return a Single that emits the result of collecting the values emitted by the source Publisher
     *         into a single mutable data structure
     * @see <a href="http://reactivex.io/documentation/operators/reduce.html">ReactiveX operators documentation: Reduce</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U> Single<U> collect(Callable<? extends U> initialItemSupplier, BiConsumer<? super U, ? super T> collector) {
        ObjectHelper.requireNonNull(initialItemSupplier, "initialItemSupplier is null");
        ObjectHelper.requireNonNull(collector, "collector is null");
        return RxJavaPlugins.onAssembly(new FlowableCollectSingle<T, U>(this, initialItemSupplier, collector));
    }

    /**
     * Collects items emitted by the source Publisher into a single mutable data structure and returns
     * a Single that emits this structure.
     * <p>
     * <img width="640" height="330" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/collect.png" alt="">
     * <p>
     * This is a simplified version of {@code reduce} that does not need to return the state on each pass.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure because by intent it will receive all values and reduce
     *      them to a single {@code onNext}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code collectInto} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the accumulator and output type
     * @param initialItem
     *           the mutable data structure that will collect the items
     * @param collector
     *           a function that accepts the {@code state} and an emitted item, and modifies {@code state}
     *           accordingly
     * @return a Single that emits the result of collecting the values emitted by the source Publisher
     *         into a single mutable data structure
     * @see <a href="http://reactivex.io/documentation/operators/reduce.html">ReactiveX operators documentation: Reduce</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U> Single<U> collectInto(final U initialItem, BiConsumer<? super U, ? super T> collector) {
        ObjectHelper.requireNonNull(initialItem, "initialItem is null");
        return collect(Functions.justCallable(initialItem), collector);
    }

    /**
     * Transform a Publisher by applying a particular Transformer function to it.
     * <p>
     * This method operates on the Publisher itself whereas {@link #lift} operates on the Publisher's
     * Subscribers or Subscribers.
     * <p>
     * If the operator you are creating is designed to act on the individual items emitted by a source
     * Publisher, use {@link #lift}. If your operator is designed to transform the source Publisher as a whole
     * (for instance, by applying a particular set of existing RxJava operators to it) use {@code compose}.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator itself doesn't interfere with the backpressure behavior which only depends
     *  on what kind of {@code Publisher} the transformer returns.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code compose} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the value type of the output Publisher
     * @param composer implements the function that transforms the source Publisher
     * @return the source Publisher, transformed by the transformer function
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Implementing-Your-Own-Operators">RxJava wiki: Implementing Your Own Operators</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> compose(FlowableTransformer<? super T, ? extends R> composer) {
        return fromPublisher(((FlowableTransformer<T, R>) ObjectHelper.requireNonNull(composer, "composer is null")).apply(this));
    }

    /**
     * Returns a new Flowable that emits items resulting from applying a function that you supply to each item
     * emitted by the source Publisher, where that function returns a Publisher, and then emitting the items
     * that result from concatenating those resulting Publishers.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/concatMap.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. Both this and the inner {@code Publisher}s are
     *  expected to honor backpressure as well. If the source {@code Publisher} violates the rule, the operator will
     *  signal a {@code MissingBackpressureException}. If any of the inner {@code Publisher}s doesn't honor
     *  backpressure, that <em>may</em> throw an {@code IllegalStateException} when that
     *  {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concatMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the type of the inner Publisher sources and thus the output type
     * @param mapper
     *            a function that, when applied to an item emitted by the source Publisher, returns an
     *            Publisher
     * @return a Flowable that emits the result of applying the transformation function to each item emitted
     *         by the source Publisher and concatenating the Publishers obtained from this transformation
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> concatMap(Function<? super T, ? extends Publisher<? extends R>> mapper) {
        return concatMap(mapper, 2);
    }

    /**
     * Returns a new Flowable that emits items resulting from applying a function that you supply to each item
     * emitted by the source Publisher, where that function returns a Publisher, and then emitting the items
     * that result from concatenating those resulting Publishers.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/concatMap.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. Both this and the inner {@code Publisher}s are
     *  expected to honor backpressure as well. If the source {@code Publisher} violates the rule, the operator will
     *  signal a {@code MissingBackpressureException}. If any of the inner {@code Publisher}s doesn't honor
     *  backpressure, that <em>may</em> throw an {@code IllegalStateException} when that
     *  {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concatMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the type of the inner Publisher sources and thus the output type
     * @param mapper
     *            a function that, when applied to an item emitted by the source Publisher, returns an
     *            Publisher
     * @param prefetch
     *            the number of elements to prefetch from the current Flowable
     * @return a Flowable that emits the result of applying the transformation function to each item emitted
     *         by the source Publisher and concatenating the Publishers obtained from this transformation
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> concatMap(Function<? super T, ? extends Publisher<? extends R>> mapper, int prefetch) {
        ObjectHelper.requireNonNull(mapper, "mapper is null");
        ObjectHelper.verifyPositive(prefetch, "prefetch");
        if (this instanceof ScalarCallable) {
            @SuppressWarnings("unchecked")
            T v = ((ScalarCallable<T>)this).call();
            if (v == null) {
                return empty();
            }
            return FlowableScalarXMap.scalarXMap(v, mapper);
        }
        return RxJavaPlugins.onAssembly(new FlowableConcatMap<T, R>(this, mapper, prefetch, ErrorMode.IMMEDIATE));
    }

    /**
     * Maps each of the items into a Publisher, subscribes to them one after the other,
     * one at a time and emits their values in order
     * while delaying any error from either this or any of the inner Publishers
     * till all of them terminate.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. Both this and the inner {@code Publisher}s are
     *  expected to honor backpressure as well. If the source {@code Publisher} violates the rule, the operator will
     *  signal a {@code MissingBackpressureException}. If any of the inner {@code Publisher}s doesn't honor
     *  backpressure, that <em>may</em> throw an {@code IllegalStateException} when that
     *  {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concatMapDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the result value type
     * @param mapper the function that maps the items of this Publisher into the inner Publishers.
     * @return the new Publisher instance with the concatenation behavior
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> concatMapDelayError(Function<? super T, ? extends Publisher<? extends R>> mapper) {
        return concatMapDelayError(mapper, 2, true);
    }

    /**
     * Maps each of the items into a Publisher, subscribes to them one after the other,
     * one at a time and emits their values in order
     * while delaying any error from either this or any of the inner Publishers
     * till all of them terminate.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. Both this and the inner {@code Publisher}s are
     *  expected to honor backpressure as well. If the source {@code Publisher} violates the rule, the operator will
     *  signal a {@code MissingBackpressureException}. If any of the inner {@code Publisher}s doesn't honor
     *  backpressure, that <em>may</em> throw an {@code IllegalStateException} when that
     *  {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concatMapDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the result value type
     * @param mapper the function that maps the items of this Publisher into the inner Publishers.
     * @param prefetch
     *            the number of elements to prefetch from the current Flowable
     * @param tillTheEnd
     *            if true, all errors from the outer and inner Publisher sources are delayed until the end,
     *            if false, an error from the main source is signalled when the current Publisher source terminates
     * @return the new Publisher instance with the concatenation behavior
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> concatMapDelayError(Function<? super T, ? extends Publisher<? extends R>> mapper,
            int prefetch, boolean tillTheEnd) {
        ObjectHelper.requireNonNull(mapper, "mapper is null");
        ObjectHelper.verifyPositive(prefetch, "prefetch");
        if (this instanceof ScalarCallable) {
            @SuppressWarnings("unchecked")
            T v = ((ScalarCallable<T>)this).call();
            if (v == null) {
                return empty();
            }
            return FlowableScalarXMap.scalarXMap(v, mapper);
        }
        return RxJavaPlugins.onAssembly(new FlowableConcatMap<T, R>(this, mapper, prefetch, tillTheEnd ? ErrorMode.END : ErrorMode.BOUNDARY));
    }


    /**
     * Maps a sequence of values into Publishers and concatenates these Publishers eagerly into a single
     * Publisher.
     * <p>
     * Eager concatenation means that once a subscriber subscribes, this operator subscribes to all of the
     * source Publishers. The operator buffers the values emitted by these Publishers and then drains them in
     * order, each one after the previous one completes.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>Backpressure is honored towards the downstream, however, due to the eagerness requirement, sources
     *      are subscribed to in unbounded mode and their values are queued up in an unbounded buffer.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This method does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <R> the value type
     * @param mapper the function that maps a sequence of values into a sequence of Publishers that will be
     *               eagerly concatenated
     * @return the new Publisher instance with the specified concatenation behavior
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> concatMapEager(Function<? super T, ? extends Publisher<? extends R>> mapper) {
        return concatMapEager(mapper, bufferSize(), bufferSize());
    }

    /**
     * Maps a sequence of values into Publishers and concatenates these Publishers eagerly into a single
     * Publisher.
     * <p>
     * Eager concatenation means that once a subscriber subscribes, this operator subscribes to all of the
     * source Publishers. The operator buffers the values emitted by these Publishers and then drains them in
     * order, each one after the previous one completes.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>Backpressure is honored towards the downstream, however, due to the eagerness requirement, sources
     *      are subscribed to in unbounded mode and their values are queued up in an unbounded buffer.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This method does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <R> the value type
     * @param mapper the function that maps a sequence of values into a sequence of Publishers that will be
     *               eagerly concatenated
     * @param maxConcurrency the maximum number of concurrent subscribed Publishers
     * @param prefetch hints about the number of expected values from each inner Publisher, must be positive
     * @return the new Publisher instance with the specified concatenation behavior
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> concatMapEager(Function<? super T, ? extends Publisher<? extends R>> mapper,
            int maxConcurrency, int prefetch) {
        ObjectHelper.requireNonNull(mapper, "mapper is null");
        ObjectHelper.verifyPositive(maxConcurrency, "maxConcurrency");
        ObjectHelper.verifyPositive(prefetch, "prefetch");
        return RxJavaPlugins.onAssembly(new FlowableConcatMapEager<T, R>(this, mapper, maxConcurrency, prefetch, ErrorMode.IMMEDIATE));
    }

    /**
     * Maps a sequence of values into Publishers and concatenates these Publishers eagerly into a single
     * Publisher.
     * <p>
     * Eager concatenation means that once a subscriber subscribes, this operator subscribes to all of the
     * source Publishers. The operator buffers the values emitted by these Publishers and then drains them in
     * order, each one after the previous one completes.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>Backpressure is honored towards the downstream, however, due to the eagerness requirement, sources
     *      are subscribed to in unbounded mode and their values are queued up in an unbounded buffer.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This method does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <R> the value type
     * @param mapper the function that maps a sequence of values into a sequence of Publishers that will be
     *               eagerly concatenated
     * @param tillTheEnd
     *            if true, all errors from the outer and inner Publisher sources are delayed until the end,
     *            if false, an error from the main source is signalled when the current Publisher source terminates
     * @return the new Publisher instance with the specified concatenation behavior
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> concatMapEagerDelayError(Function<? super T, ? extends Publisher<? extends R>> mapper,
            boolean tillTheEnd) {
        return concatMapEagerDelayError(mapper, bufferSize(), bufferSize(), tillTheEnd);
    }

    /**
     * Maps a sequence of values into Publishers and concatenates these Publishers eagerly into a single
     * Publisher.
     * <p>
     * Eager concatenation means that once a subscriber subscribes, this operator subscribes to all of the
     * source Publishers. The operator buffers the values emitted by these Publishers and then drains them in
     * order, each one after the previous one completes.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>Backpressure is honored towards the downstream, however, due to the eagerness requirement, sources
     *      are subscribed to in unbounded mode and their values are queued up in an unbounded buffer.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This method does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <R> the value type
     * @param mapper the function that maps a sequence of values into a sequence of Publishers that will be
     *               eagerly concatenated
     * @param maxConcurrency the maximum number of concurrent subscribed Publishers
     * @param prefetch
     *               the number of elements to prefetch from each source Publisher
     * @param tillTheEnd
     *               if true, exceptions from the current Flowable and all the inner Publishers are delayed until
     *               all of them terminate, if false, exception from the current Flowable is delayed until the
     *               currently running Publisher terminates
     * @return the new Publisher instance with the specified concatenation behavior
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> concatMapEagerDelayError(Function<? super T, ? extends Publisher<? extends R>> mapper,
            int maxConcurrency, int prefetch, boolean tillTheEnd) {
        ObjectHelper.requireNonNull(mapper, "mapper is null");
        ObjectHelper.verifyPositive(maxConcurrency, "maxConcurrency");
        ObjectHelper.verifyPositive(prefetch, "prefetch");
        return RxJavaPlugins.onAssembly(new FlowableConcatMapEager<T, R>(this, mapper, maxConcurrency, prefetch, tillTheEnd ? ErrorMode.END : ErrorMode.BOUNDARY));
    }

    /**
     * Returns a Flowable that concatenate each item emitted by the source Publisher with the values in an
     * Iterable corresponding to that item that is generated by a selector.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s is
     *  expected to honor backpressure as well. If the source {@code Publisher} violates the rule, the operator will
     *  signal a {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concatMapIterable} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the type of item emitted by the resulting Publisher
     * @param mapper
     *            a function that returns an Iterable sequence of values for when given an item emitted by the
     *            source Publisher
     * @return a Flowable that emits the results of concatenating the items emitted by the source Publisher with
     *         the values in the Iterables corresponding to those items, as generated by {@code collectionSelector}
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U> Flowable<U> concatMapIterable(Function<? super T, ? extends Iterable<? extends U>> mapper) {
        return concatMapIterable(mapper, 2);
    }

    /**
     * Returns a Flowable that concatenate each item emitted by the source Publisher with the values in an
     * Iterable corresponding to that item that is generated by a selector.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s is
     *  expected to honor backpressure as well. If the source {@code Publisher} violates the rule, the operator will
     *  signal a {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concatMapIterable} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the type of item emitted by the resulting Publisher
     * @param mapper
     *            a function that returns an Iterable sequence of values for when given an item emitted by the
     *            source Publisher
     * @param prefetch
     *            the number of elements to prefetch from the current Flowable
     * @return a Flowable that emits the results of concatenating the items emitted by the source Publisher with
     *         the values in the Iterables corresponding to those items, as generated by {@code collectionSelector}
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U> Flowable<U> concatMapIterable(final Function<? super T, ? extends Iterable<? extends U>> mapper, int prefetch) {
        ObjectHelper.requireNonNull(mapper, "mapper is null");
        ObjectHelper.verifyPositive(prefetch, "prefetch");
        return RxJavaPlugins.onAssembly(new FlowableFlattenIterable<T, U>(this, mapper, prefetch));
    }

    /**
     * Returns a Flowable that emits the items emitted from the current Publisher, then the next, one after
     * the other, without interleaving them.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/concat.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. Both this and the {@code other} {@code Publisher}s
     *  are expected to honor backpressure as well. If any of then violates this rule, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code concatWith} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param other
     *            a Publisher to be concatenated after the current
     * @return a Flowable that emits items emitted by the two source Publishers, one after the other,
     *         without interleaving them
     * @see <a href="http://reactivex.io/documentation/operators/concat.html">ReactiveX operators documentation: Concat</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> concatWith(Publisher<? extends T> other) {
        ObjectHelper.requireNonNull(other, "other is null");
        return concat(this, other);
    }

    /**
     * Returns a Single that emits a Boolean that indicates whether the source Publisher emitted a
     * specified item.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/contains.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code contains} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param item
     *            the item to search for in the emissions from the source Publisher
     * @return a Flowable that emits {@code true} if the specified item is emitted by the source Publisher,
     *         or {@code false} if the source Publisher completes without emitting that item
     * @see <a href="http://reactivex.io/documentation/operators/contains.html">ReactiveX operators documentation: Contains</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<Boolean> contains(final Object item) {
        ObjectHelper.requireNonNull(item, "item is null");
        return any(Functions.equalsWith(item));
    }

    /**
     * Returns a Single that counts the total number of items emitted by the source Publisher and emits
     * this count as a 64-bit Long.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/longCount.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code count} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a Single that emits a single item: the number of items emitted by the source Publisher as a
     *         64-bit Long item
     * @see <a href="http://reactivex.io/documentation/operators/count.html">ReactiveX operators documentation: Count</a>
     * @see #count()
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<Long> count() {
        return RxJavaPlugins.onAssembly(new FlowableCountSingle<T>(this));
    }

    /**
     * Returns a Flowable that mirrors the source Publisher, except that it drops items emitted by the
     * source Publisher that are followed by another item within a computed debounce duration.
     * <p>
     * <img width="640" height="425" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/debounce.f.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses the {@code debounceSelector} to mark
     *      boundaries.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code debounce} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the debounce value type (ignored)
     * @param debounceIndicator
     *            function to retrieve a sequence that indicates the throttle duration for each item
     * @return a Flowable that omits items emitted by the source Publisher that are followed by another item
     *         within a computed debounce duration
     * @see <a href="http://reactivex.io/documentation/operators/debounce.html">ReactiveX operators documentation: Debounce</a>
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Backpressure">RxJava wiki: Backpressure</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U> Flowable<T> debounce(Function<? super T, ? extends Publisher<U>> debounceIndicator) {
        ObjectHelper.requireNonNull(debounceIndicator, "debounceIndicator is null");
        return RxJavaPlugins.onAssembly(new FlowableDebounce<T, U>(this, debounceIndicator));
    }

    /**
     * Returns a Flowable that mirrors the source Publisher, except that it drops items emitted by the
     * source Publisher that are followed by newer items before a timeout value expires. The timer resets on
     * each emission.
     * <p>
     * <em>Note:</em> If items keep being emitted by the source Publisher faster than the timeout then no items
     * will be emitted by the resulting Publisher.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/debounce.png" alt="">
     * <p>
     * Information on debounce vs throttle:
     * <ul>
     * <li><a href="http://drupalmotion.com/article/debounce-and-throttle-visual-explanation">Debounce and Throttle: visual explanation</a></li>
     * <li><a href="http://unscriptable.com/2009/03/20/debouncing-javascript-methods/">Debouncing: javascript methods</a></li>
     * <li><a href="http://www.illyriad.co.uk/blog/index.php/2011/09/javascript-dont-spam-your-server-debounce-and-throttle/">Javascript - don't spam your server: debounce and throttle</a></li>
     * </ul>
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time to control data flow.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code debounce} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param timeout
     *            the time each item has to be "the most recent" of those emitted by the source Publisher to
     *            ensure that it's not dropped
     * @param unit
     *            the {@link TimeUnit} for the timeout
     * @return a Flowable that filters out items from the source Publisher that are too quickly followed by
     *         newer items
     * @see <a href="http://reactivex.io/documentation/operators/debounce.html">ReactiveX operators documentation: Debounce</a>
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Backpressure">RxJava wiki: Backpressure</a>
     * @see #throttleWithTimeout(long, TimeUnit)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<T> debounce(long timeout, TimeUnit unit) {
        return debounce(timeout, unit, Schedulers.computation());
    }

    /**
     * Returns a Flowable that mirrors the source Publisher, except that it drops items emitted by the
     * source Publisher that are followed by newer items before a timeout value expires on a specified
     * Scheduler. The timer resets on each emission.
     * <p>
     * <em>Note:</em> If items keep being emitted by the source Publisher faster than the timeout then no items
     * will be emitted by the resulting Publisher.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/debounce.s.png" alt="">
     * <p>
     * Information on debounce vs throttle:
     * <ul>
     * <li><a href="http://drupalmotion.com/article/debounce-and-throttle-visual-explanation">Debounce and Throttle: visual explanation</a></li>
     * <li><a href="http://unscriptable.com/2009/03/20/debouncing-javascript-methods/">Debouncing: javascript methods</a></li>
     * <li><a href="http://www.illyriad.co.uk/blog/index.php/2011/09/javascript-dont-spam-your-server-debounce-and-throttle/">Javascript - don't spam your server: debounce and throttle</a></li>
     * </ul>
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time to control data flow.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param timeout
     *            the time each item has to be "the most recent" of those emitted by the source Publisher to
     *            ensure that it's not dropped
     * @param unit
     *            the unit of time for the specified timeout
     * @param scheduler
     *            the {@link Scheduler} to use internally to manage the timers that handle the timeout for each
     *            item
     * @return a Flowable that filters out items from the source Publisher that are too quickly followed by
     *         newer items
     * @see <a href="http://reactivex.io/documentation/operators/debounce.html">ReactiveX operators documentation: Debounce</a>
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Backpressure">RxJava wiki: Backpressure</a>
     * @see #throttleWithTimeout(long, TimeUnit, Scheduler)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> debounce(long timeout, TimeUnit unit, Scheduler scheduler) {
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return RxJavaPlugins.onAssembly(new FlowableDebounceTimed<T>(this, timeout, unit, scheduler));
    }

    /**
     * Returns a Flowable that emits the items emitted by the source Publisher or a specified default item
     * if the source Publisher is empty.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/defaultIfEmpty.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>If the source {@code Publisher} is empty, this operator is guaranteed to honor backpressure from downstream.
     *  If the source {@code Publisher} is non-empty, it is expected to honor backpressure as well; if the rule is violated,
     *  a {@code MissingBackpressureException} <em>may</em> get signalled somewhere downstream.
     *  </dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code defaultIfEmpty} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param defaultItem
     *            the item to emit if the source Publisher emits no items
     * @return a Flowable that emits either the specified default item if the source Publisher emits no
     *         items, or the items emitted by the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/defaultifempty.html">ReactiveX operators documentation: DefaultIfEmpty</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> defaultIfEmpty(T defaultItem) {
        ObjectHelper.requireNonNull(defaultItem, "item is null");
        return switchIfEmpty(just(defaultItem));
    }

    /**
     * Returns a Flowable that delays the emissions of the source Publisher via another Publisher on a
     * per-item basis.
     * <p>
     * <img width="640" height="450" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/delay.o.png" alt="">
     * <p>
     * <em>Note:</em> the resulting Publisher will immediately propagate any {@code onError} notification
     * from the source Publisher.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with the backpressure behavior which is determined by the source {@code Publisher}.
     *  All of the other {@code Publisher}s supplied by the function are consumed
     *  in an unbounded manner (i.e., no backpressure applied to them).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code delay} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the item delay value type (ignored)
     * @param itemDelayIndicator
     *            a function that returns a Publisher for each item emitted by the source Publisher, which is
     *            then used to delay the emission of that item by the resulting Publisher until the Publisher
     *            returned from {@code itemDelay} emits an item
     * @return a Flowable that delays the emissions of the source Publisher via another Publisher on a
     *         per-item basis
     * @see <a href="http://reactivex.io/documentation/operators/delay.html">ReactiveX operators documentation: Delay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U> Flowable<T> delay(final Function<? super T, ? extends Publisher<U>> itemDelayIndicator) {
        ObjectHelper.requireNonNull(itemDelayIndicator, "itemDelayIndicator is null");
        return flatMap(FlowableInternalHelper.itemDelay(itemDelayIndicator));
    }

    /**
     * Returns a Flowable that emits the items emitted by the source Publisher shifted forward in time by a
     * specified delay. Error notifications from the source Publisher are not delayed.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/delay.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with the backpressure behavior which is determined by the source {@code Publisher}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code delay} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param delay
     *            the delay to shift the source by
     * @param unit
     *            the {@link TimeUnit} in which {@code period} is defined
     * @return the source Publisher shifted in time by the specified delay
     * @see <a href="http://reactivex.io/documentation/operators/delay.html">ReactiveX operators documentation: Delay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<T> delay(long delay, TimeUnit unit) {
        return delay(delay, unit, Schedulers.computation(), false);
    }

    /**
     * Returns a Flowable that emits the items emitted by the source Publisher shifted forward in time by a
     * specified delay. If {@code delayError} is true, error notifications will also be delayed.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/delay.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with the backpressure behavior which is determined by the source {@code Publisher}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code delay} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param delay
     *            the delay to shift the source by
     * @param unit
     *            the {@link TimeUnit} in which {@code period} is defined
     * @param delayError
     *            if true, the upstream exception is signalled with the given delay, after all preceding normal elements,
     *            if false, the upstream exception is signalled immediately
     * @return the source Publisher shifted in time by the specified delay
     * @see <a href="http://reactivex.io/documentation/operators/delay.html">ReactiveX operators documentation: Delay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<T> delay(long delay, TimeUnit unit, boolean delayError) {
        return delay(delay, unit, Schedulers.computation(), delayError);
    }

    /**
     * Returns a Flowable that emits the items emitted by the source Publisher shifted forward in time by a
     * specified delay. Error notifications from the source Publisher are not delayed.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/delay.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with the backpressure behavior which is determined by the source {@code Publisher}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param delay
     *            the delay to shift the source by
     * @param unit
     *            the time unit of {@code delay}
     * @param scheduler
     *            the {@link Scheduler} to use for delaying
     * @return the source Publisher shifted in time by the specified delay
     * @see <a href="http://reactivex.io/documentation/operators/delay.html">ReactiveX operators documentation: Delay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> delay(long delay, TimeUnit unit, Scheduler scheduler) {
        return delay(delay, unit, scheduler, false);
    }

    /**
     * Returns a Flowable that emits the items emitted by the source Publisher shifted forward in time by a
     * specified delay. If {@code delayError} is true, error notifications will also be delayed.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/delay.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with the backpressure behavior which is determined by the source {@code Publisher}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param delay
     *            the delay to shift the source by
     * @param unit
     *            the time unit of {@code delay}
     * @param scheduler
     *            the {@link Scheduler} to use for delaying
     * @param delayError
     *            if true, the upstream exception is signalled with the given delay, after all preceding normal elements,
     *            if false, the upstream exception is signalled immediately
     * @return the source Publisher shifted in time by the specified delay
     * @see <a href="http://reactivex.io/documentation/operators/delay.html">ReactiveX operators documentation: Delay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> delay(long delay, TimeUnit unit, Scheduler scheduler, boolean delayError) {
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");

        return RxJavaPlugins.onAssembly(new FlowableDelay<T>(this, Math.max(0L, delay), unit, scheduler, delayError));
    }

    /**
     * Returns a Flowable that delays the subscription to and emissions from the source Publisher via another
     * Publisher on a per-item basis.
     * <p>
     * <img width="640" height="450" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/delay.oo.png" alt="">
     * <p>
     * <em>Note:</em> the resulting Publisher will immediately propagate any {@code onError} notification
     * from the source Publisher.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with the backpressure behavior which is determined by the source {@code Publisher}.
     *  All of the other {@code Publisher}s supplied by the functions are consumed
     *  in an unbounded manner (i.e., no backpressure applied to them).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code delay} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the subscription delay value type (ignored)
     * @param <V>
     *            the item delay value type (ignored)
     * @param subscriptionIndicator
     *            a function that returns a Publisher that triggers the subscription to the source Publisher
     *            once it emits any item
     * @param itemDelayIndicator
     *            a function that returns a Publisher for each item emitted by the source Publisher, which is
     *            then used to delay the emission of that item by the resulting Publisher until the Publisher
     *            returned from {@code itemDelay} emits an item
     * @return a Flowable that delays the subscription and emissions of the source Publisher via another
     *         Publisher on a per-item basis
     * @see <a href="http://reactivex.io/documentation/operators/delay.html">ReactiveX operators documentation: Delay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, V> Flowable<T> delay(Publisher<U> subscriptionIndicator,
            Function<? super T, ? extends Publisher<V>> itemDelayIndicator) {
        return delaySubscription(subscriptionIndicator).delay(itemDelayIndicator);
    }

    /**
     * Returns a Flowable that delays the subscription to this Publisher
     * until the other Publisher emits an element or completes normally.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator forwards the backpressure requests to this Publisher once
     *  the subscription happens and requests Long.MAX_VALUE from the other Publisher</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This method does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the value type of the other Publisher, irrelevant
     * @param subscriptionIndicator the other Publisher that should trigger the subscription
     *        to this Publisher.
     * @return a Flowable that delays the subscription to this Publisher
     *         until the other Publisher emits an element or completes normally.
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U> Flowable<T> delaySubscription(Publisher<U> subscriptionIndicator) {
        ObjectHelper.requireNonNull(subscriptionIndicator, "subscriptionIndicator is null");
        return RxJavaPlugins.onAssembly(new FlowableDelaySubscriptionOther<T, U>(this, subscriptionIndicator));
    }

    /**
     * Returns a Flowable that delays the subscription to the source Publisher by a given amount of time.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/delaySubscription.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with the backpressure behavior which is determined by the source {@code Publisher}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code delaySubscription} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param delay
     *            the time to delay the subscription
     * @param unit
     *            the time unit of {@code delay}
     * @return a Flowable that delays the subscription to the source Publisher by the given amount
     * @see <a href="http://reactivex.io/documentation/operators/delay.html">ReactiveX operators documentation: Delay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<T> delaySubscription(long delay, TimeUnit unit) {
        return delaySubscription(delay, unit, Schedulers.computation());
    }

    /**
     * Returns a Flowable that delays the subscription to the source Publisher by a given amount of time,
     * both waiting and subscribing on a given Scheduler.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/delaySubscription.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with the backpressure behavior which is determined by the source {@code Publisher}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param delay
     *            the time to delay the subscription
     * @param unit
     *            the time unit of {@code delay}
     * @param scheduler
     *            the Scheduler on which the waiting and subscription will happen
     * @return a Flowable that delays the subscription to the source Publisher by a given
     *         amount, waiting and subscribing on the given Scheduler
     * @see <a href="http://reactivex.io/documentation/operators/delay.html">ReactiveX operators documentation: Delay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> delaySubscription(long delay, TimeUnit unit, Scheduler scheduler) {
        return delaySubscription(timer(delay, unit, scheduler));
    }

    /**
     * Returns a Flowable that reverses the effect of {@link #materialize materialize} by transforming the
     * {@link Notification} objects emitted by the source Publisher into the items or notifications they
     * represent.
     * <p>
     * <img width="640" height="335" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/dematerialize.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code dematerialize} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T2> the output value type
     * @return a Flowable that emits the items and notifications embedded in the {@link Notification} objects
     *         emitted by the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/materialize-dematerialize.html">ReactiveX operators documentation: Dematerialize</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <T2> Flowable<T2> dematerialize() {
        @SuppressWarnings("unchecked")
        Flowable<Notification<T2>> m = (Flowable<Notification<T2>>)this;
        return RxJavaPlugins.onAssembly(new FlowableDematerialize<T2>(m));
    }

    /**
     * Returns a Flowable that emits all items emitted by the source Publisher that are distinct.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/distinct.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code distinct} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a Flowable that emits only those items emitted by the source Publisher that are distinct from
     *         each other
     * @see <a href="http://reactivex.io/documentation/operators/distinct.html">ReactiveX operators documentation: Distinct</a>
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> distinct() {
        return distinct((Function)Functions.identity(), Functions.<T>createHashSet());
    }

    /**
     * Returns a Flowable that emits all items emitted by the source Publisher that are distinct according
     * to a key selector function.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/distinct.key.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code distinct} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <K> the key type
     * @param keySelector
     *            a function that projects an emitted item to a key value that is used to decide whether an item
     *            is distinct from another one or not
     * @return a Flowable that emits those items emitted by the source Publisher that have distinct keys
     * @see <a href="http://reactivex.io/documentation/operators/distinct.html">ReactiveX operators documentation: Distinct</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <K> Flowable<T> distinct(Function<? super T, K> keySelector) {
        return distinct(keySelector, Functions.<K>createHashSet());
    }

    /**
     * Returns a Flowable that emits all items emitted by the source Publisher that are distinct according
     * to a key selector function.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/distinct.key.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code distinct} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <K> the key type
     * @param keySelector
     *            a function that projects an emitted item to a key value that is used to decide whether an item
     *            is distinct from another one or not
     * @param collectionSupplier
     *            function called for each individual Subscriber to return a Collection subtype for holding the extracted
     *            keys and whose add() method's return indicates uniqueness.
     * @return a Flowable that emits those items emitted by the source Publisher that have distinct keys
     * @see <a href="http://reactivex.io/documentation/operators/distinct.html">ReactiveX operators documentation: Distinct</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <K> Flowable<T> distinct(Function<? super T, K> keySelector,
            Callable<? extends Collection<? super K>> collectionSupplier) {
        ObjectHelper.requireNonNull(keySelector, "keySelector is null");
        ObjectHelper.requireNonNull(collectionSupplier, "collectionSupplier is null");
        return RxJavaPlugins.onAssembly(new FlowableDistinct<T, K>(this, keySelector, collectionSupplier));
    }

    /**
     * Returns a Flowable that emits all items emitted by the source Publisher that are distinct from their
     * immediate predecessors.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/distinctUntilChanged.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code distinctUntilChanged} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a Flowable that emits those items from the source Publisher that are distinct from their
     *         immediate predecessors
     * @see <a href="http://reactivex.io/documentation/operators/distinct.html">ReactiveX operators documentation: Distinct</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> distinctUntilChanged() {
        return distinctUntilChanged(Functions.identity());
    }

    /**
     * Returns a Flowable that emits all items emitted by the source Publisher that are distinct from their
     * immediate predecessors, according to a key selector function.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/distinctUntilChanged.key.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code distinctUntilChanged} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <K> the key type
     * @param keySelector
     *            a function that projects an emitted item to a key value that is used to decide whether an item
     *            is distinct from another one or not
     * @return a Flowable that emits those items from the source Publisher whose keys are distinct from
     *         those of their immediate predecessors
     * @see <a href="http://reactivex.io/documentation/operators/distinct.html">ReactiveX operators documentation: Distinct</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <K> Flowable<T> distinctUntilChanged(Function<? super T, K> keySelector) {
        ObjectHelper.requireNonNull(keySelector, "keySelector is null");
        return RxJavaPlugins.onAssembly(new FlowableDistinctUntilChanged<T, K>(this, keySelector, ObjectHelper.equalsPredicate()));
    }

    /**
     * Returns a Flowable that emits all items emitted by the source Publisher that are distinct from their
     * immediate predecessors when compared with each other via the provided comparator function.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/distinctUntilChanged.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code distinctUntilChanged} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param comparer the function that receives the previous item and the current item and is
     *                   expected to return true if the two are equal, thus skipping the current value.
     * @return a Flowable that emits those items from the source Publisher that are distinct from their
     *         immediate predecessors
     * @see <a href="http://reactivex.io/documentation/operators/distinct.html">ReactiveX operators documentation: Distinct</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> distinctUntilChanged(BiPredicate<? super T, ? super T> comparer) {
        ObjectHelper.requireNonNull(comparer, "comparer is null");
        return RxJavaPlugins.onAssembly(new FlowableDistinctUntilChanged<T, T>(this, Functions.<T>identity(), comparer));
    }

    /**
     * Calls the specified action after this Flowable signals onError or onCompleted or gets cancelled by
     * the downstream.
     * <p>In case of a race between a terminal event and a cancellation, the provided {@code onFinally} action
     * is executed once per subscription.
     * <p>Note that the {@code onFinally} action is shared between subscriptions and as such
     * should be thread-safe.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code doFinally} does not operate by default on a particular {@link Scheduler}.</dd>
     *  <dt><b>Operator-fusion:</b></dt>
     *  <dd>This operator supports normal and conditional Subscribers as well as boundary-limited
     *  synchronous or asynchronous queue-fusion.</dd>
     * </dl>
     * <p>History: 2.0.1 - experimental
     * @param onFinally the action called when this Flowable terminates or gets cancelled
     * @return the new Flowable instance
     * @since 2.1
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> doFinally(Action onFinally) {
        ObjectHelper.requireNonNull(onFinally, "onFinally is null");
        return RxJavaPlugins.onAssembly(new FlowableDoFinally<T>(this, onFinally));
    }

    /**
     * Calls the specified consumer with the current item after this item has been emitted to the downstream.
     * <p>Note that the {@code onAfterNext} action is shared between subscriptions and as such
     * should be thread-safe.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code doAfterNext} does not operate by default on a particular {@link Scheduler}.</dd>
     *  <dt><b>Operator-fusion:</b></dt>
     *  <dd>This operator supports normal and conditional Subscribers as well as boundary-limited
     *  synchronous or asynchronous queue-fusion.</dd>
     * </dl>
     * <p>History: 2.0.1 - experimental
     * @param onAfterNext the Consumer that will be called after emitting an item from upstream to the downstream
     * @return the new Flowable instance
     * @since 2.1
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> doAfterNext(Consumer<? super T> onAfterNext) {
        ObjectHelper.requireNonNull(onAfterNext, "onAfterNext is null");
        return RxJavaPlugins.onAssembly(new FlowableDoAfterNext<T>(this, onAfterNext));
    }

    /**
     * Registers an {@link Action} to be called when this Publisher invokes either
     * {@link Subscriber#onComplete onComplete} or {@link Subscriber#onError onError}.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/finallyDo.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code doAfterTerminate} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onAfterTerminate
     *            an {@link Action} to be invoked when the source Publisher finishes
     * @return a Flowable that emits the same items as the source Publisher, then invokes the
     *         {@link Action}
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX operators documentation: Do</a>
     * @see #doOnTerminate(Action)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> doAfterTerminate(Action onAfterTerminate) {
        return doOnEach(Functions.emptyConsumer(), Functions.emptyConsumer(),
                Functions.EMPTY_ACTION, onAfterTerminate);
    }

    /**
     * Calls the cancel {@code Action} if the downstream cancels the sequence.
     * <p>
     * The action is shared between subscriptions and thus may be called concurrently from multiple
     * threads; the action must be thread safe.
     * <p>
     * If the action throws a runtime exception, that exception is rethrown by the {@code onCancel()} call,
     * sometimes as a {@code CompositeException} if there were multiple exceptions along the way.
     * <p>
     * Note that terminal events trigger the action unless the {@code Publisher} is subscribed to via {@code unsafeSubscribe()}.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/doOnUnsubscribe.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>{@code doOnCancel} does not interact with backpressure requests or value delivery; backpressure
     *  behavior is preserved between its upstream and its downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code doOnCancel} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onCancel
     *            the action that gets called when the source {@code Publisher}'s Subscription is cancelled
     * @return the source {@code Publisher} modified so as to call this Action when appropriate
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX operators documentation: Do</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> doOnCancel(Action onCancel) {
        return doOnLifecycle(Functions.emptyConsumer(), Functions.EMPTY_LONG_CONSUMER, onCancel);
    }

    /**
     * Modifies the source Publisher so that it invokes an action when it calls {@code onComplete}.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/doOnComplete.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code doOnComplete} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onComplete
     *            the action to invoke when the source Publisher calls {@code onComplete}
     * @return the source Publisher with the side-effecting behavior applied
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX operators documentation: Do</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> doOnComplete(Action onComplete) {
        return doOnEach(Functions.emptyConsumer(), Functions.emptyConsumer(),
                onComplete, Functions.EMPTY_ACTION);
    }

    /**
     * Calls the appropriate onXXX consumer (shared between all subscribers) whenever a signal with the same type
     * passes through, before forwarding them to downstream.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/doOnEach.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code doOnEach} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return the source Publisher with the side-effecting behavior applied
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX operators documentation: Do</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    private Flowable<T> doOnEach(Consumer<? super T> onNext, Consumer<? super Throwable> onError,
            Action onComplete, Action onAfterTerminate) {
        ObjectHelper.requireNonNull(onNext, "onNext is null");
        ObjectHelper.requireNonNull(onError, "onError is null");
        ObjectHelper.requireNonNull(onComplete, "onComplete is null");
        ObjectHelper.requireNonNull(onAfterTerminate, "onAfterTerminate is null");
        return RxJavaPlugins.onAssembly(new FlowableDoOnEach<T>(this, onNext, onError, onComplete, onAfterTerminate));
    }

    /**
     * Modifies the source Publisher so that it invokes an action for each item it emits.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/doOnEach.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code doOnEach} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onNotification
     *            the action to invoke for each item emitted by the source Publisher
     * @return the source Publisher with the side-effecting behavior applied
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX operators documentation: Do</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> doOnEach(final Consumer<? super Notification<T>> onNotification) {
        ObjectHelper.requireNonNull(onNotification, "consumer is null");
        return doOnEach(
                Functions.notificationOnNext(onNotification),
                Functions.notificationOnError(onNotification),
                Functions.notificationOnComplete(onNotification),
                Functions.EMPTY_ACTION
            );
    }

    /**
     * Modifies the source Publisher so that it notifies a Subscriber for each item and terminal event it emits.
     * <p>
     * In case the {@code onError} of the supplied Subscriber throws, the downstream will receive a composite
     * exception containing the original exception and the exception thrown by {@code onError}. If either the
     * {@code onNext} or the {@code onComplete} method of the supplied Subscriber throws, the downstream will be
     * terminated and will receive this thrown exception.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/doOnEach.o.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code doOnEach} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param subscriber
     *            the Subscriber to be notified about onNext, onError and onComplete events on its
     *            respective methods before the actual downstream Subscriber gets notified.
     * @return the source Publisher with the side-effecting behavior applied
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX operators documentation: Do</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> doOnEach(final Subscriber<? super T> subscriber) {
        ObjectHelper.requireNonNull(subscriber, "subscriber is null");
        return doOnEach(
                FlowableInternalHelper.subscriberOnNext(subscriber),
                FlowableInternalHelper.subscriberOnError(subscriber),
                FlowableInternalHelper.subscriberOnComplete(subscriber),
                Functions.EMPTY_ACTION);
    }

    /**
     * Modifies the source Publisher so that it invokes an action if it calls {@code onError}.
     * <p>
     * In case the {@code onError} action throws, the downstream will receive a composite exception containing
     * the original exception and the exception thrown by {@code onError}.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/doOnError.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code doOnError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onError
     *            the action to invoke if the source Publisher calls {@code onError}
     * @return the source Publisher with the side-effecting behavior applied
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX operators documentation: Do</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> doOnError(Consumer<? super Throwable> onError) {
        return doOnEach(Functions.emptyConsumer(), onError,
                Functions.EMPTY_ACTION, Functions.EMPTY_ACTION);
    }

    /**
     * Calls the appropriate onXXX method (shared between all Subscribers) for the lifecycle events of
     * the sequence (subscription, cancellation, requesting).
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/doOnNext.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code doOnLifecycle} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onSubscribe
     *              a Consumer called with the Subscription sent via Subscriber.onSubscribe()
     * @param onRequest
     *              a LongConsumer called with the request amount sent via Subscription.request()
     * @param onCancel
     *              called when the downstream cancels the Subscription via cancel()
     * @return the source Publisher with the side-effecting behavior applied
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX operators documentation: Do</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> doOnLifecycle(final Consumer<? super Subscription> onSubscribe,
            final LongConsumer onRequest, final Action onCancel) {
        ObjectHelper.requireNonNull(onSubscribe, "onSubscribe is null");
        ObjectHelper.requireNonNull(onRequest, "onRequest is null");
        ObjectHelper.requireNonNull(onCancel, "onCancel is null");
        return RxJavaPlugins.onAssembly(new FlowableDoOnLifecycle<T>(this, onSubscribe, onRequest, onCancel));
    }

    /**
     * Modifies the source Publisher so that it invokes an action when it calls {@code onNext}.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/doOnNext.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code doOnNext} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onNext
     *            the action to invoke when the source Publisher calls {@code onNext}
     * @return the source Publisher with the side-effecting behavior applied
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX operators documentation: Do</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> doOnNext(Consumer<? super T> onNext) {
        return doOnEach(onNext, Functions.emptyConsumer(),
                Functions.EMPTY_ACTION, Functions.EMPTY_ACTION);
    }

    /**
     * Modifies the source {@code Publisher} so that it invokes the given action when it receives a
     * request for more items.
     * <p>
     * <b>Note:</b> This operator is for tracing the internal behavior of back-pressure request
     * patterns and generally intended for debugging use.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     * <dt><b>Scheduler:</b></dt>
     * <dd>{@code doOnRequest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onRequest
     *            the action that gets called when a Subscriber requests items from this
     *            {@code Publisher}
     * @return the source {@code Publisher} modified so as to call this Action when appropriate
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX operators
     *      documentation: Do</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> doOnRequest(LongConsumer onRequest) {
        return doOnLifecycle(Functions.emptyConsumer(), onRequest, Functions.EMPTY_ACTION);
    }

    /**
     * Modifies the source {@code Publisher} so that it invokes the given action when it is subscribed from
     * its subscribers. Each subscription will result in an invocation of the given action except when the
     * source {@code Publisher} is reference counted, in which case the source {@code Publisher} will invoke
     * the given action for the first subscription.
     * <p>
     * <img width="640" height="390" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/doOnSubscribe.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code doOnSubscribe} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onSubscribe
     *            the Consumer that gets called when a Subscriber subscribes to the current {@code Flowable}
     * @return the source {@code Publisher} modified so as to call this Consumer when appropriate
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX operators documentation: Do</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> doOnSubscribe(Consumer<? super Subscription> onSubscribe) {
        return doOnLifecycle(onSubscribe, Functions.EMPTY_LONG_CONSUMER, Functions.EMPTY_ACTION);
    }

    /**
     * Modifies the source Publisher so that it invokes an action when it calls {@code onComplete} or
     * {@code onError}.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/doOnTerminate.png" alt="">
     * <p>
     * This differs from {@code doAfterTerminate} in that this happens <em>before</em> the {@code onComplete} or
     * {@code onError} notification.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code doOnTerminate} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onTerminate
     *            the action to invoke when the source Publisher calls {@code onComplete} or {@code onError}
     * @return the source Publisher with the side-effecting behavior applied
     * @see <a href="http://reactivex.io/documentation/operators/do.html">ReactiveX operators documentation: Do</a>
     * @see #doAfterTerminate(Action)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> doOnTerminate(final Action onTerminate) {
        return doOnEach(Functions.emptyConsumer(), Functions.actionConsumer(onTerminate),
                onTerminate, Functions.EMPTY_ACTION);
    }

    /**
     * Returns a Maybe that emits the single item at a specified index in a sequence of emissions from
     * this Flowable or completes if this Flowable sequence has fewer elements than index.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/elementAt.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code elementAt} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param index
     *            the zero-based index of the item to retrieve
     * @return a Maybe that emits a single item: the item at the specified position in the sequence of
     *         those emitted by the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/elementat.html">ReactiveX operators documentation: ElementAt</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Maybe<T> elementAt(long index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("index >= 0 required but it was " + index);
        }
        return RxJavaPlugins.onAssembly(new FlowableElementAtMaybe<T>(this, index));
    }

    /**
     * Returns a Flowable that emits the item found at a specified index in a sequence of emissions from
     * this Flowable, or a default item if that index is out of range.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/elementAtOrDefault.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code elementAt} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param index
     *            the zero-based index of the item to retrieve
     * @param defaultItem
     *            the default item
     * @return a Flowable that emits the item at the specified position in the sequence emitted by the source
     *         Publisher, or the default item if that index is outside the bounds of the source sequence
     * @throws IndexOutOfBoundsException
     *             if {@code index} is less than 0
     * @see <a href="http://reactivex.io/documentation/operators/elementat.html">ReactiveX operators documentation: ElementAt</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<T> elementAt(long index, T defaultItem) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("index >= 0 required but it was " + index);
        }
        ObjectHelper.requireNonNull(defaultItem, "defaultItem is null");
        return RxJavaPlugins.onAssembly(new FlowableElementAtSingle<T>(this, index, defaultItem));
    }

    /**
     * Returns a Flowable that emits the item found at a specified index in a sequence of emissions from
     * this Flowable or signals a {@link NoSuchElementException} if this Flowable has fewer elements than index.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/elementAtOrDefault.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded manner
     *  (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code elementAtOrError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param index
     *            the zero-based index of the item to retrieve
     * @return a Flowable that emits the item at the specified position in the sequence emitted by the source
     *         Publisher, or the default item if that index is outside the bounds of the source sequence
     * @throws IndexOutOfBoundsException
     *             if {@code index} is less than 0
     * @see <a href="http://reactivex.io/documentation/operators/elementat.html">ReactiveX operators documentation: ElementAt</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<T> elementAtOrError(long index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("index >= 0 required but it was " + index);
        }
        return RxJavaPlugins.onAssembly(new FlowableElementAtSingle<T>(this, index, null));
    }

    /**
     * Filters items emitted by a Publisher by only emitting those that satisfy a specified predicate.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/filter.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code filter} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param predicate
     *            a function that evaluates each item emitted by the source Publisher, returning {@code true}
     *            if it passes the filter
     * @return a Flowable that emits only those items emitted by the source Publisher that the filter
     *         evaluates as {@code true}
     * @see <a href="http://reactivex.io/documentation/operators/filter.html">ReactiveX operators documentation: Filter</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> filter(Predicate<? super T> predicate) {
        ObjectHelper.requireNonNull(predicate, "predicate is null");
        return RxJavaPlugins.onAssembly(new FlowableFilter<T>(this, predicate));
    }

    /**
     * Returns a Maybe that emits only the very first item emitted by this Flowable or
     * completes if this Flowable is empty.
     * <p>
     * <img width="640" height="237" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/firstElement.m.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code firstElement} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return the new Maybe instance
     * @see <a href="http://reactivex.io/documentation/operators/first.html">ReactiveX operators documentation: First</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.SPECIAL) // take may trigger UNBOUNDED_IN
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Maybe<T> firstElement() {
        return elementAt(0);
    }

    /**
     * Returns a Single that emits only the very first item emitted by this Flowable, or a default
     * item if this Flowable completes without emitting anything.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/first.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code first} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param defaultItem
     *            the default item to emit if the source Publisher doesn't emit anything
     * @return a Single that emits only the very first item from the source, or a default item if the
     *         source Publisher completes without emitting any items
     * @see <a href="http://reactivex.io/documentation/operators/first.html">ReactiveX operators documentation: First</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.SPECIAL) // take may trigger UNBOUNDED_IN
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<T> first(T defaultItem) {
        return elementAt(0, defaultItem);
    }

    /**
     * Returns a Single that emits only the very first item emitted by this Flowable or
     * signals a {@link NoSuchElementException} if this Flowable is empty.
     * <p>
     * <img width="640" height="237" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/firstOrError.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code firstOrError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return the new Single instance
     * @see <a href="http://reactivex.io/documentation/operators/first.html">ReactiveX operators documentation: First</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.SPECIAL) // take may trigger UNBOUNDED_IN
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<T> firstOrError() {
        return elementAtOrError(0);
    }

    /**
     * Returns a Flowable that emits items based on applying a function that you supply to each item emitted
     * by the source Publisher, where that function returns a Publisher, and then merging those resulting
     * Publishers and emitting the results of this merger.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/flatMap.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The upstream Flowable is consumed
     *  in a bounded manner (up to {@link #bufferSize()} outstanding request amount for items).
     *  The inner {@code Publisher}s are expected to honor backpressure; if violated,
     *  the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the value type of the inner Publishers and the output type
     * @param mapper
     *            a function that, when applied to an item emitted by the source Publisher, returns an
     *            Publisher
     * @return a Flowable that emits the result of applying the transformation function to each item emitted
     *         by the source Publisher and merging the results of the Publishers obtained from this
     *         transformation
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> flatMap(Function<? super T, ? extends Publisher<? extends R>> mapper) {
        return flatMap(mapper, false, bufferSize(), bufferSize());
    }

    /**
     * Returns a Flowable that emits items based on applying a function that you supply to each item emitted
     * by the source Publisher, where that function returns a Publisher, and then merging those resulting
     * Publishers and emitting the results of this merger.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/flatMap.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The upstream Flowable is consumed
     *  in a bounded manner (up to {@link #bufferSize()} outstanding request amount for items).
     *  The inner {@code Publisher}s are expected to honor backpressure; if violated,
     *  the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the value type of the inner Publishers and the output type
     * @param mapper
     *            a function that, when applied to an item emitted by the source Publisher, returns an
     *            Publisher
     * @param delayErrors
     *            if true, exceptions from the current Flowable and all inner Publishers are delayed until all of them terminate
     *            if false, the first one signalling an exception will terminate the whole sequence immediately
     * @return a Flowable that emits the result of applying the transformation function to each item emitted
     *         by the source Publisher and merging the results of the Publishers obtained from this
     *         transformation
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> flatMap(Function<? super T, ? extends Publisher<? extends R>> mapper, boolean delayErrors) {
        return flatMap(mapper, delayErrors, bufferSize(), bufferSize());
    }

    /**
     * Returns a Flowable that emits items based on applying a function that you supply to each item emitted
     * by the source Publisher, where that function returns a Publisher, and then merging those resulting
     * Publishers and emitting the results of this merger, while limiting the maximum number of concurrent
     * subscriptions to these Publishers.
     * <!-- <p> -->
     * <!-- <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/flatMap.png" alt=""> -->
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The upstream Flowable is consumed
     *  in a bounded manner (up to {@code maxConcurrency} outstanding request amount for items).
     *  The inner {@code Publisher}s are expected to honor backpressure; if violated,
     *  the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the value type of the inner Publishers and the output type
     * @param mapper
     *            a function that, when applied to an item emitted by the source Publisher, returns an
     *            Publisher
     * @param maxConcurrency
     *         the maximum number of Publishers that may be subscribed to concurrently
     * @return a Flowable that emits the result of applying the transformation function to each item emitted
     *         by the source Publisher and merging the results of the Publishers obtained from this
     *         transformation
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> flatMap(Function<? super T, ? extends Publisher<? extends R>> mapper, int maxConcurrency) {
        return flatMap(mapper, false, maxConcurrency, bufferSize());
    }

    /**
     * Returns a Flowable that emits items based on applying a function that you supply to each item emitted
     * by the source Publisher, where that function returns a Publisher, and then merging those resulting
     * Publishers and emitting the results of this merger, while limiting the maximum number of concurrent
     * subscriptions to these Publishers.
     * <!-- <p> -->
     * <!-- <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/flatMap.png" alt=""> -->
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The upstream Flowable is consumed
     *  in a bounded manner (up to {@code maxConcurrency} outstanding request amount for items).
     *  The inner {@code Publisher}s are expected to honor backpressure; if violated,
     *  the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the value type of the inner Publishers and the output type
     * @param mapper
     *            a function that, when applied to an item emitted by the source Publisher, returns an
     *            Publisher
     * @param maxConcurrency
     *         the maximum number of Publishers that may be subscribed to concurrently
     * @param delayErrors
     *            if true, exceptions from the current Flowable and all inner Publishers are delayed until all of them terminate
     *            if false, the first one signalling an exception will terminate the whole sequence immediately
     * @return a Flowable that emits the result of applying the transformation function to each item emitted
     *         by the source Publisher and merging the results of the Publishers obtained from this
     *         transformation
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> flatMap(Function<? super T, ? extends Publisher<? extends R>> mapper, boolean delayErrors, int maxConcurrency) {
        return flatMap(mapper, delayErrors, maxConcurrency, bufferSize());
    }

    /**
     * Returns a Flowable that emits items based on applying a function that you supply to each item emitted
     * by the source Publisher, where that function returns a Publisher, and then merging those resulting
     * Publishers and emitting the results of this merger, while limiting the maximum number of concurrent
     * subscriptions to these Publishers.
     * <!-- <p> -->
     * <!-- <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/flatMap.png" alt=""> -->
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The upstream Flowable is consumed
     *  in a bounded manner (up to {@code maxConcurrency} outstanding request amount for items).
     *  The inner {@code Publisher}s are expected to honor backpressure; if violated,
     *  the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the value type of the inner Publishers and the output type
     * @param mapper
     *            a function that, when applied to an item emitted by the source Publisher, returns an
     *            Publisher
     * @param maxConcurrency
     *         the maximum number of Publishers that may be subscribed to concurrently
     * @param delayErrors
     *            if true, exceptions from the current Flowable and all inner Publishers are delayed until all of them terminate
     *            if false, the first one signalling an exception will terminate the whole sequence immediately
     * @param bufferSize
     *            the number of elements to prefetch from each inner Publisher
     * @return a Flowable that emits the result of applying the transformation function to each item emitted
     *         by the source Publisher and merging the results of the Publishers obtained from this
     *         transformation
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> flatMap(Function<? super T, ? extends Publisher<? extends R>> mapper,
            boolean delayErrors, int maxConcurrency, int bufferSize) {
        ObjectHelper.requireNonNull(mapper, "mapper is null");
        ObjectHelper.verifyPositive(maxConcurrency, "maxConcurrency");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        if (this instanceof ScalarCallable) {
            @SuppressWarnings("unchecked")
            T v = ((ScalarCallable<T>)this).call();
            if (v == null) {
                return empty();
            }
            return FlowableScalarXMap.scalarXMap(v, mapper);
        }
        return RxJavaPlugins.onAssembly(new FlowableFlatMap<T, R>(this, mapper, delayErrors, maxConcurrency, bufferSize));
    }

    /**
     * Returns a Flowable that applies a function to each item emitted or notification raised by the source
     * Publisher and then flattens the Publishers returned from these functions and emits the resulting items.
     * <p>
     * <img width="640" height="410" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeMap.nce.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The upstream Flowable is consumed
     *  in a bounded manner (up to {@link #bufferSize()} outstanding request amount for items).
     *  The inner {@code Publisher}s are expected to honor backpressure; if violated,
     *  the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R>
     *            the result type
     * @param onNextMapper
     *            a function that returns a Publisher to merge for each item emitted by the source Publisher
     * @param onErrorMapper
     *            a function that returns a Publisher to merge for an onError notification from the source
     *            Publisher
     * @param onCompleteSupplier
     *            a function that returns a Publisher to merge for an onComplete notification from the source
     *            Publisher
     * @return a Flowable that emits the results of merging the Publishers returned from applying the
     *         specified functions to the emissions and notifications of the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> flatMap(
            Function<? super T, ? extends Publisher<? extends R>> onNextMapper,
            Function<? super Throwable, ? extends Publisher<? extends R>> onErrorMapper,
            Callable<? extends Publisher<? extends R>> onCompleteSupplier) {
        ObjectHelper.requireNonNull(onNextMapper, "onNextMapper is null");
        ObjectHelper.requireNonNull(onErrorMapper, "onErrorMapper is null");
        ObjectHelper.requireNonNull(onCompleteSupplier, "onCompleteSupplier is null");
        return merge(new FlowableMapNotification<T, Publisher<? extends R>>(this, onNextMapper, onErrorMapper, onCompleteSupplier));
    }

    /**
     * Returns a Flowable that applies a function to each item emitted or notification raised by the source
     * Publisher and then flattens the Publishers returned from these functions and emits the resulting items,
     * while limiting the maximum number of concurrent subscriptions to these Publishers.
     * <!-- <p> -->
     * <!-- <img width="640" height="410" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeMap.nce.png" alt=""> -->
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The upstream Flowable is consumed
     *  in a bounded manner (up to {@code maxConcurrency} outstanding request amount for items).
     *  The inner {@code Publisher}s are expected to honor backpressure; if violated,
     *  the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R>
     *            the result type
     * @param onNextMapper
     *            a function that returns a Publisher to merge for each item emitted by the source Publisher
     * @param onErrorMapper
     *            a function that returns a Publisher to merge for an onError notification from the source
     *            Publisher
     * @param onCompleteSupplier
     *            a function that returns a Publisher to merge for an onComplete notification from the source
     *            Publisher
     * @param maxConcurrency
     *         the maximum number of Publishers that may be subscribed to concurrently
     * @return a Flowable that emits the results of merging the Publishers returned from applying the
     *         specified functions to the emissions and notifications of the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> flatMap(
            Function<? super T, ? extends Publisher<? extends R>> onNextMapper,
            Function<Throwable, ? extends Publisher<? extends R>> onErrorMapper,
            Callable<? extends Publisher<? extends R>> onCompleteSupplier,
            int maxConcurrency) {
        ObjectHelper.requireNonNull(onNextMapper, "onNextMapper is null");
        ObjectHelper.requireNonNull(onErrorMapper, "onErrorMapper is null");
        ObjectHelper.requireNonNull(onCompleteSupplier, "onCompleteSupplier is null");
        return merge(new FlowableMapNotification<T, Publisher<? extends R>>(
                this, onNextMapper, onErrorMapper, onCompleteSupplier), maxConcurrency);
    }

    /**
     * Returns a Flowable that emits the results of a specified function to the pair of values emitted by the
     * source Publisher and a specified collection Publisher.
     * <p>
     * <img width="640" height="390" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeMap.r.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The upstream Flowable is consumed
     *  in a bounded manner (up to {@code maxConcurrency} outstanding request amount for items).
     *  The inner {@code Publisher}s are expected to honor backpressure; if violated,
     *  the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the type of items emitted by the inner Publishers
     * @param <R>
     *            the type of items emitted by the combiner function
     * @param mapper
     *            a function that returns a Publisher for each item emitted by the source Publisher
     * @param combiner
     *            a function that combines one item emitted by each of the source and collection Publishers and
     *            returns an item to be emitted by the resulting Publisher
     * @return a Flowable that emits the results of applying a function to a pair of values emitted by the
     *         source Publisher and the collection Publisher
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, R> Flowable<R> flatMap(Function<? super T, ? extends Publisher<? extends U>> mapper,
            BiFunction<? super T, ? super U, ? extends R> combiner) {
        return flatMap(mapper, combiner, false, bufferSize(), bufferSize());
    }

    /**
     * Returns a Flowable that emits the results of a specified function to the pair of values emitted by the
     * source Publisher and a specified collection Publisher.
     * <p>
     * <img width="640" height="390" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeMap.r.png" alt="">
     * <dl>
     * <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The upstream Flowable is consumed
     *  in a bounded manner (up to {@link #bufferSize()} outstanding request amount for items).
     *  The inner {@code Publisher}s are expected to honor backpressure; if violated,
     *  the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the type of items emitted by the inner Publishers
     * @param <R>
     *            the type of items emitted by the combiner functions
     * @param mapper
     *            a function that returns a Publisher for each item emitted by the source Publisher
     * @param combiner
     *            a function that combines one item emitted by each of the source and collection Publishers and
     *            returns an item to be emitted by the resulting Publisher
     * @param delayErrors
     *            if true, exceptions from the current Flowable and all inner Publishers are delayed until all of them terminate
     *            if false, the first one signalling an exception will terminate the whole sequence immediately
     * @return a Flowable that emits the results of applying a function to a pair of values emitted by the
     *         source Publisher and the collection Publisher
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, R> Flowable<R> flatMap(Function<? super T, ? extends Publisher<? extends U>> mapper,
            BiFunction<? super T, ? super U, ? extends R> combiner, boolean delayErrors) {
        return flatMap(mapper, combiner, delayErrors, bufferSize(), bufferSize());
    }

    /**
     * Returns a Flowable that emits the results of a specified function to the pair of values emitted by the
     * source Publisher and a specified collection Publisher, while limiting the maximum number of concurrent
     * subscriptions to these Publishers.
     * <!-- <p> -->
     * <!-- <img width="640" height="390" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeMap.r.png" alt=""> -->
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The upstream Flowable is consumed
     *  in a bounded manner (up to {@code maxConcurrency} outstanding request amount for items).
     *  The inner {@code Publisher}s are expected to honor backpressure; if violated,
     *  the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the type of items emitted by the inner Publishers
     * @param <R>
     *            the type of items emitted by the combiner function
     * @param mapper
     *            a function that returns a Publisher for each item emitted by the source Publisher
     * @param combiner
     *            a function that combines one item emitted by each of the source and collection Publishers and
     *            returns an item to be emitted by the resulting Publisher
     * @param maxConcurrency
     *         the maximum number of Publishers that may be subscribed to concurrently
     * @param delayErrors
     *            if true, exceptions from the current Flowable and all inner Publishers are delayed until all of them terminate
     *            if false, the first one signalling an exception will terminate the whole sequence immediately
     * @return a Flowable that emits the results of applying a function to a pair of values emitted by the
     *         source Publisher and the collection Publisher
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, R> Flowable<R> flatMap(Function<? super T, ? extends Publisher<? extends U>> mapper,
            BiFunction<? super T, ? super U, ? extends R> combiner, boolean delayErrors, int maxConcurrency) {
        return flatMap(mapper, combiner, delayErrors, maxConcurrency, bufferSize());
    }

    /**
     * Returns a Flowable that emits the results of a specified function to the pair of values emitted by the
     * source Publisher and a specified collection Publisher, while limiting the maximum number of concurrent
     * subscriptions to these Publishers.
     * <!-- <p> -->
     * <!-- <img width="640" height="390" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeMap.r.png" alt=""> -->
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The upstream Flowable is consumed
     *  in a bounded manner (up to {@code maxConcurrency} outstanding request amount for items).
     *  The inner {@code Publisher}s are expected to honor backpressure; if violated,
     *  the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the type of items emitted by the inner Publishers
     * @param <R>
     *            the type of items emitted by the combiner function
     * @param mapper
     *            a function that returns a Publisher for each item emitted by the source Publisher
     * @param combiner
     *            a function that combines one item emitted by each of the source and collection Publishers and
     *            returns an item to be emitted by the resulting Publisher
     * @param maxConcurrency
     *         the maximum number of Publishers that may be subscribed to concurrently
     * @param delayErrors
     *            if true, exceptions from the current Flowable and all inner Publishers are delayed until all of them terminate
     *            if false, the first one signalling an exception will terminate the whole sequence immediately
     * @param bufferSize
     *            the number of elements to prefetch from the inner Publishers.
     * @return a Flowable that emits the results of applying a function to a pair of values emitted by the
     *         source Publisher and the collection Publisher
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, R> Flowable<R> flatMap(final Function<? super T, ? extends Publisher<? extends U>> mapper,
            final BiFunction<? super T, ? super U, ? extends R> combiner, boolean delayErrors, int maxConcurrency, int bufferSize) {
        ObjectHelper.requireNonNull(mapper, "mapper is null");
        ObjectHelper.requireNonNull(combiner, "combiner is null");
        ObjectHelper.verifyPositive(maxConcurrency, "maxConcurrency");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return flatMap(FlowableInternalHelper.flatMapWithCombiner(mapper, combiner), delayErrors, maxConcurrency, bufferSize);
    }

    /**
     * Returns a Flowable that emits the results of a specified function to the pair of values emitted by the
     * source Publisher and a specified collection Publisher, while limiting the maximum number of concurrent
     * subscriptions to these Publishers.
     * <!-- <p> -->
     * <!-- <img width="640" height="390" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/mergeMap.r.png" alt=""> -->
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The upstream Flowable is consumed
     *  in a bounded manner (up to {@link #bufferSize()} outstanding request amount for items).
     *  The inner {@code Publisher}s are expected to honor backpressure; if violated,
     *  the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the type of items emitted by the inner Publishers
     * @param <R>
     *            the type of items emitted by the combiner function
     * @param mapper
     *            a function that returns a Publisher for each item emitted by the source Publisher
     * @param combiner
     *            a function that combines one item emitted by each of the source and collection Publishers and
     *            returns an item to be emitted by the resulting Publisher
     * @param maxConcurrency
     *         the maximum number of Publishers that may be subscribed to concurrently
     * @return a Flowable that emits the results of applying a function to a pair of values emitted by the
     *         source Publisher and the collection Publisher
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, R> Flowable<R> flatMap(Function<? super T, ? extends Publisher<? extends U>> mapper,
            BiFunction<? super T, ? super U, ? extends R> combiner, int maxConcurrency) {
        return flatMap(mapper, combiner, false, maxConcurrency, bufferSize());
    }

    /**
     * Maps each element of the upstream Flowable into CompletableSources, subscribes to them and
     * waits until the upstream and all CompletableSources complete.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the upstream in an unbounded manner.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMapCompletable} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param mapper the function that received each source value and transforms them into CompletableSources.
     * @return the new Completable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Completable flatMapCompletable(Function<? super T, ? extends CompletableSource> mapper) {
        return flatMapCompletable(mapper, false, Integer.MAX_VALUE);
    }

    /**
     * Maps each element of the upstream Flowable into CompletableSources, subscribes to them and
     * waits until the upstream and all CompletableSources complete, optionally delaying all errors.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>If {@code maxConcurrency == Integer.MAX_VALUE} the operator consumes the upstream in an unbounded manner.
     *  Otherwise the operator expects the upstream to honor backpressure. If the upstream doesn't support backpressure
     *  the operator behaves as if {@code maxConcurrency == Integer.MAX_VALUE} was used.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMapCompletable} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param mapper the function that received each source value and transforms them into CompletableSources.
     * @param delayErrors if true errors from the upstream and inner CompletableSources are delayed until each of them
     * terminates.
     * @param maxConcurrency the maximum number of active subscriptions to the CompletableSources.
     * @return the new Completable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Completable flatMapCompletable(Function<? super T, ? extends CompletableSource> mapper, boolean delayErrors, int maxConcurrency) {
        ObjectHelper.requireNonNull(mapper, "mapper is null");
        ObjectHelper.verifyPositive(maxConcurrency, "maxConcurrency");
        return RxJavaPlugins.onAssembly(new FlowableFlatMapCompletableCompletable<T>(this, mapper, delayErrors, maxConcurrency));
    }

    /**
     * Returns a Flowable that merges each item emitted by the source Publisher with the values in an
     * Iterable corresponding to that item that is generated by a selector.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/flatMapIterable.f.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s is
     *  expected to honor backpressure as well. If the source {@code Publisher} violates the rule, the operator will
     *  signal a {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMapIterable} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the type of item emitted by the resulting Iterable
     * @param mapper
     *            a function that returns an Iterable sequence of values for when given an item emitted by the
     *            source Publisher
     * @return a Flowable that emits the results of merging the items emitted by the source Publisher with
     *         the values in the Iterables corresponding to those items, as generated by {@code collectionSelector}
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U> Flowable<U> flatMapIterable(final Function<? super T, ? extends Iterable<? extends U>> mapper) {
        return flatMapIterable(mapper, bufferSize());
    }

    /**
     * Returns a Flowable that merges each item emitted by the source Publisher with the values in an
     * Iterable corresponding to that item that is generated by a selector.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/flatMapIterable.f.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s is
     *  expected to honor backpressure as well. If the source {@code Publisher} violates the rule, the operator will
     *  signal a {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMapIterable} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the type of item emitted by the resulting Iterable
     * @param mapper
     *            a function that returns an Iterable sequence of values for when given an item emitted by the
     *            source Publisher
     * @param bufferSize
     *            the number of elements to prefetch from the current Flowable
     * @return a Flowable that emits the results of merging the items emitted by the source Publisher with
     *         the values in the Iterables corresponding to those items, as generated by {@code collectionSelector}
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U> Flowable<U> flatMapIterable(final Function<? super T, ? extends Iterable<? extends U>> mapper, int bufferSize) {
        ObjectHelper.requireNonNull(mapper, "mapper is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return RxJavaPlugins.onAssembly(new FlowableFlattenIterable<T, U>(this, mapper, bufferSize));
    }

    /**
     * Returns a Flowable that emits the results of applying a function to the pair of values from the source
     * Publisher and an Iterable corresponding to that item that is generated by a selector.
     * <p>
     * <img width="640" height="410" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/flatMapIterable.f.r.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and the source {@code Publisher}s is
     *  consumed in an unbounded manner (i.e., no backpressure is applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMapIterable} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the collection element type
     * @param <V>
     *            the type of item emitted by the resulting Iterable
     * @param mapper
     *            a function that returns an Iterable sequence of values for each item emitted by the source
     *            Publisher
     * @param resultSelector
     *            a function that returns an item based on the item emitted by the source Publisher and the
     *            Iterable returned for that item by the {@code collectionSelector}
     * @return a Flowable that emits the items returned by {@code resultSelector} for each item in the source
     *         Publisher
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, V> Flowable<V> flatMapIterable(final Function<? super T, ? extends Iterable<? extends U>> mapper,
            final BiFunction<? super T, ? super U, ? extends V> resultSelector) {
        ObjectHelper.requireNonNull(mapper, "mapper is null");
        ObjectHelper.requireNonNull(resultSelector, "resultSelector is null");
        return flatMap(FlowableInternalHelper.flatMapIntoIterable(mapper), resultSelector, false, bufferSize(), bufferSize());
    }

    /**
     * Returns a Flowable that merges each item emitted by the source Publisher with the values in an
     * Iterable corresponding to that item that is generated by a selector, while limiting the number of concurrent
     * subscriptions to these Publishers.
     * <p>
     * <img width="640" height="410" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/flatMapIterable.f.r.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s is
     *  expected to honor backpressure as well. If the source {@code Publisher} violates the rule, the operator will
     *  signal a {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMapIterable} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the element type of the inner Iterable sequences
     * @param <V>
     *            the type of item emitted by the resulting Publisher
     * @param mapper
     *            a function that returns an Iterable sequence of values for when given an item emitted by the
     *            source Publisher
     * @param resultSelector
     *            a function that returns an item based on the item emitted by the source Publisher and the
     *            Iterable returned for that item by the {@code collectionSelector}
     * @param prefetch
     *            the number of elements to prefetch from the current Flowable
     * @return a Flowable that emits the results of merging the items emitted by the source Publisher with
     *         the values in the Iterables corresponding to those items, as generated by {@code collectionSelector}
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, V> Flowable<V> flatMapIterable(final Function<? super T, ? extends Iterable<? extends U>> mapper,
            final BiFunction<? super T, ? super U, ? extends V> resultSelector, int prefetch) {
        ObjectHelper.requireNonNull(mapper, "mapper is null");
        ObjectHelper.requireNonNull(resultSelector, "resultSelector is null");
        return flatMap(FlowableInternalHelper.flatMapIntoIterable(mapper), resultSelector, false, bufferSize(), prefetch);
    }

    /**
     * Maps each element of the upstream Flowable into MaybeSources, subscribes to all of them
     * and merges their onSuccess values, in no particular order, into a single Flowable sequence.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the upstream in an unbounded manner.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMapMaybe} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <R> the result value type
     * @param mapper the function that received each source value and transforms them into MaybeSources.
     * @return the new Flowable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> flatMapMaybe(Function<? super T, ? extends MaybeSource<? extends R>> mapper) {
        return flatMapMaybe(mapper, false, Integer.MAX_VALUE);
    }

    /**
     * Maps each element of the upstream Flowable into MaybeSources, subscribes to at most
     * {@code maxConcurrency} MaybeSources at a time and merges their onSuccess values,
     * in no particular order, into a single Flowable sequence, optionally delaying all errors.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>If {@code maxConcurrency == Integer.MAX_VALUE} the operator consumes the upstream in an unbounded manner.
     *  Otherwise the operator expects the upstream to honor backpressure. If the upstream doesn't support backpressure
     *  the operator behaves as if {@code maxConcurrency == Integer.MAX_VALUE} was used.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMapMaybe} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <R> the result value type
     * @param mapper the function that received each source value and transforms them into MaybeSources.
     * @param delayErrors if true errors from the upstream and inner MaybeSources are delayed until each of them
     * terminates.
     * @param maxConcurrency the maximum number of active subscriptions to the MaybeSources.
     * @return the new Flowable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> flatMapMaybe(Function<? super T, ? extends MaybeSource<? extends R>> mapper, boolean delayErrors, int maxConcurrency) {
        ObjectHelper.requireNonNull(mapper, "mapper is null");
        ObjectHelper.verifyPositive(maxConcurrency, "maxConcurrency");
        return RxJavaPlugins.onAssembly(new FlowableFlatMapMaybe<T, R>(this, mapper, delayErrors, maxConcurrency));
    }

    /**
     * Maps each element of the upstream Flowable into SingleSources, subscribes to all of them
     * and merges their onSuccess values, in no particular order, into a single Flowable sequence.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the upstream in an unbounded manner.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMapSingle} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <R> the result value type
     * @param mapper the function that received each source value and transforms them into SingleSources.
     * @return the new Flowable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> flatMapSingle(Function<? super T, ? extends SingleSource<? extends R>> mapper) {
        return flatMapSingle(mapper, false, Integer.MAX_VALUE);
    }

    /**
     * Maps each element of the upstream Flowable into SingleSources, subscribes to at most
     * {@code maxConcurrency} SingleSources at a time and merges their onSuccess values,
     * in no particular order, into a single Flowable sequence, optionally delaying all errors.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>If {@code maxConcurrency == Integer.MAX_VALUE} the operator consumes the upstream in an unbounded manner.
     *  Otherwise the operator expects the upstream to honor backpressure. If the upstream doesn't support backpressure
     *  the operator behaves as if {@code maxConcurrency == Integer.MAX_VALUE} was used.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code flatMapSingle} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <R> the result value type
     * @param mapper the function that received each source value and transforms them into SingleSources.
     * @param delayErrors if true errors from the upstream and inner SingleSources are delayed until each of them
     * terminates.
     * @param maxConcurrency the maximum number of active subscriptions to the SingleSources.
     * @return the new Flowable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> flatMapSingle(Function<? super T, ? extends SingleSource<? extends R>> mapper, boolean delayErrors, int maxConcurrency) {
        ObjectHelper.requireNonNull(mapper, "mapper is null");
        ObjectHelper.verifyPositive(maxConcurrency, "maxConcurrency");
        return RxJavaPlugins.onAssembly(new FlowableFlatMapSingle<T, R>(this, mapper, delayErrors, maxConcurrency));
    }

    /**
     * Subscribes to the {@link Publisher} and receives notifications for each element.
     * <p>
     * Alias to {@link #subscribe(Consumer)}
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner (i.e., no
     *  backpressure is applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code forEach} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onNext
     *            {@link Consumer} to execute for each item.
     * @return
     *            a Disposable that allows cancelling an asynchronous sequence
     * @throws NullPointerException
     *             if {@code onNext} is null
     * @see <a href="http://reactivex.io/documentation/operators/subscribe.html">ReactiveX operators documentation: Subscribe</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.NONE)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Disposable forEach(Consumer<? super T> onNext) {
        return subscribe(onNext);
    }

    /**
     * Subscribes to the {@link Publisher} and receives notifications for each element until the
     * onNext Predicate returns false.
     * <p>
     * If the Flowable emits an error, it is wrapped into an
     * {@link io.reactivex.exceptions.OnErrorNotImplementedException OnErrorNotImplementedException}
     * and routed to the RxJavaPlugins.onError handler.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner (i.e., no
     *  backpressure is applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code forEachWhile} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onNext
     *            {@link Predicate} to execute for each item.
     * @return
     *            a {@link Disposable} that allows cancelling an asynchronous sequence
     * @throws NullPointerException
     *             if {@code onNext} is null
     * @see <a href="http://reactivex.io/documentation/operators/subscribe.html">ReactiveX operators documentation: Subscribe</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.NONE)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Disposable forEachWhile(Predicate<? super T> onNext) {
        return forEachWhile(onNext, Functions.ON_ERROR_MISSING, Functions.EMPTY_ACTION);
    }

    /**
     * Subscribes to the {@link Publisher} and receives notifications for each element and error events until the
     * onNext Predicate returns false.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner (i.e., no
     *  backpressure is applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code forEachWhile} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onNext
     *            {@link Predicate} to execute for each item.
     * @param onError
     *            {@link Consumer} to execute when an error is emitted.
     * @return
     *            a {@link Disposable} that allows cancelling an asynchronous sequence
     * @throws NullPointerException
     *             if {@code onNext} is null, or
     *             if {@code onError} is null
     * @see <a href="http://reactivex.io/documentation/operators/subscribe.html">ReactiveX operators documentation: Subscribe</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.NONE)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Disposable forEachWhile(Predicate<? super T> onNext, Consumer<? super Throwable> onError) {
        return forEachWhile(onNext, onError, Functions.EMPTY_ACTION);
    }

    /**
     * Subscribes to the {@link Publisher} and receives notifications for each element and the terminal events until the
     * onNext Predicate returns false.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner (i.e., no
     *  backpressure is applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code forEachWhile} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onNext
     *            {@link Predicate} to execute for each item.
     * @param onError
     *            {@link Consumer} to execute when an error is emitted.
     * @param onComplete
     *            {@link Action} to execute when completion is signalled.
     * @return
     *            a {@link Disposable} that allows cancelling an asynchronous sequence
     * @throws NullPointerException
     *             if {@code onNext} is null, or
     *             if {@code onError} is null, or
     *             if {@code onComplete} is null
     * @see <a href="http://reactivex.io/documentation/operators/subscribe.html">ReactiveX operators documentation: Subscribe</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.NONE)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Disposable forEachWhile(final Predicate<? super T> onNext, final Consumer<? super Throwable> onError,
            final Action onComplete) {
        ObjectHelper.requireNonNull(onNext, "onNext is null");
        ObjectHelper.requireNonNull(onError, "onError is null");
        ObjectHelper.requireNonNull(onComplete, "onComplete is null");

        ForEachWhileSubscriber<T> s = new ForEachWhileSubscriber<T>(onNext, onError, onComplete);
        subscribe(s);
        return s;
    }

    /**
     * Groups the items emitted by a {@code Publisher} according to a specified criterion, and emits these
     * grouped items as {@link GroupedFlowable}s. The emitted {@code GroupedPublisher} allows only a single
     * {@link Subscriber} during its lifetime and if this {@code Subscriber} cancels before the
     * source terminates, the next emission by the source having the same key will trigger a new
     * {@code GroupedPublisher} emission.
     * <p>
     * <img width="640" height="360" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/groupBy.png" alt="">
     * <p>
     * <em>Note:</em> A {@link GroupedFlowable} will cache the items it is to emit until such time as it
     * is subscribed to. For this reason, in order to avoid memory leaks, you should not simply ignore those
     * {@code GroupedPublisher}s that do not concern you. Instead, you can signal to them that they may
     * discard their buffers by applying an operator like {@link #ignoreElements} to them.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>Both the returned and its inner {@code Publisher}s honor backpressure and the source {@code Publisher}
     *  is consumed in a bounded mode (i.e., requested a fixed amount upfront and replenished based on
     *  downstream consumption). Note that both the returned and its inner {@code Publisher}s use
     *  unbounded internal buffers and if the source {@code Publisher} doesn't honor backpressure, that <em>may</em>
     *  lead to {@code OutOfMemoryError}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code groupBy} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param keySelector
     *            a function that extracts the key for each item
     * @param <K>
     *            the key type
     * @return a {@code Publisher} that emits {@link GroupedFlowable}s, each of which corresponds to a
     *         unique key value and each of which emits those items from the source Publisher that share that
     *         key value
     * @see <a href="http://reactivex.io/documentation/operators/groupby.html">ReactiveX operators documentation: GroupBy</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <K> Flowable<GroupedFlowable<K, T>> groupBy(Function<? super T, ? extends K> keySelector) {
        return groupBy(keySelector, Functions.<T>identity(), false, bufferSize());
    }

    /**
     * Groups the items emitted by a {@code Publisher} according to a specified criterion, and emits these
     * grouped items as {@link GroupedFlowable}s. The emitted {@code GroupedPublisher} allows only a single
     * {@link Subscriber} during its lifetime and if this {@code Subscriber} cancels before the
     * source terminates, the next emission by the source having the same key will trigger a new
     * {@code GroupedPublisher} emission.
     * <p>
     * <img width="640" height="360" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/groupBy.png" alt="">
     * <p>
     * <em>Note:</em> A {@link GroupedFlowable} will cache the items it is to emit until such time as it
     * is subscribed to. For this reason, in order to avoid memory leaks, you should not simply ignore those
     * {@code GroupedPublisher}s that do not concern you. Instead, you can signal to them that they may
     * discard their buffers by applying an operator like {@link #ignoreElements} to them.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>Both the returned and its inner {@code Publisher}s honor backpressure and the source {@code Publisher}
     *  is consumed in a bounded mode (i.e., requested a fixed amount upfront and replenished based on
     *  downstream consumption). Note that both the returned and its inner {@code Publisher}s use
     *  unbounded internal buffers and if the source {@code Publisher} doesn't honor backpressure, that <em>may</em>
     *  lead to {@code OutOfMemoryError}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code groupBy} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param keySelector
     *            a function that extracts the key for each item
     * @param <K>
     *            the key type
     * @param delayError
     *            if true, the exception from the current Flowable is delayed in each group until that specific group emitted
     *            the normal values; if false, the exception bypasses values in the groups and is reported immediately.
     * @return a {@code Publisher} that emits {@link GroupedFlowable}s, each of which corresponds to a
     *         unique key value and each of which emits those items from the source Publisher that share that
     *         key value
     * @see <a href="http://reactivex.io/documentation/operators/groupby.html">ReactiveX operators documentation: GroupBy</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <K> Flowable<GroupedFlowable<K, T>> groupBy(Function<? super T, ? extends K> keySelector, boolean delayError) {
        return groupBy(keySelector, Functions.<T>identity(), delayError, bufferSize());
    }

    /**
     * Groups the items emitted by a {@code Publisher} according to a specified criterion, and emits these
     * grouped items as {@link GroupedFlowable}s. The emitted {@code GroupedPublisher} allows only a single
     * {@link Subscriber} during its lifetime and if this {@code Subscriber} cancels before the
     * source terminates, the next emission by the source having the same key will trigger a new
     * {@code GroupedPublisher} emission.
     * <p>
     * <img width="640" height="360" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/groupBy.png" alt="">
     * <p>
     * <em>Note:</em> A {@link GroupedFlowable} will cache the items it is to emit until such time as it
     * is subscribed to. For this reason, in order to avoid memory leaks, you should not simply ignore those
     * {@code GroupedPublisher}s that do not concern you. Instead, you can signal to them that they may
     * discard their buffers by applying an operator like {@link #ignoreElements} to them.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>Both the returned and its inner {@code Publisher}s honor backpressure and the source {@code Publisher}
     *  is consumed in a bounded mode (i.e., requested a fixed amount upfront and replenished based on
     *  downstream consumption). Note that both the returned and its inner {@code Publisher}s use
     *  unbounded internal buffers and if the source {@code Publisher} doesn't honor backpressure, that <em>may</em>
     *  lead to {@code OutOfMemoryError}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code groupBy} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param keySelector
     *            a function that extracts the key for each item
     * @param valueSelector
     *            a function that extracts the return element for each item
     * @param <K>
     *            the key type
     * @param <V>
     *            the element type
     * @return a {@code Publisher} that emits {@link GroupedFlowable}s, each of which corresponds to a
     *         unique key value and each of which emits those items from the source Publisher that share that
     *         key value
     * @see <a href="http://reactivex.io/documentation/operators/groupby.html">ReactiveX operators documentation: GroupBy</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <K, V> Flowable<GroupedFlowable<K, V>> groupBy(Function<? super T, ? extends K> keySelector,
            Function<? super T, ? extends V> valueSelector) {
        return groupBy(keySelector, valueSelector, false, bufferSize());
    }

    /**
     * Groups the items emitted by a {@code Publisher} according to a specified criterion, and emits these
     * grouped items as {@link GroupedFlowable}s. The emitted {@code GroupedPublisher} allows only a single
     * {@link Subscriber} during its lifetime and if this {@code Subscriber} cancels before the
     * source terminates, the next emission by the source having the same key will trigger a new
     * {@code GroupedPublisher} emission.
     * <p>
     * <img width="640" height="360" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/groupBy.png" alt="">
     * <p>
     * <em>Note:</em> A {@link GroupedFlowable} will cache the items it is to emit until such time as it
     * is subscribed to. For this reason, in order to avoid memory leaks, you should not simply ignore those
     * {@code GroupedPublisher}s that do not concern you. Instead, you can signal to them that they may
     * discard their buffers by applying an operator like {@link #ignoreElements} to them.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>Both the returned and its inner {@code Publisher}s honor backpressure and the source {@code Publisher}
     *  is consumed in a bounded mode (i.e., requested a fixed amount upfront and replenished based on
     *  downstream consumption). Note that both the returned and its inner {@code Publisher}s use
     *  unbounded internal buffers and if the source {@code Publisher} doesn't honor backpressure, that <em>may</em>
     *  lead to {@code OutOfMemoryError}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code groupBy} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param keySelector
     *            a function that extracts the key for each item
     * @param valueSelector
     *            a function that extracts the return element for each item
     * @param <K>
     *            the key type
     * @param <V>
     *            the element type
     * @param delayError
     *            if true, the exception from the current Flowable is delayed in each group until that specific group emitted
     *            the normal values; if false, the exception bypasses values in the groups and is reported immediately.
     * @return a {@code Publisher} that emits {@link GroupedFlowable}s, each of which corresponds to a
     *         unique key value and each of which emits those items from the source Publisher that share that
     *         key value
     * @see <a href="http://reactivex.io/documentation/operators/groupby.html">ReactiveX operators documentation: GroupBy</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <K, V> Flowable<GroupedFlowable<K, V>> groupBy(Function<? super T, ? extends K> keySelector,
            Function<? super T, ? extends V> valueSelector, boolean delayError) {
        return groupBy(keySelector, valueSelector, delayError, bufferSize());
    }

    /**
     * Groups the items emitted by a {@code Publisher} according to a specified criterion, and emits these
     * grouped items as {@link GroupedFlowable}s. The emitted {@code GroupedPublisher} allows only a single
     * {@link Subscriber} during its lifetime and if this {@code Subscriber} cancels before the
     * source terminates, the next emission by the source having the same key will trigger a new
     * {@code GroupedPublisher} emission.
     * <p>
     * <img width="640" height="360" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/groupBy.png" alt="">
     * <p>
     * <em>Note:</em> A {@link GroupedFlowable} will cache the items it is to emit until such time as it
     * is subscribed to. For this reason, in order to avoid memory leaks, you should not simply ignore those
     * {@code GroupedPublisher}s that do not concern you. Instead, you can signal to them that they may
     * discard their buffers by applying an operator like {@link #ignoreElements} to them.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>Both the returned and its inner {@code Publisher}s honor backpressure and the source {@code Publisher}
     *  is consumed in a bounded mode (i.e., requested a fixed amount upfront and replenished based on
     *  downstream consumption). Note that both the returned and its inner {@code Publisher}s use
     *  unbounded internal buffers and if the source {@code Publisher} doesn't honor backpressure, that <em>may</em>
     *  lead to {@code OutOfMemoryError}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code groupBy} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param keySelector
     *            a function that extracts the key for each item
     * @param valueSelector
     *            a function that extracts the return element for each item
     * @param delayError
     *            if true, the exception from the current Flowable is delayed in each group until that specific group emitted
     *            the normal values; if false, the exception bypasses values in the groups and is reported immediately.
     * @param bufferSize
     *            the hint for how many {@link GroupedFlowable}s and element in each {@link GroupedFlowable} should be buffered
     * @param <K>
     *            the key type
     * @param <V>
     *            the element type
     * @return a {@code Publisher} that emits {@link GroupedFlowable}s, each of which corresponds to a
     *         unique key value and each of which emits those items from the source Publisher that share that
     *         key value
     * @see <a href="http://reactivex.io/documentation/operators/groupby.html">ReactiveX operators documentation: GroupBy</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <K, V> Flowable<GroupedFlowable<K, V>> groupBy(Function<? super T, ? extends K> keySelector,
            Function<? super T, ? extends V> valueSelector,
            boolean delayError, int bufferSize) {
        ObjectHelper.requireNonNull(keySelector, "keySelector is null");
        ObjectHelper.requireNonNull(valueSelector, "valueSelector is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");

        return RxJavaPlugins.onAssembly(new FlowableGroupBy<T, K, V>(this, keySelector, valueSelector, bufferSize, delayError));
    }

    /**
     * Returns a Flowable that correlates two Publishers when they overlap in time and groups the results.
     * <p>
     * There are no guarantees in what order the items get combined when multiple
     * items from one or both source Publishers overlap.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/groupJoin.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't support backpressure and consumes all participating {@code Publisher}s in
     *  an unbounded mode (i.e., not applying any backpressure to them).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code groupJoin} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <TRight> the value type of the right Publisher source
     * @param <TLeftEnd> the element type of the left duration Publishers
     * @param <TRightEnd> the element type of the right duration Publishers
     * @param <R> the result type
     * @param other
     *            the other Publisher to correlate items from the source Publisher with
     * @param leftEnd
     *            a function that returns a Publisher whose emissions indicate the duration of the values of
     *            the source Publisher
     * @param rightEnd
     *            a function that returns a Publisher whose emissions indicate the duration of the values of
     *            the {@code right} Publisher
     * @param resultSelector
     *            a function that takes an item emitted by each Publisher and returns the value to be emitted
     *            by the resulting Publisher
     * @return a Flowable that emits items based on combining those items emitted by the source Publishers
     *         whose durations overlap
     * @see <a href="http://reactivex.io/documentation/operators/join.html">ReactiveX operators documentation: Join</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <TRight, TLeftEnd, TRightEnd, R> Flowable<R> groupJoin(
            Publisher<? extends TRight> other,
            Function<? super T, ? extends Publisher<TLeftEnd>> leftEnd,
            Function<? super TRight, ? extends Publisher<TRightEnd>> rightEnd,
            BiFunction<? super T, ? super Flowable<TRight>, ? extends R> resultSelector) {
        ObjectHelper.requireNonNull(other, "other is null");
        ObjectHelper.requireNonNull(leftEnd, "leftEnd is null");
        ObjectHelper.requireNonNull(rightEnd, "rightEnd is null");
        ObjectHelper.requireNonNull(resultSelector, "resultSelector is null");
        return RxJavaPlugins.onAssembly(new FlowableGroupJoin<T, TRight, TLeftEnd, TRightEnd, R>(
                this, other, leftEnd, rightEnd, resultSelector));
    }

    /**
     * Hides the identity of this Flowable and its Subscription.
     * <p>Allows hiding extra features such as {@link Processor}'s
     * {@link Subscriber} methods or preventing certain identity-based
     * optimizations (fusion).
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator is a pass-through for backpressure, the behavior is determined by the upstream's
     *  backpressure behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code hide} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @return the new Flowable instance
     *
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> hide() {
        return RxJavaPlugins.onAssembly(new FlowableHide<T>(this));
    }

    /**
     * Ignores all items emitted by the source Publisher and only calls {@code onComplete} or {@code onError}.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/ignoreElements.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator ignores backpressure as it doesn't emit any elements and consumes the source {@code Publisher}
     *  in an unbounded manner (i.e., no backpressure is applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code ignoreElements} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a Completable that only calls {@code onComplete} or {@code onError}, based on which one is
     *         called by the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/ignoreelements.html">ReactiveX operators documentation: IgnoreElements</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Completable ignoreElements() {
        return RxJavaPlugins.onAssembly(new FlowableIgnoreElementsCompletable<T>(this));
    }

    /**
     * Returns a Single that emits {@code true} if the source Publisher is empty, otherwise {@code false}.
     * <p>
     * In Rx.Net this is negated as the {@code any} Subscriber but we renamed this in RxJava to better match Java
     * naming idioms.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/isEmpty.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code isEmpty} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a Flowable that emits a Boolean
     * @see <a href="http://reactivex.io/documentation/operators/contains.html">ReactiveX operators documentation: Contains</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<Boolean> isEmpty() {
        return all(Functions.alwaysFalse());
    }

    /**
     * Correlates the items emitted by two Publishers based on overlapping durations.
     * <p>
     * There are no guarantees in what order the items get combined when multiple
     * items from one or both source Publishers overlap.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/join_.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't support backpressure and consumes all participating {@code Publisher}s in
     *  an unbounded mode (i.e., not applying any backpressure to them).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code join} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <TRight> the value type of the right Publisher source
     * @param <TLeftEnd> the element type of the left duration Publishers
     * @param <TRightEnd> the element type of the right duration Publishers
     * @param <R> the result type
     * @param other
     *            the second Publisher to join items from
     * @param leftEnd
     *            a function to select a duration for each item emitted by the source Publisher, used to
     *            determine overlap
     * @param rightEnd
     *            a function to select a duration for each item emitted by the {@code right} Publisher, used to
     *            determine overlap
     * @param resultSelector
     *            a function that computes an item to be emitted by the resulting Publisher for any two
     *            overlapping items emitted by the two Publishers
     * @return a Flowable that emits items correlating to items emitted by the source Publishers that have
     *         overlapping durations
     * @see <a href="http://reactivex.io/documentation/operators/join.html">ReactiveX operators documentation: Join</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <TRight, TLeftEnd, TRightEnd, R> Flowable<R> join(
            Publisher<? extends TRight> other,
            Function<? super T, ? extends Publisher<TLeftEnd>> leftEnd,
            Function<? super TRight, ? extends Publisher<TRightEnd>> rightEnd,
            BiFunction<? super T, ? super TRight, ? extends R> resultSelector) {
        ObjectHelper.requireNonNull(other, "other is null");
        ObjectHelper.requireNonNull(leftEnd, "leftEnd is null");
        ObjectHelper.requireNonNull(rightEnd, "rightEnd is null");
        ObjectHelper.requireNonNull(resultSelector, "resultSelector is null");
        return RxJavaPlugins.onAssembly(new FlowableJoin<T, TRight, TLeftEnd, TRightEnd, R>(
                this, other, leftEnd, rightEnd, resultSelector));
    }


    /**
     * Returns a Maybe that emits the last item emitted by this Flowable or completes if
     * this Flowable is empty.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/last.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code lastElement} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a new Maybe instance
     * @see <a href="http://reactivex.io/documentation/operators/last.html">ReactiveX operators documentation: Last</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Maybe<T> lastElement() {
        return RxJavaPlugins.onAssembly(new FlowableLastMaybe<T>(this));
    }

    /**
     * Returns a Single that emits only the last item emitted by this Flowable, or a default item
     * if this Flowable completes without emitting any items.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/lastOrDefault.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code last} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param defaultItem
     *            the default item to emit if the source Publisher is empty
     * @return the new Single instance
     * @see <a href="http://reactivex.io/documentation/operators/last.html">ReactiveX operators documentation: Last</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<T> last(T defaultItem) {
        ObjectHelper.requireNonNull(defaultItem, "defaultItem");
        return RxJavaPlugins.onAssembly(new FlowableLastSingle<T>(this, defaultItem));
    }

    /**
     * Returns a Single that emits only the last item emitted by this Flowable or signals
     * a {@link NoSuchElementException} if this Flowable is empty.
     * <p>
     * <img width="640" height="236" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/lastOrError.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code lastOrError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return the new Single instance
     * @see <a href="http://reactivex.io/documentation/operators/last.html">ReactiveX operators documentation: Last</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<T> lastOrError() {
        return RxJavaPlugins.onAssembly(new FlowableLastSingle<T>(this, null));
    }

    /**
     * <strong>This method requires advanced knowledge about building operators; please consider
     * other standard composition methods first;</strong>
     * Lifts a function to the current Publisher and returns a new Publisher that when subscribed to will pass
     * the values of the current Publisher through the Operator function.
     * <p>
     * In other words, this allows chaining Subscribers together on a Publisher for acting on the values within
     * the Publisher.
     * <p> {@code
     * Publisher.map(...).filter(...).take(5).lift(new OperatorA()).lift(new OperatorB(...)).subscribe()
     * }
     * <p>
     * If the operator you are creating is designed to act on the individual items emitted by a source
     * Publisher, use {@code lift}. If your operator is designed to transform the source Publisher as a whole
     * (for instance, by applying a particular set of existing RxJava operators to it) use {@link #compose}.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The {@code Operator} instance provided is responsible to be backpressure-aware or
     *  document the fact that the consumer of the returned {@code Publisher} has to apply one of
     *  the {@code onBackpressureXXX} operators.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code lift} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the output value type
     * @param lifter the Operator that implements the Publisher-operating function to be applied to the source
     *             Publisher
     * @return a Flowable that is the result of applying the lifted Operator to the source Publisher
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Implementing-Your-Own-Operators">RxJava wiki: Implementing Your Own Operators</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.SPECIAL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> lift(FlowableOperator<? extends R, ? super T> lifter) {
        ObjectHelper.requireNonNull(lifter, "lifter is null");
        return RxJavaPlugins.onAssembly(new FlowableLift<R, T>(this, lifter));
    }

    /**
     * Limits both the number of upstream items (after which the sequence completes)
     * and the total downstream request amount requested from the upstream to
     * possibly prevent the creation of excess items by the upstream.
     * <p>
     * The operator requests at most the given {@code count} of items from upstream even
     * if the downstream requests more than that. For example, given a {@code limit(5)},
     * if the downstream requests 1, a request of 1 is submitted to the upstream
     * and the operator remembers that only 4 items can be requested now on. A request
     * of 5 at this point will request 4 from the upstream and any subsequent requests will
     * be ignored.
     * <p>
     * Note that requests are negotiated on an operator boundary and {@code limit}'s amount
     * may not be preserved further upstream. For example,
     * {@code source.observeOn(Schedulers.computation()).limit(5)} will still request the
     * default (128) elements from the given {@code source}.
     * <p>
     * The main use of this operator is with sources that are async boundaries that
     * don't interfere with request amounts, such as certain {@code Flowable}-based
     * network endpoints that relay downstream request amounts unchanged and are, therefore,
     * prone to trigger excessive item creation/transmission over the network.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator requests a total of the given {@code count} items from the upstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code limit} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>

     * @param count the maximum number of items and the total request amount, non-negative.
     *              Zero will immediately cancel the upstream on subscription and complete
     *              the downstream.
     * @return the new Flowable instance
     * @see #take(long)
     * @see #rebatchRequests(int)
     * @since 2.1.6 - experimental
     */
    @Experimental
    @BackpressureSupport(BackpressureKind.SPECIAL)
    @SchedulerSupport(SchedulerSupport.NONE)
    @CheckReturnValue
    public final Flowable<T> limit(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("count >= 0 required but it was " + count);
        }
        return RxJavaPlugins.onAssembly(new FlowableLimit<T>(this, count));
    }

    /**
     * Returns a Flowable that applies a specified function to each item emitted by the source Publisher and
     * emits the results of these function applications.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/map.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code map} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the output type
     * @param mapper
     *            a function to apply to each item emitted by the Publisher
     * @return a Flowable that emits the items from the source Publisher, transformed by the specified
     *         function
     * @see <a href="http://reactivex.io/documentation/operators/map.html">ReactiveX operators documentation: Map</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> map(Function<? super T, ? extends R> mapper) {
        ObjectHelper.requireNonNull(mapper, "mapper is null");
        return RxJavaPlugins.onAssembly(new FlowableMap<T, R>(this, mapper));
    }

    /**
     * Returns a Flowable that represents all of the emissions <em>and</em> notifications from the source
     * Publisher into emissions marked with their original types within {@link Notification} objects.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/materialize.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and expects it from the source {@code Publisher}.
     *  If this expectation is violated, the operator <em>may</em> throw an {@code IllegalStateException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code materialize} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a Flowable that emits items that are the result of materializing the items and notifications
     *         of the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/materialize-dematerialize.html">ReactiveX operators documentation: Materialize</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<Notification<T>> materialize() {
        return RxJavaPlugins.onAssembly(new FlowableMaterialize<T>(this));
    }

    /**
     * Flattens this and another Publisher into a single Publisher, without any transformation.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/merge.png" alt="">
     * <p>
     * You can combine items emitted by multiple Publishers so that they appear as a single Publisher, by
     * using the {@code mergeWith} method.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. This and the other {@code Publisher}s are expected to honor
     *  backpressure; if violated, the operator <em>may</em> signal {@code MissingBackpressureException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code mergeWith} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param other
     *            a Publisher to be merged
     * @return a Flowable that emits all of the items emitted by the source Publishers
     * @see <a href="http://reactivex.io/documentation/operators/merge.html">ReactiveX operators documentation: Merge</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> mergeWith(Publisher<? extends T> other) {
        ObjectHelper.requireNonNull(other, "other is null");
        return merge(this, other);
    }

    /**
     * Modifies a Publisher to perform its emissions and notifications on a specified {@link Scheduler},
     * asynchronously with a bounded buffer of {@link #bufferSize()} slots.
     *
     * <p>Note that onError notifications will cut ahead of onNext notifications on the emission thread if Scheduler is truly
     * asynchronous. If strict event ordering is required, consider using the {@link #observeOn(Scheduler, boolean)} overload.
     * <p>
     * <img width="640" height="308" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/observeOn.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator honors backpressure from downstream and expects it from the source {@code Publisher}. Violating this
     *  expectation will lead to {@code MissingBackpressureException}. This is the most common operator where the exception
     *  pops up; look for sources up the chain that don't support backpressure,
     *  such as {@code interval}, {@code timer}, {code PublishSubject} or {@code BehaviorSubject} and apply any
     *  of the {@code onBackpressureXXX} operators <strong>before</strong> applying {@code observeOn} itself.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param scheduler
     *            the {@link Scheduler} to notify {@link Subscriber}s on
     * @return the source Publisher modified so that its {@link Subscriber}s are notified on the specified
     *         {@link Scheduler}
     * @see <a href="http://reactivex.io/documentation/operators/observeon.html">ReactiveX operators documentation: ObserveOn</a>
     * @see <a href="http://www.grahamlea.com/2014/07/rxjava-threading-examples/">RxJava Threading Examples</a>
     * @see #subscribeOn
     * @see #observeOn(Scheduler, boolean)
     * @see #observeOn(Scheduler, boolean, int)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> observeOn(Scheduler scheduler) {
        return observeOn(scheduler, false, bufferSize());
    }

    /**
     * Modifies a Publisher to perform its emissions and notifications on a specified {@link Scheduler},
     * asynchronously with a bounded buffer and optionally delays onError notifications.
     * <p>
     * <img width="640" height="308" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/observeOn.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator honors backpressure from downstream and expects it from the source {@code Publisher}. Violating this
     *  expectation will lead to {@code MissingBackpressureException}. This is the most common operator where the exception
     *  pops up; look for sources up the chain that don't support backpressure,
     *  such as {@code interval}, {@code timer}, {code PublishSubject} or {@code BehaviorSubject} and apply any
     *  of the {@code onBackpressureXXX} operators <strong>before</strong> applying {@code observeOn} itself.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param scheduler
     *            the {@link Scheduler} to notify {@link Subscriber}s on
     * @param delayError
     *            indicates if the onError notification may not cut ahead of onNext notification on the other side of the
     *            scheduling boundary. If true a sequence ending in onError will be replayed in the same order as was received
     *            from upstream
     * @return the source Publisher modified so that its {@link Subscriber}s are notified on the specified
     *         {@link Scheduler}
     * @see <a href="http://reactivex.io/documentation/operators/observeon.html">ReactiveX operators documentation: ObserveOn</a>
     * @see <a href="http://www.grahamlea.com/2014/07/rxjava-threading-examples/">RxJava Threading Examples</a>
     * @see #subscribeOn
     * @see #observeOn(Scheduler)
     * @see #observeOn(Scheduler, boolean, int)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> observeOn(Scheduler scheduler, boolean delayError) {
        return observeOn(scheduler, delayError, bufferSize());
    }

    /**
     * Modifies a Publisher to perform its emissions and notifications on a specified {@link Scheduler},
     * asynchronously with a bounded buffer of configurable size and optionally delays onError notifications.
     * <p>
     * <img width="640" height="308" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/observeOn.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator honors backpressure from downstream and expects it from the source {@code Publisher}. Violating this
     *  expectation will lead to {@code MissingBackpressureException}. This is the most common operator where the exception
     *  pops up; look for sources up the chain that don't support backpressure,
     *  such as {@code interval}, {@code timer}, {code PublishSubject} or {@code BehaviorSubject} and apply any
     *  of the {@code onBackpressureXXX} operators <strong>before</strong> applying {@code observeOn} itself.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param scheduler
     *            the {@link Scheduler} to notify {@link Subscriber}s on
     * @param delayError
     *            indicates if the onError notification may not cut ahead of onNext notification on the other side of the
     *            scheduling boundary. If true a sequence ending in onError will be replayed in the same order as was received
     *            from upstream
     * @param bufferSize the size of the buffer.
     * @return the source Publisher modified so that its {@link Subscriber}s are notified on the specified
     *         {@link Scheduler}
     * @see <a href="http://reactivex.io/documentation/operators/observeon.html">ReactiveX operators documentation: ObserveOn</a>
     * @see <a href="http://www.grahamlea.com/2014/07/rxjava-threading-examples/">RxJava Threading Examples</a>
     * @see #subscribeOn
     * @see #observeOn(Scheduler)
     * @see #observeOn(Scheduler, boolean)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> observeOn(Scheduler scheduler, boolean delayError, int bufferSize) {
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return RxJavaPlugins.onAssembly(new FlowableObserveOn<T>(this, scheduler, delayError, bufferSize));
    }

    /**
     * Filters the items emitted by a Publisher, only emitting those of the specified type.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/ofClass.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code ofType} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the output type
     * @param clazz
     *            the class type to filter the items emitted by the source Publisher
     * @return a Flowable that emits items from the source Publisher of type {@code clazz}
     * @see <a href="http://reactivex.io/documentation/operators/filter.html">ReactiveX operators documentation: Filter</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U> Flowable<U> ofType(final Class<U> clazz) {
        ObjectHelper.requireNonNull(clazz, "clazz is null");
        return filter(Functions.isInstanceOf(clazz)).cast(clazz);
    }

    /**
     * Instructs a Publisher that is emitting items faster than its Subscriber can consume them to buffer these
     * items indefinitely until they can be emitted.
     * <p>
     * <img width="640" height="300" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/bp.obp.buffer.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded
     *  manner (i.e., not applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onBackpressureBuffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return the source Publisher modified to buffer items to the extent system resources allow
     * @see <a href="http://reactivex.io/documentation/operators/backpressure.html">ReactiveX operators documentation: backpressure operators</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onBackpressureBuffer() {
        return onBackpressureBuffer(bufferSize(), false, true);
    }

    /**
     * Instructs a Publisher that is emitting items faster than its Subscriber can consume them to buffer these
     * items indefinitely until they can be emitted.
     * <p>
     * <img width="640" height="300" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/bp.obp.buffer.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded
     *  manner (i.e., not applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onBackpressureBuffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param delayError
     *                if true, an exception from the current Flowable is delayed until all buffered elements have been
     *                consumed by the downstream; if false, an exception is immediately signalled to the downstream, skipping
     *                any buffered element
     * @return the source Publisher modified to buffer items to the extent system resources allow
     * @see <a href="http://reactivex.io/documentation/operators/backpressure.html">ReactiveX operators documentation: backpressure operators</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onBackpressureBuffer(boolean delayError) {
        return onBackpressureBuffer(bufferSize(), delayError, true);
    }

    /**
     * Instructs a Publisher that is emitting items faster than its Subscriber can consume them to buffer up to
     * a given amount of items until they can be emitted. The resulting Publisher will {@code onError} emitting
     * a {@code BufferOverflowException} as soon as the buffer's capacity is exceeded, dropping all undelivered
     * items, and cancelling the source.
     * <p>
     * <img width="640" height="300" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/bp.obp.buffer.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded
     *  manner (i.e., not applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onBackpressureBuffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param capacity number of slots available in the buffer.
     * @return the source {@code Publisher} modified to buffer items up to the given capacity.
     * @see <a href="http://reactivex.io/documentation/operators/backpressure.html">ReactiveX operators documentation: backpressure operators</a>
     * @since 1.1.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onBackpressureBuffer(int capacity) {
        return onBackpressureBuffer(capacity, false, false);
    }

    /**
     * Instructs a Publisher that is emitting items faster than its Subscriber can consume them to buffer up to
     * a given amount of items until they can be emitted. The resulting Publisher will {@code onError} emitting
     * a {@code BufferOverflowException} as soon as the buffer's capacity is exceeded, dropping all undelivered
     * items, and cancelling the source.
     * <p>
     * <img width="640" height="300" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/bp.obp.buffer.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded
     *  manner (i.e., not applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onBackpressureBuffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param capacity number of slots available in the buffer.
     * @param delayError
     *                if true, an exception from the current Flowable is delayed until all buffered elements have been
     *                consumed by the downstream; if false, an exception is immediately signalled to the downstream, skipping
     *                any buffered element
     * @return the source {@code Publisher} modified to buffer items up to the given capacity.
     * @see <a href="http://reactivex.io/documentation/operators/backpressure.html">ReactiveX operators documentation: backpressure operators</a>
     * @since 1.1.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onBackpressureBuffer(int capacity, boolean delayError) {
        return onBackpressureBuffer(capacity, delayError, false);
    }

    /**
     * Instructs a Publisher that is emitting items faster than its Subscriber can consume them to buffer up to
     * a given amount of items until they can be emitted. The resulting Publisher will {@code onError} emitting
     * a {@code BufferOverflowException} as soon as the buffer's capacity is exceeded, dropping all undelivered
     * items, and cancelling the source.
     * <p>
     * <img width="640" height="300" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/bp.obp.buffer.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded
     *  manner (i.e., not applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onBackpressureBuffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param capacity number of slots available in the buffer.
     * @param delayError
     *                if true, an exception from the current Flowable is delayed until all buffered elements have been
     *                consumed by the downstream; if false, an exception is immediately signalled to the downstream, skipping
     *                any buffered element
     * @param unbounded
     *                if true, the capacity value is interpreted as the internal "island" size of the unbounded buffer
     * @return the source {@code Publisher} modified to buffer items up to the given capacity.
     * @see <a href="http://reactivex.io/documentation/operators/backpressure.html">ReactiveX operators documentation: backpressure operators</a>
     * @since 1.1.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.SPECIAL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onBackpressureBuffer(int capacity, boolean delayError, boolean unbounded) {
        ObjectHelper.verifyPositive(capacity, "bufferSize");
        return RxJavaPlugins.onAssembly(new FlowableOnBackpressureBuffer<T>(this, capacity, unbounded, delayError, Functions.EMPTY_ACTION));
    }

    /**
     * Instructs a Publisher that is emitting items faster than its Subscriber can consume them to buffer up to
     * a given amount of items until they can be emitted. The resulting Publisher will {@code onError} emitting
     * a {@code BufferOverflowException} as soon as the buffer's capacity is exceeded, dropping all undelivered
     * items, cancelling the source, and notifying the producer with {@code onOverflow}.
     * <p>
     * <img width="640" height="300" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/bp.obp.buffer.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded
     *  manner (i.e., not applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onBackpressureBuffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param capacity number of slots available in the buffer.
     * @param delayError
     *                if true, an exception from the current Flowable is delayed until all buffered elements have been
     *                consumed by the downstream; if false, an exception is immediately signalled to the downstream, skipping
     *                any buffered element
     * @param unbounded
     *                if true, the capacity value is interpreted as the internal "island" size of the unbounded buffer
     * @param onOverflow action to execute if an item needs to be buffered, but there are no available slots.  Null is allowed.
     * @return the source {@code Publisher} modified to buffer items up to the given capacity
     * @see <a href="http://reactivex.io/documentation/operators/backpressure.html">ReactiveX operators documentation: backpressure operators</a>
     * @since 1.1.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.SPECIAL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onBackpressureBuffer(int capacity, boolean delayError, boolean unbounded,
            Action onOverflow) {
        ObjectHelper.requireNonNull(onOverflow, "onOverflow is null");
        ObjectHelper.verifyPositive(capacity, "capacity");
        return RxJavaPlugins.onAssembly(new FlowableOnBackpressureBuffer<T>(this, capacity, unbounded, delayError, onOverflow));
    }

    /**
     * Instructs a Publisher that is emitting items faster than its Subscriber can consume them to buffer up to
     * a given amount of items until they can be emitted. The resulting Publisher will {@code onError} emitting
     * a {@code BufferOverflowException} as soon as the buffer's capacity is exceeded, dropping all undelivered
     * items, cancelling the source, and notifying the producer with {@code onOverflow}.
     * <p>
     * <img width="640" height="300" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/bp.obp.buffer.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded
     *  manner (i.e., not applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onBackpressureBuffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param capacity number of slots available in the buffer.
     * @param onOverflow action to execute if an item needs to be buffered, but there are no available slots.  Null is allowed.
     * @return the source {@code Publisher} modified to buffer items up to the given capacity
     * @see <a href="http://reactivex.io/documentation/operators/backpressure.html">ReactiveX operators documentation: backpressure operators</a>
     * @since 1.1.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onBackpressureBuffer(int capacity, Action onOverflow) {
        return onBackpressureBuffer(capacity, false, false, onOverflow);
    }

    /**
     * Instructs a Publisher that is emitting items faster than its Subscriber can consume them to buffer up to
     * a given amount of items until they can be emitted. The resulting Publisher will behave as determined
     * by {@code overflowStrategy} if the buffer capacity is exceeded.
     *
     * <ul>
     *     <li>{@code BackpressureOverflow.Strategy.ON_OVERFLOW_ERROR} (default) will {@code onError} dropping all undelivered items,
     *     cancelling the source, and notifying the producer with {@code onOverflow}. </li>
     *     <li>{@code BackpressureOverflow.Strategy.ON_OVERFLOW_DROP_LATEST} will drop any new items emitted by the producer while
     *     the buffer is full, without generating any {@code onError}.  Each drop will however invoke {@code onOverflow}
     *     to signal the overflow to the producer.</li>
     *     <li>{@code BackpressureOverflow.Strategy.ON_OVERFLOW_DROP_OLDEST} will drop the oldest items in the buffer in order to make
     *     room for newly emitted ones. Overflow will not generate an{@code onError}, but each drop will invoke
     *     {@code onOverflow} to signal the overflow to the producer.</li>
     * </ul>
     *
     * <p>
     * <img width="640" height="300" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/bp.obp.buffer.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded
     *  manner (i.e., not applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onBackpressureBuffer} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param capacity number of slots available in the buffer.
     * @param onOverflow action to execute if an item needs to be buffered, but there are no available slots.  Null is allowed.
     * @param overflowStrategy how should the {@code Publisher} react to buffer overflows.  Null is not allowed.
     * @return the source {@code Flowable} modified to buffer items up to the given capacity
     * @see <a href="http://reactivex.io/documentation/operators/backpressure.html">ReactiveX operators documentation: backpressure operators</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.SPECIAL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onBackpressureBuffer(long capacity, Action onOverflow, BackpressureOverflowStrategy overflowStrategy) {
        ObjectHelper.requireNonNull(overflowStrategy, "strategy is null");
        ObjectHelper.verifyPositive(capacity, "capacity");
        return RxJavaPlugins.onAssembly(new FlowableOnBackpressureBufferStrategy<T>(this, capacity, onOverflow, overflowStrategy));
    }

    /**
     * Instructs a Publisher that is emitting items faster than its Subscriber can consume them to discard,
     * rather than emit, those items that its Subscriber is not prepared to observe.
     * <p>
     * <img width="640" height="245" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/bp.obp.drop.png" alt="">
     * <p>
     * If the downstream request count hits 0 then the Publisher will refrain from calling {@code onNext} until
     * the Subscriber invokes {@code request(n)} again to increase the request count.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded
     *  manner (i.e., not applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onBackpressureDrop} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return the source Publisher modified to drop {@code onNext} notifications on overflow
     * @see <a href="http://reactivex.io/documentation/operators/backpressure.html">ReactiveX operators documentation: backpressure operators</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onBackpressureDrop() {
        return RxJavaPlugins.onAssembly(new FlowableOnBackpressureDrop<T>(this));
    }

    /**
     * Instructs a Publisher that is emitting items faster than its Subscriber can consume them to discard,
     * rather than emit, those items that its Subscriber is not prepared to observe.
     * <p>
     * <img width="640" height="245" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/bp.obp.drop.png" alt="">
     * <p>
     * If the downstream request count hits 0 then the Publisher will refrain from calling {@code onNext} until
     * the Subscriber invokes {@code request(n)} again to increase the request count.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded
     *  manner (i.e., not applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onBackpressureDrop} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onDrop the action to invoke for each item dropped. onDrop action should be fast and should never block.
     * @return the source Publisher modified to drop {@code onNext} notifications on overflow
     * @see <a href="http://reactivex.io/documentation/operators/backpressure.html">ReactiveX operators documentation: backpressure operators</a>
     * @since 1.1.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onBackpressureDrop(Consumer<? super T> onDrop) {
        ObjectHelper.requireNonNull(onDrop, "onDrop is null");
        return RxJavaPlugins.onAssembly(new FlowableOnBackpressureDrop<T>(this, onDrop));
    }

    /**
     * Instructs a Publisher that is emitting items faster than its Subscriber can consume them to
     * hold onto the latest value and emit that on request.
     * <p>
     * <img width="640" height="245" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/bp.obp.latest.png" alt="">
     * <p>
     * Its behavior is logically equivalent to {@code blockingLatest()} with the exception that
     * the downstream is not blocking while requesting more values.
     * <p>
     * Note that if the upstream Publisher does support backpressure, this operator ignores that capability
     * and doesn't propagate any backpressure requests from downstream.
     * <p>
     * Note that due to the nature of how backpressure requests are propagated through subscribeOn/observeOn,
     * requesting more than 1 from downstream doesn't guarantee a continuous delivery of onNext events.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an unbounded
     *  manner (i.e., not applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onBackpressureLatest} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return the source Publisher modified so that it emits the most recently-received item upon request
     * @since 1.1.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onBackpressureLatest() {
        return RxJavaPlugins.onAssembly(new FlowableOnBackpressureLatest<T>(this));
    }

    /**
     * Instructs a Publisher to pass control to another Publisher rather than invoking
     * {@link Subscriber#onError onError} if it encounters an error.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/onErrorResumeNext.png" alt="">
     * <p>
     * By default, when a Publisher encounters an error that prevents it from emitting the expected item to
     * its {@link Subscriber}, the Publisher invokes its Subscriber's {@code onError} method, and then quits
     * without invoking any more of its Subscriber's methods. The {@code onErrorResumeNext} method changes this
     * behavior. If you pass a function that returns a Publisher ({@code resumeFunction}) to
     * {@code onErrorResumeNext}, if the original Publisher encounters an error, instead of invoking its
     * Subscriber's {@code onError} method, it will instead relinquish control to the Publisher returned from
     * {@code resumeFunction}, which will invoke the Subscriber's {@link Subscriber#onNext onNext} method if it is
     * able to do so. In such a case, because no Publisher necessarily invokes {@code onError}, the Subscriber
     * may never know that an error happened.
     * <p>
     * You can use this to prevent errors from propagating or to supply fallback data should errors be
     * encountered.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. This and the resuming {@code Publisher}s
     *  are expected to honor backpressure as well.
     *  If any of them violate this expectation, the operator <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes or
     *  a {@code MissingBackpressureException} is signalled somewhere downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onErrorResumeNext} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param resumeFunction
     *            a function that returns a Publisher that will take over if the source Publisher encounters
     *            an error
     * @return the original Publisher, with appropriately modified behavior
     * @see <a href="http://reactivex.io/documentation/operators/catch.html">ReactiveX operators documentation: Catch</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onErrorResumeNext(Function<? super Throwable, ? extends Publisher<? extends T>> resumeFunction) {
        ObjectHelper.requireNonNull(resumeFunction, "resumeFunction is null");
        return RxJavaPlugins.onAssembly(new FlowableOnErrorNext<T>(this, resumeFunction, false));
    }

    /**
     * Instructs a Publisher to pass control to another Publisher rather than invoking
     * {@link Subscriber#onError onError} if it encounters an error.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/onErrorResumeNext.png" alt="">
     * <p>
     * By default, when a Publisher encounters an error that prevents it from emitting the expected item to
     * its {@link Subscriber}, the Publisher invokes its Subscriber's {@code onError} method, and then quits
     * without invoking any more of its Subscriber's methods. The {@code onErrorResumeNext} method changes this
     * behavior. If you pass another Publisher ({@code resumeSequence}) to a Publisher's
     * {@code onErrorResumeNext} method, if the original Publisher encounters an error, instead of invoking its
     * Subscriber's {@code onError} method, it will instead relinquish control to {@code resumeSequence} which
     * will invoke the Subscriber's {@link Subscriber#onNext onNext} method if it is able to do so. In such a case,
     * because no Publisher necessarily invokes {@code onError}, the Subscriber may never know that an error
     * happened.
     * <p>
     * You can use this to prevent errors from propagating or to supply fallback data should errors be
     * encountered.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. This and the resuming {@code Publisher}s
     *  are expected to honor backpressure as well.
     *  If any of them violate this expectation, the operator <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes or
     *  {@code MissingBackpressureException} is signalled somewhere downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onErrorResumeNext} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param next
     *            the next Publisher source that will take over if the source Publisher encounters
     *            an error
     * @return the original Publisher, with appropriately modified behavior
     * @see <a href="http://reactivex.io/documentation/operators/catch.html">ReactiveX operators documentation: Catch</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onErrorResumeNext(final Publisher<? extends T> next) {
        ObjectHelper.requireNonNull(next, "next is null");
        return onErrorResumeNext(Functions.justFunction(next));
    }

    /**
     * Instructs a Publisher to emit an item (returned by a specified function) rather than invoking
     * {@link Subscriber#onError onError} if it encounters an error.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/onErrorReturn.png" alt="">
     * <p>
     * By default, when a Publisher encounters an error that prevents it from emitting the expected item to
     * its {@link Subscriber}, the Publisher invokes its Subscriber's {@code onError} method, and then quits
     * without invoking any more of its Subscriber's methods. The {@code onErrorReturn} method changes this
     * behavior. If you pass a function ({@code resumeFunction}) to a Publisher's {@code onErrorReturn}
     * method, if the original Publisher encounters an error, instead of invoking its Subscriber's
     * {@code onError} method, it will instead emit the return value of {@code resumeFunction}.
     * <p>
     * You can use this to prevent errors from propagating or to supply fallback data should errors be
     * encountered.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s is expected to honor
     *  backpressure as well. If it this expectation is violated, the operator <em>may</em> throw
     *  {@code IllegalStateException} when the source {@code Publisher} completes or
     *  {@code MissingBackpressureException} is signalled somewhere downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onErrorReturn} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param valueSupplier
     *            a function that returns a single value that will be emitted along with a regular onComplete in case
     *            the current Flowable signals an onError event
     * @return the original Publisher with appropriately modified behavior
     * @see <a href="http://reactivex.io/documentation/operators/catch.html">ReactiveX operators documentation: Catch</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onErrorReturn(Function<? super Throwable, ? extends T> valueSupplier) {
        ObjectHelper.requireNonNull(valueSupplier, "valueSupplier is null");
        return RxJavaPlugins.onAssembly(new FlowableOnErrorReturn<T>(this, valueSupplier));
    }

    /**
     * Instructs a Publisher to emit an item (returned by a specified function) rather than invoking
     * {@link Subscriber#onError onError} if it encounters an error.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/onErrorReturn.png" alt="">
     * <p>
     * By default, when a Publisher encounters an error that prevents it from emitting the expected item to
     * its {@link Subscriber}, the Publisher invokes its Subscriber's {@code onError} method, and then quits
     * without invoking any more of its Subscriber's methods. The {@code onErrorReturn} method changes this
     * behavior. If you pass a function ({@code resumeFunction}) to a Publisher's {@code onErrorReturn}
     * method, if the original Publisher encounters an error, instead of invoking its Subscriber's
     * {@code onError} method, it will instead emit the return value of {@code resumeFunction}.
     * <p>
     * You can use this to prevent errors from propagating or to supply fallback data should errors be
     * encountered.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}s is expected to honor
     *  backpressure as well. If it this expectation is violated, the operator <em>may</em> throw
     *  {@code IllegalStateException} when the source {@code Publisher} completes or
     *  {@code MissingBackpressureException} is signalled somewhere downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onErrorReturnItem} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param item
     *            the value that is emitted along with a regular onComplete in case the current
     *            Flowable signals an exception
     * @return the original Publisher with appropriately modified behavior
     * @see <a href="http://reactivex.io/documentation/operators/catch.html">ReactiveX operators documentation: Catch</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onErrorReturnItem(final T item) {
        ObjectHelper.requireNonNull(item, "item is null");
        return onErrorReturn(Functions.justFunction(item));
    }

    /**
     * Instructs a Publisher to pass control to another Publisher rather than invoking
     * {@link Subscriber#onError onError} if it encounters an {@link java.lang.Exception}.
     * <p>
     * This differs from {@link #onErrorResumeNext} in that this one does not handle {@link java.lang.Throwable}
     * or {@link java.lang.Error} but lets those continue through.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/onExceptionResumeNextViaPublisher.png" alt="">
     * <p>
     * By default, when a Publisher encounters an exception that prevents it from emitting the expected item
     * to its {@link Subscriber}, the Publisher invokes its Subscriber's {@code onError} method, and then quits
     * without invoking any more of its Subscriber's methods. The {@code onExceptionResumeNext} method changes
     * this behavior. If you pass another Publisher ({@code resumeSequence}) to a Publisher's
     * {@code onExceptionResumeNext} method, if the original Publisher encounters an exception, instead of
     * invoking its Subscriber's {@code onError} method, it will instead relinquish control to
     * {@code resumeSequence} which will invoke the Subscriber's {@link Subscriber#onNext onNext} method if it is
     * able to do so. In such a case, because no Publisher necessarily invokes {@code onError}, the Subscriber
     * may never know that an exception happened.
     * <p>
     * You can use this to prevent exceptions from propagating or to supply fallback data should exceptions be
     * encountered.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. This and the resuming {@code Publisher}s
     *  are expected to honor backpressure as well.
     *  If any of them violate this expectation, the operator <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes or
     *  {@code MissingBackpressureException} is signalled somewhere downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onExceptionResumeNext} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param next
     *            the next Publisher that will take over if the source Publisher encounters
     *            an exception
     * @return the original Publisher, with appropriately modified behavior
     * @see <a href="http://reactivex.io/documentation/operators/catch.html">ReactiveX operators documentation: Catch</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onExceptionResumeNext(final Publisher<? extends T> next) {
        ObjectHelper.requireNonNull(next, "next is null");
        return RxJavaPlugins.onAssembly(new FlowableOnErrorNext<T>(this, Functions.justFunction(next), true));
    }

    /**
     * Nulls out references to the upstream producer and downstream Subscriber if
     * the sequence is terminated or downstream cancels.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code onTerminateDetach} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @return a Flowable which nulls out references to the upstream producer and downstream Subscriber if
     * the sequence is terminated or downstream cancels
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> onTerminateDetach() {
        return RxJavaPlugins.onAssembly(new FlowableDetach<T>(this));
    }

    /**
     * Parallelizes the flow by creating multiple 'rails' (equal to the number of CPUs)
     * and dispatches the upstream items to them in a round-robin fashion.
     * <p>
     * Note that the rails don't execute in parallel on their own and one needs to
     * apply {@link ParallelFlowable#runOn(Scheduler)} to specify the Scheduler where
     * each rail will execute.
     * <p>
     * To merge the parallel 'rails' back into a single sequence, use {@link ParallelFlowable#sequential()}.
     * <p>
     * <img width="640" height="547" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/flowable.parallel.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator requires the upstream to honor backpressure and each 'rail' honors backpressure
     *  as well.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code parallel} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * <p>History: 2.0.5 - experimental
     * @return the new ParallelFlowable instance
     * @since 2.1 - beta
     */
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    @CheckReturnValue
    @Beta
    public final ParallelFlowable<T> parallel() {
        return ParallelFlowable.from(this);
    }

    /**
     * Parallelizes the flow by creating the specified number of 'rails'
     * and dispatches the upstream items to them in a round-robin fashion.
     * <p>
     * Note that the rails don't execute in parallel on their own and one needs to
     * apply {@link ParallelFlowable#runOn(Scheduler)} to specify the Scheduler where
     * each rail will execute.
     * <p>
     * To merge the parallel 'rails' back into a single sequence, use {@link ParallelFlowable#sequential()}.
     * <p>
     * <img width="640" height="547" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/flowable.parallel.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator requires the upstream to honor backpressure and each 'rail' honors backpressure
     *  as well.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code parallel} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * <p>History: 2.0.5 - experimental
     * @param parallelism the number of 'rails' to use
     * @return the new ParallelFlowable instance
     * @since 2.1 - beta
     */
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    @CheckReturnValue
    @Beta
    public final ParallelFlowable<T> parallel(int parallelism) {
        ObjectHelper.verifyPositive(parallelism, "parallelism");
        return ParallelFlowable.from(this, parallelism);
    }

    /**
     * Parallelizes the flow by creating the specified number of 'rails'
     * and dispatches the upstream items to them in a round-robin fashion and
     * uses the defined per-'rail' prefetch amount.
     * <p>
     * Note that the rails don't execute in parallel on their own and one needs to
     * apply {@link ParallelFlowable#runOn(Scheduler)} to specify the Scheduler where
     * each rail will execute.
     * <p>
     * To merge the parallel 'rails' back into a single sequence, use {@link ParallelFlowable#sequential()}.
     * <p>
     * <img width="640" height="547" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/flowable.parallel.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator requires the upstream to honor backpressure and each 'rail' honors backpressure
     *  as well.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code parallel} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * <p>History: 2.0.5 - experimental
     * @param parallelism the number of 'rails' to use
     * @param prefetch the number of items each 'rail' should prefetch
     * @return the new ParallelFlowable instance
     * @since 2.1 - beta
     */
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    @CheckReturnValue
    @Beta
    public final ParallelFlowable<T> parallel(int parallelism, int prefetch) {
        ObjectHelper.verifyPositive(parallelism, "parallelism");
        ObjectHelper.verifyPositive(prefetch, "prefetch");
        return ParallelFlowable.from(this, parallelism, prefetch);
    }

    /**
     * Returns a {@link ConnectableFlowable}, which is a variety of Publisher that waits until its
     * {@link ConnectableFlowable#connect connect} method is called before it begins emitting items to those
     * {@link Subscriber}s that have subscribed to it.
     * <p>
     * <img width="640" height="510" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/publishConnect.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code ConnectableFlowable} honors backpressure for each of its {@code Subscriber}s
     *  and expects the source {@code Publisher} to honor backpressure as well. If this expectation is violated,
     *  the operator will signal a {@code MissingBackpressureException} to its {@code Subscriber}s and disconnect.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code publish} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a {@link ConnectableFlowable} that upon connection causes the source Publisher to emit items
     *         to its {@link Subscriber}s
     * @see <a href="http://reactivex.io/documentation/operators/publish.html">ReactiveX operators documentation: Publish</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final ConnectableFlowable<T> publish() {
        return publish(bufferSize());
    }

    /**
     * Returns a Flowable that emits the results of invoking a specified selector on items emitted by a
     * {@link ConnectableFlowable} that shares a single subscription to the underlying sequence.
     * <p>
     * <img width="640" height="510" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/publishConnect.f.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects the source {@code Publisher} to honor backpressure and if this expectation is
     *  violated, the operator will signal a {@code MissingBackpressureException} through the {@code Publisher}
     *  provided to the function. Since the {@code Publisher} returned by the {@code selector} may be
     *  independent from the provided {@code Publisher} to the function, the output's backpressure behavior
     *  is determined by this returned {@code Publisher}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code publish} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R>
     *            the type of items emitted by the resulting Publisher
     * @param selector
     *            a function that can use the multicasted source sequence as many times as needed, without
     *            causing multiple subscriptions to the source sequence. Subscribers to the given source will
     *            receive all notifications of the source from the time of the subscription forward.
     * @return a Flowable that emits the results of invoking the selector on the items emitted by a {@link ConnectableFlowable} that shares a single subscription to the underlying sequence
     * @see <a href="http://reactivex.io/documentation/operators/publish.html">ReactiveX operators documentation: Publish</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> publish(Function<? super Flowable<T>, ? extends Publisher<R>> selector) {
        return publish(selector, bufferSize());
    }

    /**
     * Returns a Flowable that emits the results of invoking a specified selector on items emitted by a
     * {@link ConnectableFlowable} that shares a single subscription to the underlying sequence.
     * <p>
     * <img width="640" height="510" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/publishConnect.f.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects the source {@code Publisher} to honor backpressure and if this expectation is
     *  violated, the operator will signal a {@code MissingBackpressureException} through the {@code Publisher}
     *  provided to the function. Since the {@code Publisher} returned by the {@code selector} may be
     *  independent from the provided {@code Publisher} to the function, the output's backpressure behavior
     *  is determined by this returned {@code Publisher}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code publish} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R>
     *            the type of items emitted by the resulting Publisher
     * @param selector
     *            a function that can use the multicasted source sequence as many times as needed, without
     *            causing multiple subscriptions to the source sequence. Subscribers to the given source will
     *            receive all notifications of the source from the time of the subscription forward.
     * @param prefetch
     *            the number of elements to prefetch from the current Flowable
     * @return a Flowable that emits the results of invoking the selector on the items emitted by a {@link ConnectableFlowable} that shares a single subscription to the underlying sequence
     * @see <a href="http://reactivex.io/documentation/operators/publish.html">ReactiveX operators documentation: Publish</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> publish(Function<? super Flowable<T>, ? extends Publisher<? extends R>> selector, int prefetch) {
        ObjectHelper.requireNonNull(selector, "selector is null");
        ObjectHelper.verifyPositive(prefetch, "prefetch");
        return RxJavaPlugins.onAssembly(new FlowablePublishMulticast<T, R>(this, selector, prefetch, false));
    }

    /**
     * Returns a {@link ConnectableFlowable}, which is a variety of Publisher that waits until its
     * {@link ConnectableFlowable#connect connect} method is called before it begins emitting items to those
     * {@link Subscriber}s that have subscribed to it.
     * <p>
     * <img width="640" height="510" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/publishConnect.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned {@code ConnectableFlowable} honors backpressure for each of its {@code Subscriber}s
     *  and expects the source {@code Publisher} to honor backpressure as well. If this expectation is violated,
     *  the operator will signal a {@code MissingBackpressureException} to its {@code Subscriber}s and disconnect.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code publish} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param bufferSize
     *            the number of elements to prefetch from the current Flowable
     * @return a {@link ConnectableFlowable} that upon connection causes the source Publisher to emit items
     *         to its {@link Subscriber}s
     * @see <a href="http://reactivex.io/documentation/operators/publish.html">ReactiveX operators documentation: Publish</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final ConnectableFlowable<T> publish(int bufferSize) {
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return FlowablePublish.create(this, bufferSize);
    }

    /**
     * Requests {@code n} initially from the upstream and then 75% of {@code n} subsequently
     * after 75% of {@code n} values have been emitted to the downstream.
     *
     * <p>This operator allows preventing the downstream to trigger unbounded mode via {@code request(Long.MAX_VALUE)}
     * or compensate for the per-item overhead of small and frequent requests.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from upstream and honors backpressure from downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code rebatchRequests} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param n the initial request amount, further request will happen after 75% of this value
     * @return the Publisher that rebatches request amounts from downstream
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> rebatchRequests(int n) {
        return observeOn(ImmediateThinScheduler.INSTANCE, true, n);
    }

    /**
     * Returns a Maybe that applies a specified accumulator function to the first item emitted by a source
     * Publisher, then feeds the result of that function along with the second item emitted by the source
     * Publisher into the same function, and so on until all items have been emitted by the source Publisher,
     * and emits the final result from the final call to your function as its sole item.
     * <p>
     * If the source is empty, a {@code NoSuchElementException} is signalled.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/reduce.png" alt="">
     * <p>
     * This technique, which is called "reduce" here, is sometimes called "aggregate," "fold," "accumulate,"
     * "compress," or "inject" in other programming contexts. Groovy, for instance, has an {@code inject} method
     * that does a similar operation on lists.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure of its downstream consumer and consumes the
     *  upstream source in unbounded mode.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code reduce} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param reducer
     *            an accumulator function to be invoked on each item emitted by the source Publisher, whose
     *            result will be used in the next accumulator call
     * @return a Maybe that emits a single item that is the result of accumulating the items emitted by
     *         the source Flowable
     * @see <a href="http://reactivex.io/documentation/operators/reduce.html">ReactiveX operators documentation: Reduce</a>
     * @see <a href="http://en.wikipedia.org/wiki/Fold_(higher-order_function)">Wikipedia: Fold (higher-order function)</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Maybe<T> reduce(BiFunction<T, T, T> reducer) {
        ObjectHelper.requireNonNull(reducer, "reducer is null");
        return RxJavaPlugins.onAssembly(new FlowableReduceMaybe<T>(this, reducer));
    }

    /**
     * Returns a Single that applies a specified accumulator function to the first item emitted by a source
     * Publisher and a specified seed value, then feeds the result of that function along with the second item
     * emitted by a Publisher into the same function, and so on until all items have been emitted by the
     * source Publisher, emitting the final result from the final call to your function as its sole item.
     * <p>
     * <img width="640" height="325" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/reduceSeed.png" alt="">
     * <p>
     * This technique, which is called "reduce" here, is sometimes called "aggregate," "fold," "accumulate,"
     * "compress," or "inject" in other programming contexts. Groovy, for instance, has an {@code inject} method
     * that does a similar operation on lists.
     * <p>
     * Note that the {@code seed} is shared among all subscribers to the resulting Publisher
     * and may cause problems if it is mutable. To make sure each subscriber gets its own value, defer
     * the application of this operator via {@link #defer(Callable)}:
     * <pre><code>
     * Publisher&lt;T&gt; source = ...
     * Single.defer(() -&gt; source.reduce(new ArrayList&lt;&gt;(), (list, item) -&gt; list.add(item)));
     *
     * // alternatively, by using compose to stay fluent
     *
     * source.compose(o -&gt;
     *     Flowable.defer(() -&gt; o.reduce(new ArrayList&lt;&gt;(), (list, item) -&gt; list.add(item)).toFlowable())
     * ).firstOrError();
     *
     * // or, by using reduceWith instead of reduce
     *
     * source.reduceWith(() -&gt; new ArrayList&lt;&gt;(), (list, item) -&gt; list.add(item)));
     * </code></pre>
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure of its downstream consumer and consumes the
     *  upstream source in unbounded mode.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code reduce} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the accumulator and output value type
     * @param seed
     *            the initial (seed) accumulator value
     * @param reducer
     *            an accumulator function to be invoked on each item emitted by the source Publisher, the
     *            result of which will be used in the next accumulator call
     * @return a Single that emits a single item that is the result of accumulating the output from the
     *         items emitted by the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/reduce.html">ReactiveX operators documentation: Reduce</a>
     * @see <a href="http://en.wikipedia.org/wiki/Fold_(higher-order_function)">Wikipedia: Fold (higher-order function)</a>
     * @see #reduceWith(Callable, BiFunction)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Single<R> reduce(R seed, BiFunction<R, ? super T, R> reducer) {
        ObjectHelper.requireNonNull(seed, "seed is null");
        ObjectHelper.requireNonNull(reducer, "reducer is null");
        return RxJavaPlugins.onAssembly(new FlowableReduceSeedSingle<T, R>(this, seed, reducer));
    }

    /**
     * Returns a Single that applies a specified accumulator function to the first item emitted by a source
     * Publisher and a seed value derived from calling a specified seedSupplier, then feeds the result
     * of that function along with the second item emitted by a Publisher into the same function, and so on until
     * all items have been emitted by the source Publisher, emitting the final result from the final call to your
     * function as its sole item.
     * <p>
     * <img width="640" height="325" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/reduceSeed.png" alt="">
     * <p>
     * This technique, which is called "reduce" here, is sometimes called "aggregate," "fold," "accumulate,"
     * "compress," or "inject" in other programming contexts. Groovy, for instance, has an {@code inject} method
     * that does a similar operation on lists.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure of its downstream consumer and consumes the
     *  upstream source in unbounded mode.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code reduceWith} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the accumulator and output value type
     * @param seedSupplier
     *            the Callable that provides the initial (seed) accumulator value for each individual Subscriber
     * @param reducer
     *            an accumulator function to be invoked on each item emitted by the source Publisher, the
     *            result of which will be used in the next accumulator call
     * @return a Single that emits a single item that is the result of accumulating the output from the
     *         items emitted by the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/reduce.html">ReactiveX operators documentation: Reduce</a>
     * @see <a href="http://en.wikipedia.org/wiki/Fold_(higher-order_function)">Wikipedia: Fold (higher-order function)</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Single<R> reduceWith(Callable<R> seedSupplier, BiFunction<R, ? super T, R> reducer) {
        ObjectHelper.requireNonNull(seedSupplier, "seedSupplier is null");
        ObjectHelper.requireNonNull(reducer, "reducer is null");
        return RxJavaPlugins.onAssembly(new FlowableReduceWithSingle<T, R>(this, seedSupplier, reducer));
    }

    /**
     * Returns a Flowable that repeats the sequence of items emitted by the source Publisher indefinitely.
     * <p>
     * <img width="640" height="309" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/repeat.o.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure and expects the source {@code Publisher} to honor backpressure as well.
     *  If this expectation is violated, the operator <em>may</em> throw an {@code IllegalStateException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code repeat} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a Flowable that emits the items emitted by the source Publisher repeatedly and in sequence
     * @see <a href="http://reactivex.io/documentation/operators/repeat.html">ReactiveX operators documentation: Repeat</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> repeat() {
        return repeat(Long.MAX_VALUE);
    }

    /**
     * Returns a Flowable that repeats the sequence of items emitted by the source Publisher at most
     * {@code count} times.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/repeat.on.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure and expects the source {@code Publisher} to honor backpressure as well.
     *  If this expectation is violated, the operator <em>may</em> throw an {@code IllegalStateException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code repeat} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param times
     *            the number of times the source Publisher items are repeated, a count of 0 will yield an empty
     *            sequence
     * @return a Flowable that repeats the sequence of items emitted by the source Publisher at most
     *         {@code count} times
     * @throws IllegalArgumentException
     *             if {@code count} is less than zero
     * @see <a href="http://reactivex.io/documentation/operators/repeat.html">ReactiveX operators documentation: Repeat</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> repeat(long times) {
        if (times < 0) {
            throw new IllegalArgumentException("times >= 0 required but it was " + times);
        }
        if (times == 0) {
            return empty();
        }
        return RxJavaPlugins.onAssembly(new FlowableRepeat<T>(this, times));
    }

    /**
     * Returns a Flowable that repeats the sequence of items emitted by the source Publisher until
     * the provided stop function returns true.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/repeat.on.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure and expects the source {@code Publisher} to honor backpressure as well.
     *  If this expectation is violated, the operator <em>may</em> throw an {@code IllegalStateException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code repeatUntil} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param stop
     *                a boolean supplier that is called when the current Flowable completes and unless it returns
     *                false, the current Flowable is resubscribed
     * @return the new Flowable instance
     * @throws NullPointerException
     *             if {@code stop} is null
     * @see <a href="http://reactivex.io/documentation/operators/repeat.html">ReactiveX operators documentation: Repeat</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> repeatUntil(BooleanSupplier stop) {
        ObjectHelper.requireNonNull(stop, "stop is null");
        return RxJavaPlugins.onAssembly(new FlowableRepeatUntil<T>(this, stop));
    }

    /**
     * Returns a Flowable that emits the same values as the source Publisher with the exception of an
     * {@code onComplete}. An {@code onComplete} notification from the source will result in the emission of
     * a {@code void} item to the Publisher provided as an argument to the {@code notificationHandler}
     * function. If that Publisher calls {@code onComplete} or {@code onError} then {@code repeatWhen} will
     * call {@code onComplete} or {@code onError} on the child subscription. Otherwise, this Publisher will
     * resubscribe to the source Publisher.
     * <p>
     * <img width="640" height="430" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/repeatWhen.f.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure and expects the source {@code Publisher} to honor backpressure as well.
     *  If this expectation is violated, the operator <em>may</em> throw an {@code IllegalStateException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code repeatWhen} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param handler
     *            receives a Publisher of notifications with which a user can complete or error, aborting the repeat.
     * @return the source Publisher modified with repeat logic
     * @see <a href="http://reactivex.io/documentation/operators/repeat.html">ReactiveX operators documentation: Repeat</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> repeatWhen(final Function<? super Flowable<Object>, ? extends Publisher<?>> handler) {
        ObjectHelper.requireNonNull(handler, "handler is null");
        return RxJavaPlugins.onAssembly(new FlowableRepeatWhen<T>(this, handler));
    }

    /**
     * Returns a {@link ConnectableFlowable} that shares a single subscription to the underlying Publisher
     * that will replay all of its items and notifications to any future {@link Subscriber}. A Connectable
     * Publisher resembles an ordinary Publisher, except that it does not begin emitting items when it is
     * subscribed to, but only when its {@code connect} method is called.
     * <p>
     * <img width="640" height="515" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code replay} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a {@link ConnectableFlowable} that upon connection causes the source Publisher to emit its
     *         items to its {@link Subscriber}s
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final ConnectableFlowable<T> replay() {
        return FlowableReplay.createFrom(this);
    }

    /**
     * Returns a Flowable that emits items that are the results of invoking a specified selector on the items
     * emitted by a {@link ConnectableFlowable} that shares a single subscription to the source Publisher.
     * <p>
     * <img width="640" height="450" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.f.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code replay} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R>
     *            the type of items emitted by the resulting Publisher
     * @param selector
     *            the selector function, which can use the multicasted sequence as many times as needed, without
     *            causing multiple subscriptions to the Publisher
     * @return a Flowable that emits items that are the results of invoking the selector on a
     *         {@link ConnectableFlowable} that shares a single subscription to the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> replay(Function<? super Flowable<T>, ? extends Publisher<R>> selector) {
        ObjectHelper.requireNonNull(selector, "selector is null");
        return FlowableReplay.multicastSelector(FlowableInternalHelper.replayCallable(this), selector);
    }

    /**
     * Returns a Flowable that emits items that are the results of invoking a specified selector on items
     * emitted by a {@link ConnectableFlowable} that shares a single subscription to the source Publisher,
     * replaying {@code bufferSize} notifications.
     * <p>
     * <img width="640" height="440" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.fn.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code replay} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R>
     *            the type of items emitted by the resulting Publisher
     * @param selector
     *            the selector function, which can use the multicasted sequence as many times as needed, without
     *            causing multiple subscriptions to the Publisher
     * @param bufferSize
     *            the buffer size that limits the number of items the connectable Publisher can replay
     * @return a Flowable that emits items that are the results of invoking the selector on items emitted by
     *         a {@link ConnectableFlowable} that shares a single subscription to the source Publisher
     *         replaying no more than {@code bufferSize} items
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> replay(Function<? super Flowable<T>, ? extends Publisher<R>> selector, final int bufferSize) {
        ObjectHelper.requireNonNull(selector, "selector is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return FlowableReplay.multicastSelector(FlowableInternalHelper.replayCallable(this, bufferSize), selector);
    }

    /**
     * Returns a Flowable that emits items that are the results of invoking a specified selector on items
     * emitted by a {@link ConnectableFlowable} that shares a single subscription to the source Publisher,
     * replaying no more than {@code bufferSize} items that were emitted within a specified time window.
     * <p>
     * <img width="640" height="445" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.fnt.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code replay} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R>
     *            the type of items emitted by the resulting Publisher
     * @param selector
     *            a selector function, which can use the multicasted sequence as many times as needed, without
     *            causing multiple subscriptions to the Publisher
     * @param bufferSize
     *            the buffer size that limits the number of items the connectable Publisher can replay
     * @param time
     *            the duration of the window in which the replayed items must have been emitted
     * @param unit
     *            the time unit of {@code time}
     * @return a Flowable that emits items that are the results of invoking the selector on items emitted by
     *         a {@link ConnectableFlowable} that shares a single subscription to the source Publisher, and
     *         replays no more than {@code bufferSize} items that were emitted within the window defined by
     *         {@code time}
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final <R> Flowable<R> replay(Function<? super Flowable<T>, ? extends Publisher<R>> selector, int bufferSize, long time, TimeUnit unit) {
        return replay(selector, bufferSize, time, unit, Schedulers.computation());
    }

    /**
     * Returns a Flowable that emits items that are the results of invoking a specified selector on items
     * emitted by a {@link ConnectableFlowable} that shares a single subscription to the source Publisher,
     * replaying no more than {@code bufferSize} items that were emitted within a specified time window.
     * <p>
     * <img width="640" height="445" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.fnts.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param <R>
     *            the type of items emitted by the resulting Publisher
     * @param selector
     *            a selector function, which can use the multicasted sequence as many times as needed, without
     *            causing multiple subscriptions to the Publisher
     * @param bufferSize
     *            the buffer size that limits the number of items the connectable Publisher can replay
     * @param time
     *            the duration of the window in which the replayed items must have been emitted
     * @param unit
     *            the time unit of {@code time}
     * @param scheduler
     *            the Scheduler that is the time source for the window
     * @return a Flowable that emits items that are the results of invoking the selector on items emitted by
     *         a {@link ConnectableFlowable} that shares a single subscription to the source Publisher, and
     *         replays no more than {@code bufferSize} items that were emitted within the window defined by
     *         {@code time}
     * @throws IllegalArgumentException
     *             if {@code bufferSize} is less than zero
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final <R> Flowable<R> replay(Function<? super Flowable<T>, ? extends Publisher<R>> selector, final int bufferSize, final long time, final TimeUnit unit, final Scheduler scheduler) {
        ObjectHelper.requireNonNull(selector, "selector is null");
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return FlowableReplay.multicastSelector(
                FlowableInternalHelper.replayCallable(this, bufferSize, time, unit, scheduler), selector);
    }

    /**
     * Returns a Flowable that emits items that are the results of invoking a specified selector on items
     * emitted by a {@link ConnectableFlowable} that shares a single subscription to the source Publisher,
     * replaying a maximum of {@code bufferSize} items.
     * <p>
     * <img width="640" height="440" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.fns.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param <R>
     *            the type of items emitted by the resulting Publisher
     * @param selector
     *            a selector function, which can use the multicasted sequence as many times as needed, without
     *            causing multiple subscriptions to the Publisher
     * @param bufferSize
     *            the buffer size that limits the number of items the connectable Publisher can replay
     * @param scheduler
     *            the Scheduler on which the replay is observed
     * @return a Flowable that emits items that are the results of invoking the selector on items emitted by
     *         a {@link ConnectableFlowable} that shares a single subscription to the source Publisher,
     *         replaying no more than {@code bufferSize} notifications
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final <R> Flowable<R> replay(final Function<? super Flowable<T>, ? extends Publisher<R>> selector, final int bufferSize, final Scheduler scheduler) {
        ObjectHelper.requireNonNull(selector, "selector is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return FlowableReplay.multicastSelector(FlowableInternalHelper.replayCallable(this, bufferSize),
                FlowableInternalHelper.replayFunction(selector, scheduler)
        );
    }

    /**
     * Returns a Flowable that emits items that are the results of invoking a specified selector on items
     * emitted by a {@link ConnectableFlowable} that shares a single subscription to the source Publisher,
     * replaying all items that were emitted within a specified time window.
     * <p>
     * <img width="640" height="435" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.ft.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code replay} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R>
     *            the type of items emitted by the resulting Publisher
     * @param selector
     *            a selector function, which can use the multicasted sequence as many times as needed, without
     *            causing multiple subscriptions to the Publisher
     * @param time
     *            the duration of the window in which the replayed items must have been emitted
     * @param unit
     *            the time unit of {@code time}
     * @return a Flowable that emits items that are the results of invoking the selector on items emitted by
     *         a {@link ConnectableFlowable} that shares a single subscription to the source Publisher,
     *         replaying all items that were emitted within the window defined by {@code time}
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final <R> Flowable<R> replay(Function<? super Flowable<T>, ? extends Publisher<R>> selector, long time, TimeUnit unit) {
        return replay(selector, time, unit, Schedulers.computation());
    }

    /**
     * Returns a Flowable that emits items that are the results of invoking a specified selector on items
     * emitted by a {@link ConnectableFlowable} that shares a single subscription to the source Publisher,
     * replaying all items that were emitted within a specified time window.
     * <p>
     * <img width="640" height="440" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.fts.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param <R>
     *            the type of items emitted by the resulting Publisher
     * @param selector
     *            a selector function, which can use the multicasted sequence as many times as needed, without
     *            causing multiple subscriptions to the Publisher
     * @param time
     *            the duration of the window in which the replayed items must have been emitted
     * @param unit
     *            the time unit of {@code time}
     * @param scheduler
     *            the scheduler that is the time source for the window
     * @return a Flowable that emits items that are the results of invoking the selector on items emitted by
     *         a {@link ConnectableFlowable} that shares a single subscription to the source Publisher,
     *         replaying all items that were emitted within the window defined by {@code time}
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final <R> Flowable<R> replay(Function<? super Flowable<T>, ? extends Publisher<R>> selector, final long time, final TimeUnit unit, final Scheduler scheduler) {
        ObjectHelper.requireNonNull(selector, "selector is null");
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return FlowableReplay.multicastSelector(FlowableInternalHelper.replayCallable(this, time, unit, scheduler), selector);
    }

    /**
     * Returns a Flowable that emits items that are the results of invoking a specified selector on items
     * emitted by a {@link ConnectableFlowable} that shares a single subscription to the source Publisher.
     * <p>
     * <img width="640" height="445" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.fs.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param <R>
     *            the type of items emitted by the resulting Publisher
     * @param selector
     *            a selector function, which can use the multicasted sequence as many times as needed, without
     *            causing multiple subscriptions to the Publisher
     * @param scheduler
     *            the Scheduler where the replay is observed
     * @return a Flowable that emits items that are the results of invoking the selector on items emitted by
     *         a {@link ConnectableFlowable} that shares a single subscription to the source Publisher,
     *         replaying all items
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final <R> Flowable<R> replay(final Function<? super Flowable<T>, ? extends Publisher<R>> selector, final Scheduler scheduler) {
        ObjectHelper.requireNonNull(selector, "selector is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return FlowableReplay.multicastSelector(FlowableInternalHelper.replayCallable(this),
                FlowableInternalHelper.replayFunction(selector, scheduler));
    }

    /**
     * Returns a {@link ConnectableFlowable} that shares a single subscription to the source Publisher that
     * replays at most {@code bufferSize} items emitted by that Publisher. A Connectable Publisher resembles
     * an ordinary Publisher, except that it does not begin emitting items when it is subscribed to, but only
     * when its {@code connect} method is called.
     * <p>
     * <img width="640" height="515" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.n.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code replay} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param bufferSize
     *            the buffer size that limits the number of items that can be replayed
     * @return a {@link ConnectableFlowable} that shares a single subscription to the source Publisher and
     *         replays at most {@code bufferSize} items emitted by that Publisher
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final ConnectableFlowable<T> replay(final int bufferSize) {
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return FlowableReplay.create(this, bufferSize);
    }

    /**
     * Returns a {@link ConnectableFlowable} that shares a single subscription to the source Publisher and
     * replays at most {@code bufferSize} items that were emitted during a specified time window. A Connectable
     * Publisher resembles an ordinary Publisher, except that it does not begin emitting items when it is
     * subscribed to, but only when its {@code connect} method is called.
     * <p>
     * <img width="640" height="515" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.nt.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code replay} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param bufferSize
     *            the buffer size that limits the number of items that can be replayed
     * @param time
     *            the duration of the window in which the replayed items must have been emitted
     * @param unit
     *            the time unit of {@code time}
     * @return a {@link ConnectableFlowable} that shares a single subscription to the source Publisher and
     *         replays at most {@code bufferSize} items that were emitted during the window defined by
     *         {@code time}
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final ConnectableFlowable<T> replay(int bufferSize, long time, TimeUnit unit) {
        return replay(bufferSize, time, unit, Schedulers.computation());
    }

    /**
     * Returns a {@link ConnectableFlowable} that shares a single subscription to the source Publisher and
     * that replays a maximum of {@code bufferSize} items that are emitted within a specified time window. A
     * Connectable Publisher resembles an ordinary Publisher, except that it does not begin emitting items
     * when it is subscribed to, but only when its {@code connect} method is called.
     * <p>
     * <img width="640" height="515" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.nts.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param bufferSize
     *            the buffer size that limits the number of items that can be replayed
     * @param time
     *            the duration of the window in which the replayed items must have been emitted
     * @param unit
     *            the time unit of {@code time}
     * @param scheduler
     *            the scheduler that is used as a time source for the window
     * @return a {@link ConnectableFlowable} that shares a single subscription to the source Publisher and
     *         replays at most {@code bufferSize} items that were emitted during the window defined by
     *         {@code time}
     * @throws IllegalArgumentException
     *             if {@code bufferSize} is less than zero
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final ConnectableFlowable<T> replay(final int bufferSize, final long time, final TimeUnit unit, final Scheduler scheduler) {
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return FlowableReplay.create(this, time, unit, scheduler, bufferSize);
    }

    /**
     * Returns a {@link ConnectableFlowable} that shares a single subscription to the source Publisher and
     * replays at most {@code bufferSize} items emitted by that Publisher. A Connectable Publisher resembles
     * an ordinary Publisher, except that it does not begin emitting items when it is subscribed to, but only
     * when its {@code connect} method is called.
     * <p>
     * <img width="640" height="515" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.ns.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param bufferSize
     *            the buffer size that limits the number of items that can be replayed
     * @param scheduler
     *            the scheduler on which the Subscribers will observe the emitted items
     * @return a {@link ConnectableFlowable} that shares a single subscription to the source Publisher and
     *         replays at most {@code bufferSize} items that were emitted by the Publisher
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final ConnectableFlowable<T> replay(final int bufferSize, final Scheduler scheduler) {
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return FlowableReplay.observeOn(replay(bufferSize), scheduler);
    }

    /**
     * Returns a {@link ConnectableFlowable} that shares a single subscription to the source Publisher and
     * replays all items emitted by that Publisher within a specified time window. A Connectable Publisher
     * resembles an ordinary Publisher, except that it does not begin emitting items when it is subscribed to,
     * but only when its {@code connect} method is called.
     * <p>
     * <img width="640" height="515" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.t.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code replay} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param time
     *            the duration of the window in which the replayed items must have been emitted
     * @param unit
     *            the time unit of {@code time}
     * @return a {@link ConnectableFlowable} that shares a single subscription to the source Publisher and
     *         replays the items that were emitted during the window defined by {@code time}
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final ConnectableFlowable<T> replay(long time, TimeUnit unit) {
        return replay(time, unit, Schedulers.computation());
    }

    /**
     * Returns a {@link ConnectableFlowable} that shares a single subscription to the source Publisher and
     * replays all items emitted by that Publisher within a specified time window. A Connectable Publisher
     * resembles an ordinary Publisher, except that it does not begin emitting items when it is subscribed to,
     * but only when its {@code connect} method is called.
     * <p>
     * <img width="640" height="515" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.ts.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param time
     *            the duration of the window in which the replayed items must have been emitted
     * @param unit
     *            the time unit of {@code time}
     * @param scheduler
     *            the Scheduler that is the time source for the window
     * @return a {@link ConnectableFlowable} that shares a single subscription to the source Publisher and
     *         replays the items that were emitted during the window defined by {@code time}
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final ConnectableFlowable<T> replay(final long time, final TimeUnit unit, final Scheduler scheduler) {
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return FlowableReplay.create(this, time, unit, scheduler);
    }

    /**
     * Returns a {@link ConnectableFlowable} that shares a single subscription to the source Publisher that
     * will replay all of its items and notifications to any future {@link Subscriber} on the given
     * {@link Scheduler}. A Connectable Publisher resembles an ordinary Publisher, except that it does not
     * begin emitting items when it is subscribed to, but only when its {@code connect} method is called.
     * <p>
     * <img width="640" height="515" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/replay.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator supports backpressure. Note that the upstream requests are determined by the child
     *  Subscriber which requests the largest amount: i.e., two child Subscribers with requests of 10 and 100 will
     *  request 100 elements from the underlying Publisher sequence.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param scheduler
     *            the Scheduler on which the Subscribers will observe the emitted items
     * @return a {@link ConnectableFlowable} that shares a single subscription to the source Publisher that
     *         will replay all of its items and notifications to any future {@link Subscriber} on the given
     *         {@link Scheduler}
     * @see <a href="http://reactivex.io/documentation/operators/replay.html">ReactiveX operators documentation: Replay</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final ConnectableFlowable<T> replay(final Scheduler scheduler) {
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return FlowableReplay.observeOn(replay(), scheduler);
    }

    /**
     * Returns a Flowable that mirrors the source Publisher, resubscribing to it if it calls {@code onError}
     * (infinite retry count).
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/retry.png" alt="">
     * <p>
     * If the source Publisher calls {@link Subscriber#onError}, this method will resubscribe to the source
     * Publisher rather than propagating the {@code onError} call.
     * <p>
     * Any and all items emitted by the source Publisher will be emitted by the resulting Publisher, even
     * those emitted during failed subscriptions. For example, if a Publisher fails at first but emits
     * {@code [1, 2]} then succeeds the second time and emits {@code [1, 2, 3, 4, 5]} then the complete sequence
     * of emissions and notifications would be {@code [1, 2, 1, 2, 3, 4, 5, onComplete]}.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure and expects the source {@code Publisher} to honor backpressure as well.
     *  If this expectation is violated, the operator <em>may</em> throw an {@code IllegalStateException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code retry} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return the source Publisher modified with retry logic
     * @see <a href="http://reactivex.io/documentation/operators/retry.html">ReactiveX operators documentation: Retry</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> retry() {
        return retry(Long.MAX_VALUE, Functions.alwaysTrue());
    }

    /**
     * Returns a Flowable that mirrors the source Publisher, resubscribing to it if it calls {@code onError}
     * and the predicate returns true for that specific exception and retry count.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/retry.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure and expects the source {@code Publisher} to honor backpressure as well.
     *  If this expectation is violated, the operator <em>may</em> throw an {@code IllegalStateException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code retry} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param predicate
     *            the predicate that determines if a resubscription may happen in case of a specific exception
     *            and retry count
     * @return the source Publisher modified with retry logic
     * @see #retry()
     * @see <a href="http://reactivex.io/documentation/operators/retry.html">ReactiveX operators documentation: Retry</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> retry(BiPredicate<? super Integer, ? super Throwable> predicate) {
        ObjectHelper.requireNonNull(predicate, "predicate is null");

        return RxJavaPlugins.onAssembly(new FlowableRetryBiPredicate<T>(this, predicate));
    }

    /**
     * Returns a Flowable that mirrors the source Publisher, resubscribing to it if it calls {@code onError}
     * up to a specified number of retries.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/retry.png" alt="">
     * <p>
     * If the source Publisher calls {@link Subscriber#onError}, this method will resubscribe to the source
     * Publisher for a maximum of {@code count} resubscriptions rather than propagating the
     * {@code onError} call.
     * <p>
     * Any and all items emitted by the source Publisher will be emitted by the resulting Publisher, even
     * those emitted during failed subscriptions. For example, if a Publisher fails at first but emits
     * {@code [1, 2]} then succeeds the second time and emits {@code [1, 2, 3, 4, 5]} then the complete sequence
     * of emissions and notifications would be {@code [1, 2, 1, 2, 3, 4, 5, onComplete]}.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure and expects the source {@code Publisher} to honor backpressure as well.
     *  If this expectation is violated, the operator <em>may</em> throw an {@code IllegalStateException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code retry} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param count
     *            number of retry attempts before failing
     * @return the source Publisher modified with retry logic
     * @see <a href="http://reactivex.io/documentation/operators/retry.html">ReactiveX operators documentation: Retry</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> retry(long count) {
        return retry(count, Functions.alwaysTrue());
    }

    /**
     * Retries at most times or until the predicate returns false, whichever happens first.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure and expects the source {@code Publisher} to honor backpressure as well.
     *  If this expectation is violated, the operator <em>may</em> throw an {@code IllegalStateException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code retry} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param times the number of times to repeat
     * @param predicate the predicate called with the failure Throwable and should return true to trigger a retry.
     * @return the new Flowable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> retry(long times, Predicate<? super Throwable> predicate) {
        if (times < 0) {
            throw new IllegalArgumentException("times >= 0 required but it was " + times);
        }
        ObjectHelper.requireNonNull(predicate, "predicate is null");

        return RxJavaPlugins.onAssembly(new FlowableRetryPredicate<T>(this, times, predicate));
    }

    /**
     * Retries the current Flowable if the predicate returns true.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure and expects the source {@code Publisher} to honor backpressure as well.
     *  If this expectation is violated, the operator <em>may</em> throw an {@code IllegalStateException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code retry} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param predicate the predicate that receives the failure Throwable and should return true to trigger a retry.
     * @return the new Flowable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> retry(Predicate<? super Throwable> predicate) {
        return retry(Long.MAX_VALUE, predicate);
    }

    /**
     * Retries until the given stop function returns true.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure and expects the source {@code Publisher} to honor backpressure as well.
     *  If this expectation is violated, the operator <em>may</em> throw an {@code IllegalStateException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code retryUntil} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param stop the function that should return true to stop retrying
     * @return the new Flowable instance
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> retryUntil(final BooleanSupplier stop) {
        ObjectHelper.requireNonNull(stop, "stop is null");
        return retry(Long.MAX_VALUE, Functions.predicateReverseFor(stop));
    }

    /**
     * Returns a Flowable that emits the same values as the source Publisher with the exception of an
     * {@code onError}. An {@code onError} notification from the source will result in the emission of a
     * {@link Throwable} item to the Publisher provided as an argument to the {@code notificationHandler}
     * function. If that Publisher calls {@code onComplete} or {@code onError} then {@code retry} will call
     * {@code onComplete} or {@code onError} on the child subscription. Otherwise, this Publisher will
     * resubscribe to the source Publisher.
     * <p>
     * <img width="640" height="430" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/retryWhen.f.png" alt="">
     * <p>
     * Example:
     *
     * This retries 3 times, each time incrementing the number of seconds it waits.
     *
     * <pre><code>
     *  Flowable.create((FlowableEmitter&lt;? super String&gt; s) -&gt; {
     *      System.out.println("subscribing");
     *      s.onError(new RuntimeException("always fails"));
     *  }, BackpressureStrategy.BUFFER).retryWhen(attempts -&gt; {
     *      return attempts.zipWith(Flowable.range(1, 3), (n, i) -&gt; i).flatMap(i -&gt; {
     *          System.out.println("delay retry by " + i + " second(s)");
     *          return Flowable.timer(i, TimeUnit.SECONDS);
     *      });
     *  }).blockingForEach(System.out::println);
     * </code></pre>
     *
     * Output is:
     *
     * <pre> {@code
     * subscribing
     * delay retry by 1 second(s)
     * subscribing
     * delay retry by 2 second(s)
     * subscribing
     * delay retry by 3 second(s)
     * subscribing
     * } </pre>
     * <p>
     * Note that the inner {@code Publisher} returned by the handler function should signal
     * either {@code onNext}, {@code onError} or {@code onComplete} in response to the received
     * {@code Throwable} to indicate the operator should retry or terminate. If the upstream to
     * the operator is asynchronous, signalling onNext followed by onComplete immediately may
     * result in the sequence to be completed immediately. Similarly, if this inner
     * {@code Publisher} signals {@code onError} or {@code onComplete} while the upstream is
     * active, the sequence is terminated with the same signal immediately.
     * <p>
     * The following example demonstrates how to retry an asynchronous source with a delay:
     * <pre><code>
     * Flowable.timer(1, TimeUnit.SECONDS)
     *     .doOnSubscribe(s -&gt; System.out.println("subscribing"))
     *     .map(v -&gt; { throw new RuntimeException(); })
     *     .retryWhen(errors -&gt; {
     *         AtomicInteger counter = new AtomicInteger();
     *         return errors
     *                   .takeWhile(e -&gt; counter.getAndIncrement() != 3)
     *                   .flatMap(e -&gt; {
     *                       System.out.println("delay retry by " + counter.get() + " second(s)");
     *                       return Flowable.timer(counter.get(), TimeUnit.SECONDS);
     *                   });
     *     })
     *     .blockingSubscribe(System.out::println, System.out::println);
     * </code></pre>
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure and expects both the source
     *  and inner {@code Publisher}s to honor backpressure as well.
     *  If this expectation is violated, the operator <em>may</em> throw an {@code IllegalStateException}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code retryWhen} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param handler
     *            receives a Publisher of notifications with which a user can complete or error, aborting the
     *            retry
     * @return the source Publisher modified with retry logic
     * @see <a href="http://reactivex.io/documentation/operators/retry.html">ReactiveX operators documentation: Retry</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> retryWhen(
            final Function<? super Flowable<Throwable>, ? extends Publisher<?>> handler) {
        ObjectHelper.requireNonNull(handler, "handler is null");

        return RxJavaPlugins.onAssembly(new FlowableRetryWhen<T>(this, handler));
    }

    /**
     * Subscribes to the current Flowable and wraps the given Subscriber into a SafeSubscriber
     * (if not already a SafeSubscriber) that
     * deals with exceptions thrown by a misbehaving Subscriber (that doesn't follow the
     * Reactive-Streams specification).
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator leaves the reactive world and the backpressure behavior depends on the Subscriber's behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code safeSubscribe} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param s the incoming Subscriber instance
     * @throws NullPointerException if s is null
     */
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final void safeSubscribe(Subscriber<? super T> s) {
        ObjectHelper.requireNonNull(s, "s is null");
        if (s instanceof SafeSubscriber) {
            subscribe((SafeSubscriber<? super T>)s);
        } else {
            subscribe(new SafeSubscriber<T>(s));
        }
    }

    /**
     * Returns a Flowable that emits the most recently emitted item (if any) emitted by the source Publisher
     * within periodic time intervals.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/sample.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time to control data flow.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code sample} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param period
     *            the sampling rate
     * @param unit
     *            the {@link TimeUnit} in which {@code period} is defined
     * @return a Flowable that emits the results of sampling the items emitted by the source Publisher at
     *         the specified time interval
     * @see <a href="http://reactivex.io/documentation/operators/sample.html">ReactiveX operators documentation: Sample</a>
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Backpressure">RxJava wiki: Backpressure</a>
     * @see #throttleLast(long, TimeUnit)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<T> sample(long period, TimeUnit unit) {
        return sample(period, unit, Schedulers.computation());
    }

    /**
     * Returns a Flowable that emits the most recently emitted item (if any) emitted by the source Publisher
     * within periodic time intervals and optionally emit the very last upstream item when the upstream completes.
     * <p>
     * <img width="640" height="276" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/sample.emitlast.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time to control data flow.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code sample} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * <p>History: 2.0.5 - experimental
     * @param period
     *            the sampling rate
     * @param unit
     *            the {@link TimeUnit} in which {@code period} is defined
     * @param emitLast
     *            if true and the upstream completes while there is still an unsampled item available,
     *            that item is emitted to downstream before completion
     *            if false, an unsampled last item is ignored.
     * @return a Flowable that emits the results of sampling the items emitted by the source Publisher at
     *         the specified time interval
     * @see <a href="http://reactivex.io/documentation/operators/sample.html">ReactiveX operators documentation: Sample</a>
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Backpressure">RxJava wiki: Backpressure</a>
     * @see #throttleLast(long, TimeUnit)
     * @since 2.1
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<T> sample(long period, TimeUnit unit, boolean emitLast) {
        return sample(period, unit, Schedulers.computation(), emitLast);
    }

    /**
     * Returns a Flowable that emits the most recently emitted item (if any) emitted by the source Publisher
     * within periodic time intervals, where the intervals are defined on a particular Scheduler.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/sample.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time to control data flow.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param period
     *            the sampling rate
     * @param unit
     *            the {@link TimeUnit} in which {@code period} is defined
     * @param scheduler
     *            the {@link Scheduler} to use when sampling
     * @return a Flowable that emits the results of sampling the items emitted by the source Publisher at
     *         the specified time interval
     * @see <a href="http://reactivex.io/documentation/operators/sample.html">ReactiveX operators documentation: Sample</a>
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Backpressure">RxJava wiki: Backpressure</a>
     * @see #throttleLast(long, TimeUnit, Scheduler)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> sample(long period, TimeUnit unit, Scheduler scheduler) {
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return RxJavaPlugins.onAssembly(new FlowableSampleTimed<T>(this, period, unit, scheduler, false));
    }

    /**
     * Returns a Flowable that emits the most recently emitted item (if any) emitted by the source Publisher
     * within periodic time intervals, where the intervals are defined on a particular Scheduler
     * and optionally emit the very last upstream item when the upstream completes.
     * <p>
     * <img width="640" height="276" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/sample.s.emitlast.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time to control data flow.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * <p>History: 2.0.5 - experimental
     * @param period
     *            the sampling rate
     * @param unit
     *            the {@link TimeUnit} in which {@code period} is defined
     * @param scheduler
     *            the {@link Scheduler} to use when sampling
     * @param emitLast
     *            if true and the upstream completes while there is still an unsampled item available,
     *            that item is emitted to downstream before completion
     *            if false, an unsampled last item is ignored.
     * @return a Flowable that emits the results of sampling the items emitted by the source Publisher at
     *         the specified time interval
     * @see <a href="http://reactivex.io/documentation/operators/sample.html">ReactiveX operators documentation: Sample</a>
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Backpressure">RxJava wiki: Backpressure</a>
     * @see #throttleLast(long, TimeUnit, Scheduler)
     * @since 2.1
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> sample(long period, TimeUnit unit, Scheduler scheduler, boolean emitLast) {
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return RxJavaPlugins.onAssembly(new FlowableSampleTimed<T>(this, period, unit, scheduler, emitLast));
    }

    /**
     * Returns a Flowable that, when the specified {@code sampler} Publisher emits an item or completes,
     * emits the most recently emitted item (if any) emitted by the source Publisher since the previous
     * emission from the {@code sampler} Publisher.
     * <p>
     * <img width="640" height="289" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/sample.o.nolast.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses the emissions of the {@code sampler}
     *      Publisher to control data flow.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code sample} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the element type of the sampler Publisher
     * @param sampler
     *            the Publisher to use for sampling the source Publisher
     * @return a Flowable that emits the results of sampling the items emitted by this Publisher whenever
     *         the {@code sampler} Publisher emits an item or completes
     * @see <a href="http://reactivex.io/documentation/operators/sample.html">ReactiveX operators documentation: Sample</a>
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Backpressure">RxJava wiki: Backpressure</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U> Flowable<T> sample(Publisher<U> sampler) {
        ObjectHelper.requireNonNull(sampler, "sampler is null");
        return RxJavaPlugins.onAssembly(new FlowableSamplePublisher<T>(this, sampler, false));
    }

    /**
     * Returns a Flowable that, when the specified {@code sampler} Publisher emits an item or completes,
     * emits the most recently emitted item (if any) emitted by the source Publisher since the previous
     * emission from the {@code sampler} Publisher
     * and optionally emit the very last upstream item when the upstream or other Publisher complete.
     * <p>
     * <img width="640" height="289" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/sample.o.emitlast.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses the emissions of the {@code sampler}
     *      Publisher to control data flow.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code sample} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * <p>History: 2.0.5 - experimental
     * @param <U> the element type of the sampler Publisher
     * @param sampler
     *            the Publisher to use for sampling the source Publisher
     * @param emitLast
     *            if true and the upstream completes while there is still an unsampled item available,
     *            that item is emitted to downstream before completion
     *            if false, an unsampled last item is ignored.
     * @return a Flowable that emits the results of sampling the items emitted by this Publisher whenever
     *         the {@code sampler} Publisher emits an item or completes
     * @see <a href="http://reactivex.io/documentation/operators/sample.html">ReactiveX operators documentation: Sample</a>
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Backpressure">RxJava wiki: Backpressure</a>
     * @since 2.1
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U> Flowable<T> sample(Publisher<U> sampler, boolean emitLast) {
        ObjectHelper.requireNonNull(sampler, "sampler is null");
        return RxJavaPlugins.onAssembly(new FlowableSamplePublisher<T>(this, sampler, emitLast));
    }

    /**
     * Returns a Flowable that applies a specified accumulator function to the first item emitted by a source
     * Publisher, then feeds the result of that function along with the second item emitted by the source
     * Publisher into the same function, and so on until all items have been emitted by the source Publisher,
     * emitting the result of each of these iterations.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/scan.png" alt="">
     * <p>
     * This sort of function is sometimes called an accumulator.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure and expects the source {@code Publisher} to honor backpressure as well.
     *  Violating this expectation, a {@code MissingBackpressureException} <em>may</em> get signalled somewhere downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code scan} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param accumulator
     *            an accumulator function to be invoked on each item emitted by the source Publisher, whose
     *            result will be emitted to {@link Subscriber}s via {@link Subscriber#onNext onNext} and used in the
     *            next accumulator call
     * @return a Flowable that emits the results of each call to the accumulator function
     * @see <a href="http://reactivex.io/documentation/operators/scan.html">ReactiveX operators documentation: Scan</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> scan(BiFunction<T, T, T> accumulator) {
        ObjectHelper.requireNonNull(accumulator, "accumulator is null");
        return RxJavaPlugins.onAssembly(new FlowableScan<T>(this, accumulator));
    }

    /**
     * Returns a Flowable that applies a specified accumulator function to the first item emitted by a source
     * Publisher and a seed value, then feeds the result of that function along with the second item emitted by
     * the source Publisher into the same function, and so on until all items have been emitted by the source
     * Publisher, emitting the result of each of these iterations.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/scanSeed.png" alt="">
     * <p>
     * This sort of function is sometimes called an accumulator.
     * <p>
     * Note that the Publisher that results from this method will emit {@code initialValue} as its first
     * emitted item.
     * <p>
     * Note that the {@code initialValue} is shared among all subscribers to the resulting Publisher
     * and may cause problems if it is mutable. To make sure each subscriber gets its own value, defer
     * the application of this operator via {@link #defer(Callable)}:
     * <pre><code>
     * Publisher&lt;T&gt; source = ...
     * Flowable.defer(() -&gt; source.scan(new ArrayList&lt;&gt;(), (list, item) -&gt; list.add(item)));
     *
     * // alternatively, by using compose to stay fluent
     *
     * source.compose(o -&gt;
     *     Flowable.defer(() -&gt; o.scan(new ArrayList&lt;&gt;(), (list, item) -&gt; list.add(item)))
     * );
     * </code></pre>
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure and expects the source {@code Publisher} to honor backpressure as well.
     *  Violating this expectation, a {@code MissingBackpressureException} <em>may</em> get signalled somewhere downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code scan} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the initial, accumulator and result type
     * @param initialValue
     *            the initial (seed) accumulator item
     * @param accumulator
     *            an accumulator function to be invoked on each item emitted by the source Publisher, whose
     *            result will be emitted to {@link Subscriber}s via {@link Subscriber#onNext onNext} and used in the
     *            next accumulator call
     * @return a Flowable that emits {@code initialValue} followed by the results of each call to the
     *         accumulator function
     * @see <a href="http://reactivex.io/documentation/operators/scan.html">ReactiveX operators documentation: Scan</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> scan(final R initialValue, BiFunction<R, ? super T, R> accumulator) {
        ObjectHelper.requireNonNull(initialValue, "seed is null");
        return scanWith(Functions.justCallable(initialValue), accumulator);
    }

    /**
     * Returns a Flowable that applies a specified accumulator function to the first item emitted by a source
     * Publisher and a seed value, then feeds the result of that function along with the second item emitted by
     * the source Publisher into the same function, and so on until all items have been emitted by the source
     * Publisher, emitting the result of each of these iterations.
     * <p>
     * <img width="640" height="320" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/scanSeed.png" alt="">
     * <p>
     * This sort of function is sometimes called an accumulator.
     * <p>
     * Note that the Publisher that results from this method will emit the value returned by
     * the {@code seedSupplier} as its first item.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors downstream backpressure and expects the source {@code Publisher} to honor backpressure as well.
     *  Violating this expectation, a {@code MissingBackpressureException} <em>may</em> get signalled somewhere downstream.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code scanWith} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the initial, accumulator and result type
     * @param seedSupplier
     *            a Callable that returns the initial (seed) accumulator item for each individual Subscriber
     * @param accumulator
     *            an accumulator function to be invoked on each item emitted by the source Publisher, whose
     *            result will be emitted to {@link Subscriber}s via {@link Subscriber#onNext onNext} and used in the
     *            next accumulator call
     * @return a Flowable that emits {@code initialValue} followed by the results of each call to the
     *         accumulator function
     * @see <a href="http://reactivex.io/documentation/operators/scan.html">ReactiveX operators documentation: Scan</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> scanWith(Callable<R> seedSupplier, BiFunction<R, ? super T, R> accumulator) {
        ObjectHelper.requireNonNull(seedSupplier, "seedSupplier is null");
        ObjectHelper.requireNonNull(accumulator, "accumulator is null");
        return RxJavaPlugins.onAssembly(new FlowableScanSeed<T, R>(this, seedSupplier, accumulator));
    }

    /**
     * Forces a Publisher's emissions and notifications to be serialized and for it to obey
     * <a href="http://reactivex.io/documentation/contract.html">the Publisher contract</a> in other ways.
     * <p>
     * It is possible for a Publisher to invoke its Subscribers' methods asynchronously, perhaps from
     * different threads. This could make such a Publisher poorly-behaved, in that it might try to invoke
     * {@code onComplete} or {@code onError} before one of its {@code onNext} invocations, or it might call
     * {@code onNext} from two different threads concurrently. You can force such a Publisher to be
     * well-behaved and sequential by applying the {@code serialize} method to it.
     * <p>
     * <img width="640" height="400" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/synchronize.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code serialize} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a {@link Publisher} that is guaranteed to be well-behaved and to make only serialized calls to
     *         its Subscribers
     * @see <a href="http://reactivex.io/documentation/operators/serialize.html">ReactiveX operators documentation: Serialize</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> serialize() {
        return RxJavaPlugins.onAssembly(new FlowableSerialized<T>(this));
    }

    /**
     * Returns a new {@link Publisher} that multicasts (shares) the original {@link Publisher}. As long as
     * there is at least one {@link Subscriber} this {@link Publisher} will be subscribed and emitting data.
     * When all subscribers have cancelled it will cancel the source {@link Publisher}.
     * <p>
     * This is an alias for {@link #publish()}.{@link ConnectableFlowable#refCount()}.
     * <p>
     * <img width="640" height="510" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/publishRefCount.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure and and expects the source {@code Publisher} to honor backpressure as well.
     *  If this expectation is violated, the operator will signal a {@code MissingBackpressureException} to
     *  its {@code Subscriber}s.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code share} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a {@code Publisher} that upon connection causes the source {@code Publisher} to emit items
     *         to its {@link Subscriber}s
     * @see <a href="http://reactivex.io/documentation/operators/refcount.html">ReactiveX operators documentation: RefCount</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> share() {
        return publish().refCount();
    }

    /**
     * Returns a Maybe that completes if this Flowable is empty, signals one item if this Flowable
     * signals exactly one item or signals an {@code IllegalArgumentException} if this Flowable signals
     * more than one item.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/single.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code singleElement} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a Maybe that emits the single item emitted by the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/first.html">ReactiveX operators documentation: First</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Maybe<T> singleElement() {
        return RxJavaPlugins.onAssembly(new FlowableSingleMaybe<T>(this));
    }

    /**
     * Returns a Single that emits the single item emitted by the source Publisher, if that Publisher
     * emits only a single item, or a default item if the source Publisher emits no items. If the source
     * Publisher emits more than one item, an {@code IllegalArgumentException} is signalled instead.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/singleOrDefault.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code single} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param defaultItem
     *            a default value to emit if the source Publisher emits no item
     * @return a Single that emits the single item emitted by the source Publisher, or a default item if
     *         the source Publisher is empty
     * @see <a href="http://reactivex.io/documentation/operators/first.html">ReactiveX operators documentation: First</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<T> single(T defaultItem) {
        ObjectHelper.requireNonNull(defaultItem, "defaultItem is null");
        return RxJavaPlugins.onAssembly(new FlowableSingleSingle<T>(this, defaultItem));
    }

    /**
     * Returns a Single that emits the single item emitted by this Flowable, if this Flowable
     * emits only a single item, otherwise
     * if this Flowable completes without emitting any items a {@link NoSuchElementException} will be signalled and
     * if this Flowable emits more than one item, an {@code IllegalArgumentException} will be signalled.
     * <p>
     * <img width="640" height="205" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/singleOrError.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code singleOrError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return the new Single instance
     * @see <a href="http://reactivex.io/documentation/operators/first.html">ReactiveX operators documentation: First</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<T> singleOrError() {
        return RxJavaPlugins.onAssembly(new FlowableSingleSingle<T>(this, null));
    }

    /**
     * Returns a Flowable that skips the first {@code count} items emitted by the source Publisher and emits
     * the remainder.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/skip.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code skip} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param count
     *            the number of items to skip
     * @return a Flowable that is identical to the source Publisher except that it does not emit the first
     *         {@code count} items that the source Publisher emits
     * @see <a href="http://reactivex.io/documentation/operators/skip.html">ReactiveX operators documentation: Skip</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> skip(long count) {
        if (count <= 0L) {
            return RxJavaPlugins.onAssembly(this);
        }
        return RxJavaPlugins.onAssembly(new FlowableSkip<T>(this, count));
    }

    /**
     * Returns a Flowable that skips values emitted by the source Publisher before a specified time window
     * elapses.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/skip.t.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't support backpressure as it uses time to skip arbitrary number of elements and
     *  thus has to consume the source {@code Publisher} in an unbounded manner (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code skip} does not operate on any particular scheduler but uses the current time
     *  from the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param time
     *            the length of the time window to skip
     * @param unit
     *            the time unit of {@code time}
     * @return a Flowable that skips values emitted by the source Publisher before the time window defined
     *         by {@code time} elapses and the emits the remainder
     * @see <a href="http://reactivex.io/documentation/operators/skip.html">ReactiveX operators documentation: Skip</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> skip(long time, TimeUnit unit) {
        return skipUntil(timer(time, unit));
    }

    /**
     * Returns a Flowable that skips values emitted by the source Publisher before a specified time window
     * on a specified {@link Scheduler} elapses.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/skip.ts.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't support backpressure as it uses time to skip arbitrary number of elements and
     *  thus has to consume the source {@code Publisher} in an unbounded manner (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use for the timed skipping</dd>
     * </dl>
     *
     * @param time
     *            the length of the time window to skip
     * @param unit
     *            the time unit of {@code time}
     * @param scheduler
     *            the {@link Scheduler} on which the timed wait happens
     * @return a Flowable that skips values emitted by the source Publisher before the time window defined
     *         by {@code time} and {@code scheduler} elapses, and then emits the remainder
     * @see <a href="http://reactivex.io/documentation/operators/skip.html">ReactiveX operators documentation: Skip</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> skip(long time, TimeUnit unit, Scheduler scheduler) {
        return skipUntil(timer(time, unit, scheduler));
    }

    /**
     * Returns a Flowable that drops a specified number of items from the end of the sequence emitted by the
     * source Publisher.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/skipLast.png" alt="">
     * <p>
     * This Subscriber accumulates a queue long enough to store the first {@code count} items. As more items are
     * received, items are taken from the front of the queue and emitted by the returned Publisher. This causes
     * such items to be delayed.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code skipLast} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param count
     *            number of items to drop from the end of the source sequence
     * @return a Flowable that emits the items emitted by the source Publisher except for the dropped ones
     *         at the end
     * @throws IndexOutOfBoundsException
     *             if {@code count} is less than zero
     * @see <a href="http://reactivex.io/documentation/operators/skiplast.html">ReactiveX operators documentation: SkipLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> skipLast(int count) {
        if (count < 0) {
            throw new IndexOutOfBoundsException("count >= 0 required but it was " + count);
        }
        if (count == 0) {
            return RxJavaPlugins.onAssembly(this);
        }
        return RxJavaPlugins.onAssembly(new FlowableSkipLast<T>(this, count));
    }

    /**
     * Returns a Flowable that drops items emitted by the source Publisher during a specified time window
     * before the source completes.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/skipLast.t.png" alt="">
     * <p>
     * Note: this action will cache the latest items arriving in the specified time window.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't support backpressure as it uses time to skip arbitrary number of elements and
     *  thus has to consume the source {@code Publisher} in an unbounded manner (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code skipLast} does not operate on any particular scheduler but uses the current time
     *  from the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param time
     *            the length of the time window
     * @param unit
     *            the time unit of {@code time}
     * @return a Flowable that drops those items emitted by the source Publisher in a time window before the
     *         source completes defined by {@code time}
     * @see <a href="http://reactivex.io/documentation/operators/skiplast.html">ReactiveX operators documentation: SkipLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> skipLast(long time, TimeUnit unit) {
        return skipLast(time, unit, Schedulers.computation(), false, bufferSize());
    }

    /**
     * Returns a Flowable that drops items emitted by the source Publisher during a specified time window
     * before the source completes.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/skipLast.t.png" alt="">
     * <p>
     * Note: this action will cache the latest items arriving in the specified time window.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't support backpressure as it uses time to skip arbitrary number of elements and
     *  thus has to consume the source {@code Publisher} in an unbounded manner (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code skipLast} does not operate on any particular scheduler but uses the current time
     *  from the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param time
     *            the length of the time window
     * @param unit
     *            the time unit of {@code time}
     * @param delayError
     *            if true, an exception signalled by the current Flowable is delayed until the regular elements are consumed
     *            by the downstream; if false, an exception is immediately signalled and all regular elements dropped
     * @return a Flowable that drops those items emitted by the source Publisher in a time window before the
     *         source completes defined by {@code time}
     * @see <a href="http://reactivex.io/documentation/operators/skiplast.html">ReactiveX operators documentation: SkipLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> skipLast(long time, TimeUnit unit, boolean delayError) {
        return skipLast(time, unit, Schedulers.computation(), delayError, bufferSize());
    }

    /**
     * Returns a Flowable that drops items emitted by the source Publisher during a specified time window
     * (defined on a specified scheduler) before the source completes.
     * <p>
     * <img width="640" height="340" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/skipLast.ts.png" alt="">
     * <p>
     * Note: this action will cache the latest items arriving in the specified time window.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't support backpressure as it uses time to skip arbitrary number of elements and
     *  thus has to consume the source {@code Publisher} in an unbounded manner (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use for tracking the current time</dd>
     * </dl>
     *
     * @param time
     *            the length of the time window
     * @param unit
     *            the time unit of {@code time}
     * @param scheduler
     *            the scheduler used as the time source
     * @return a Flowable that drops those items emitted by the source Publisher in a time window before the
     *         source completes defined by {@code time} and {@code scheduler}
     * @see <a href="http://reactivex.io/documentation/operators/skiplast.html">ReactiveX operators documentation: SkipLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> skipLast(long time, TimeUnit unit, Scheduler scheduler) {
        return skipLast(time, unit, scheduler, false, bufferSize());
    }

    /**
     * Returns a Flowable that drops items emitted by the source Publisher during a specified time window
     * (defined on a specified scheduler) before the source completes.
     * <p>
     * <img width="640" height="340" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/skipLast.ts.png" alt="">
     * <p>
     * Note: this action will cache the latest items arriving in the specified time window.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't support backpressure as it uses time to skip arbitrary number of elements and
     *  thus has to consume the source {@code Publisher} in an unbounded manner (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use to track the current time</dd>
     * </dl>
     *
     * @param time
     *            the length of the time window
     * @param unit
     *            the time unit of {@code time}
     * @param scheduler
     *            the scheduler used as the time source
     * @param delayError
     *            if true, an exception signalled by the current Flowable is delayed until the regular elements are consumed
     *            by the downstream; if false, an exception is immediately signalled and all regular elements dropped
     * @return a Flowable that drops those items emitted by the source Publisher in a time window before the
     *         source completes defined by {@code time} and {@code scheduler}
     * @see <a href="http://reactivex.io/documentation/operators/skiplast.html">ReactiveX operators documentation: SkipLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> skipLast(long time, TimeUnit unit, Scheduler scheduler, boolean delayError) {
        return skipLast(time, unit, scheduler, delayError, bufferSize());
    }

    /**
     * Returns a Flowable that drops items emitted by the source Publisher during a specified time window
     * (defined on a specified scheduler) before the source completes.
     * <p>
     * <img width="640" height="340" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/skipLast.ts.png" alt="">
     * <p>
     * Note: this action will cache the latest items arriving in the specified time window.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't support backpressure as it uses time to skip arbitrary number of elements and
     *  thus has to consume the source {@code Publisher} in an unbounded manner (i.e., no backpressure applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param time
     *            the length of the time window
     * @param unit
     *            the time unit of {@code time}
     * @param scheduler
     *            the scheduler used as the time source
     * @param delayError
     *            if true, an exception signalled by the current Flowable is delayed until the regular elements are consumed
     *            by the downstream; if false, an exception is immediately signalled and all regular elements dropped
     * @param bufferSize
     *            the hint about how many elements to expect to be skipped
     * @return a Flowable that drops those items emitted by the source Publisher in a time window before the
     *         source completes defined by {@code time} and {@code scheduler}
     * @see <a href="http://reactivex.io/documentation/operators/skiplast.html">ReactiveX operators documentation: SkipLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> skipLast(long time, TimeUnit unit, Scheduler scheduler, boolean delayError, int bufferSize) {
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        // the internal buffer holds pairs of (timestamp, value) so double the default buffer size
        int s = bufferSize << 1;
        return RxJavaPlugins.onAssembly(new FlowableSkipLastTimed<T>(this, time, unit, scheduler, s, delayError));
    }

    /**
     * Returns a Flowable that skips items emitted by the source Publisher until a second Publisher emits
     * an item.
     * <p>
     * <img width="640" height="375" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/skipUntil.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code skipUntil} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the element type of the other Publisher
     * @param other
     *            the second Publisher that has to emit an item before the source Publisher's elements begin
     *            to be mirrored by the resulting Publisher
     * @return a Flowable that skips items from the source Publisher until the second Publisher emits an
     *         item, then emits the remaining items
     * @see <a href="http://reactivex.io/documentation/operators/skipuntil.html">ReactiveX operators documentation: SkipUntil</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U> Flowable<T> skipUntil(Publisher<U> other) {
        ObjectHelper.requireNonNull(other, "other is null");
        return RxJavaPlugins.onAssembly(new FlowableSkipUntil<T, U>(this, other));
    }

    /**
     * Returns a Flowable that skips all items emitted by the source Publisher as long as a specified
     * condition holds true, but emits all further source items as soon as the condition becomes false.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/skipWhile.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code skipWhile} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param predicate
     *            a function to test each item emitted from the source Publisher
     * @return a Flowable that begins emitting items emitted by the source Publisher when the specified
     *         predicate becomes false
     * @see <a href="http://reactivex.io/documentation/operators/skipwhile.html">ReactiveX operators documentation: SkipWhile</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> skipWhile(Predicate<? super T> predicate) {
        ObjectHelper.requireNonNull(predicate, "predicate is null");
        return RxJavaPlugins.onAssembly(new FlowableSkipWhile<T>(this, predicate));
    }
    /**
     * Returns a Flowable that emits the events emitted by source Publisher, in a
     * sorted order. Each item emitted by the Publisher must implement {@link Comparable} with respect to all
     * other items in the sequence.
     *
     * <p>If any item emitted by this Flowable does not implement {@link Comparable} with respect to
     *             all other items emitted by this Flowable, no items will be emitted and the
     *             sequence is terminated with a {@link ClassCastException}.
     * <p>Note that calling {@code sorted} with long, non-terminating or infinite sources
     * might cause {@link OutOfMemoryError}
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code sorted} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a Flowable that emits the items emitted by the source Publisher in sorted order
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> sorted() {
        return toList().toFlowable().map(Functions.listSorter(Functions.<T>naturalComparator())).flatMapIterable(Functions.<List<T>>identity());
    }

    /**
     * Returns a Flowable that emits the events emitted by source Publisher, in a
     * sorted order based on a specified comparison function.
     *
     * <p>Note that calling {@code sorted} with long, non-terminating or infinite sources
     * might cause {@link OutOfMemoryError}
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code sorted} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param sortFunction
     *            a function that compares two items emitted by the source Publisher and returns an Integer
     *            that indicates their sort order
     * @return a Flowable that emits the items emitted by the source Publisher in sorted order
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> sorted(Comparator<? super T> sortFunction) {
        ObjectHelper.requireNonNull(sortFunction, "sortFunction");
        return toList().toFlowable().map(Functions.listSorter(sortFunction)).flatMapIterable(Functions.<List<T>>identity());
    }

    /**
     * Returns a Flowable that emits the items in a specified {@link Iterable} before it begins to emit items
     * emitted by the source Publisher.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/startWith.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}
     *  is expected to honor backpressure as well. If it violates this rule, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code startWith} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param items
     *            an Iterable that contains the items you want the modified Publisher to emit first
     * @return a Flowable that emits the items in the specified {@link Iterable} and then emits the items
     *         emitted by the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/startwith.html">ReactiveX operators documentation: StartWith</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> startWith(Iterable<? extends T> items) {
        return concatArray(fromIterable(items), this);
    }

    /**
     * Returns a Flowable that emits the items in a specified {@link Publisher} before it begins to emit
     * items emitted by the source Publisher.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/startWith.o.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. Both this and the {@code other} {@code Publisher}s
     *  are expected to honor backpressure as well. If any of then violates this rule, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code startWith} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param other
     *            a Publisher that contains the items you want the modified Publisher to emit first
     * @return a Flowable that emits the items in the specified {@link Publisher} and then emits the items
     *         emitted by the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/startwith.html">ReactiveX operators documentation: StartWith</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> startWith(Publisher<? extends T> other) {
        ObjectHelper.requireNonNull(other, "other is null");
        return concatArray(other, this);
    }

    /**
     * Returns a Flowable that emits a specified item before it begins to emit items emitted by the source
     * Publisher.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/startWith.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}
     *  is expected to honor backpressure as well. If it violates this rule, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code startWith} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param value
     *            the item to emit first
     * @return a Flowable that emits the specified item before it begins to emit items emitted by the source
     *         Publisher
     * @see <a href="http://reactivex.io/documentation/operators/startwith.html">ReactiveX operators documentation: StartWith</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> startWith(T value) {
        ObjectHelper.requireNonNull(value, "item is null");
        return concatArray(just(value), this);
    }

    /**
     * Returns a Flowable that emits the specified items before it begins to emit items emitted by the source
     * Publisher.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/startWith.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The source {@code Publisher}
     *  is expected to honor backpressure as well. If it violates this rule, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code startWithArray} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param items
     *            the array of values to emit first
     * @return a Flowable that emits the specified items before it begins to emit items emitted by the source
     *         Publisher
     * @see <a href="http://reactivex.io/documentation/operators/startwith.html">ReactiveX operators documentation: StartWith</a>
     */
    @SuppressWarnings("unchecked")
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> startWithArray(T... items) {
        Flowable<T> fromArray = fromArray(items);
        if (fromArray == empty()) {
            return RxJavaPlugins.onAssembly(this);
        }
        return concatArray(fromArray, this);
    }

    /**
     * Subscribes to a Publisher and ignores {@code onNext} and {@code onComplete} emissions.
     * <p>
     * If the Flowable emits an error, it is wrapped into an
     * {@link io.reactivex.exceptions.OnErrorNotImplementedException OnErrorNotImplementedException}
     * and routed to the RxJavaPlugins.onError handler.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner (i.e., no
     *  backpressure is applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code subscribe} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a {@link Disposable} reference with which the caller can stop receiving items before
     *         the Publisher has finished sending them
     * @see <a href="http://reactivex.io/documentation/operators/subscribe.html">ReactiveX operators documentation: Subscribe</a>
     */
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Disposable subscribe() {
        return subscribe(Functions.emptyConsumer(), Functions.ON_ERROR_MISSING,
                Functions.EMPTY_ACTION, FlowableInternalHelper.RequestMax.INSTANCE);
    }

    /**
     * Subscribes to a Publisher and provides a callback to handle the items it emits.
     * <p>
     * If the Flowable emits an error, it is wrapped into an
     * {@link io.reactivex.exceptions.OnErrorNotImplementedException OnErrorNotImplementedException}
     * and routed to the RxJavaPlugins.onError handler.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner (i.e., no
     *  backpressure is applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code subscribe} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onNext
     *             the {@code Consumer<T>} you have designed to accept emissions from the Publisher
     * @return a {@link Disposable} reference with which the caller can stop receiving items before
     *         the Publisher has finished sending them
     * @throws NullPointerException
     *             if {@code onNext} is null
     * @see <a href="http://reactivex.io/documentation/operators/subscribe.html">ReactiveX operators documentation: Subscribe</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Disposable subscribe(Consumer<? super T> onNext) {
        return subscribe(onNext, Functions.ON_ERROR_MISSING,
                Functions.EMPTY_ACTION, FlowableInternalHelper.RequestMax.INSTANCE);
    }

    /**
     * Subscribes to a Publisher and provides callbacks to handle the items it emits and any error
     * notification it issues.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner (i.e., no
     *  backpressure is applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code subscribe} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onNext
     *             the {@code Consumer<T>} you have designed to accept emissions from the Publisher
     * @param onError
     *             the {@code Consumer<Throwable>} you have designed to accept any error notification from the
     *             Publisher
     * @return a {@link Disposable} reference with which the caller can stop receiving items before
     *         the Publisher has finished sending them
     * @see <a href="http://reactivex.io/documentation/operators/subscribe.html">ReactiveX operators documentation: Subscribe</a>
     * @throws NullPointerException
     *             if {@code onNext} is null, or
     *             if {@code onError} is null
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Disposable subscribe(Consumer<? super T> onNext, Consumer<? super Throwable> onError) {
        return subscribe(onNext, onError, Functions.EMPTY_ACTION, FlowableInternalHelper.RequestMax.INSTANCE);
    }

    /**
     * Subscribes to a Publisher and provides callbacks to handle the items it emits and any error or
     * completion notification it issues.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner (i.e., no
     *  backpressure is applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code subscribe} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onNext
     *             the {@code Consumer<T>} you have designed to accept emissions from the Publisher
     * @param onError
     *             the {@code Consumer<Throwable>} you have designed to accept any error notification from the
     *             Publisher
     * @param onComplete
     *             the {@code Action} you have designed to accept a completion notification from the
     *             Publisher
     * @return a {@link Disposable} reference with which the caller can stop receiving items before
     *         the Publisher has finished sending them
     * @throws NullPointerException
     *             if {@code onNext} is null, or
     *             if {@code onError} is null, or
     *             if {@code onComplete} is null
     * @see <a href="http://reactivex.io/documentation/operators/subscribe.html">ReactiveX operators documentation: Subscribe</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Disposable subscribe(Consumer<? super T> onNext, Consumer<? super Throwable> onError,
            Action onComplete) {
        return subscribe(onNext, onError, onComplete, FlowableInternalHelper.RequestMax.INSTANCE);
    }

    /**
     * Subscribes to a Publisher and provides callbacks to handle the items it emits and any error or
     * completion notification it issues.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner (i.e., no
     *  backpressure is applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code subscribe} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param onNext
     *             the {@code Consumer<T>} you have designed to accept emissions from the Publisher
     * @param onError
     *             the {@code Consumer<Throwable>} you have designed to accept any error notification from the
     *             Publisher
     * @param onComplete
     *             the {@code Action} you have designed to accept a completion notification from the
     *             Publisher
     * @param onSubscribe
     *             the {@code Consumer} that receives the upstream's Subscription
     * @return a {@link Disposable} reference with which the caller can stop receiving items before
     *         the Publisher has finished sending them
     * @throws NullPointerException
     *             if {@code onNext} is null, or
     *             if {@code onError} is null, or
     *             if {@code onComplete} is null
     * @see <a href="http://reactivex.io/documentation/operators/subscribe.html">ReactiveX operators documentation: Subscribe</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.SPECIAL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Disposable subscribe(Consumer<? super T> onNext, Consumer<? super Throwable> onError,
            Action onComplete, Consumer<? super Subscription> onSubscribe) {
        ObjectHelper.requireNonNull(onNext, "onNext is null");
        ObjectHelper.requireNonNull(onError, "onError is null");
        ObjectHelper.requireNonNull(onComplete, "onComplete is null");
        ObjectHelper.requireNonNull(onSubscribe, "onSubscribe is null");

        LambdaSubscriber<T> ls = new LambdaSubscriber<T>(onNext, onError, onComplete, onSubscribe);

        subscribe(ls);

        return ls;
    }

    @BackpressureSupport(BackpressureKind.SPECIAL)
    @SchedulerSupport(SchedulerSupport.NONE)
    @Override
    public final void subscribe(Subscriber<? super T> s) {
        if (s instanceof FlowableSubscriber) {
            subscribe((FlowableSubscriber<? super T>)s);
        } else {
            ObjectHelper.requireNonNull(s, "s is null");
            subscribe(new StrictSubscriber<T>(s));
        }
    }

    /**
     * Establish a connection between this Flowable and the given FlowableSubscriber and
     * start streaming events based on the demand of the FlowableSubscriber.
     * <p>
     * This is a "factory method" and can be called multiple times, each time starting a new {@link Subscription}.
     * <p>
     * Each {@link Subscription} will work for only a single {@link FlowableSubscriber}.
     * <p>
     * If the same {@link FlowableSubscriber} instance is subscribed to multiple {@link Flowable}s and/or the
     * same {@link Flowable} multiple times, it must ensure the serialization over its {@code onXXX}
     * methods manually.
     * <p>
     * If the {@link Flowable} rejects the subscription attempt or otherwise fails it will signal
     * the error via {@link FlowableSubscriber#onError(Throwable)}.
     * <p>
     * This subscribe method relaxes the following Reactive-Streams rules:
     * <ul>
     * <li>§1.3: onNext should not be called concurrently until onSubscribe returns.
     *     <b>FlowableSubscriber.onSubscribe should make sure a sync or async call triggered by request() is safe.</b></li>
     * <li>§2.3: onError or onComplete must not call cancel.
     *     <b>Calling request() or cancel() is NOP at this point.</b></li>
     * <li>§2.12: onSubscribe must be called at most once on the same instance.
     *     <b>FlowableSubscriber reuse is not checked and if happens, it is the responsibility of
     *     the FlowableSubscriber to ensure proper serialization of its onXXX methods.</b></li>
     * <li>§3.9: negative requests should emit an onError(IllegalArgumentException).
     *     <b>Non-positive requests signal via RxJavaPlugins.onError and the stream is not affected.</b></li>
     * </ul>
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The backpressure behavior/expectation is determined by the supplied {@code FlowableSubscriber}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code subscribe} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * <p>History: 2.0.7 - experimental
     * @param s the FlowableSubscriber that will consume signals from this Flowable
     * @since 2.1 - beta
     */
    @BackpressureSupport(BackpressureKind.SPECIAL)
    @SchedulerSupport(SchedulerSupport.NONE)
    @Beta
    public final void subscribe(FlowableSubscriber<? super T> s) {
        ObjectHelper.requireNonNull(s, "s is null");
        try {
            Subscriber<? super T> z = RxJavaPlugins.onSubscribe(this, s);

            ObjectHelper.requireNonNull(z, "Plugin returned null Subscriber");

            subscribeActual(z);
        } catch (NullPointerException e) { // NOPMD
            throw e;
        } catch (Throwable e) {
            Exceptions.throwIfFatal(e);
            // can't call onError because no way to know if a Subscription has been set or not
            // can't call onSubscribe because the call might have set a Subscription already
            RxJavaPlugins.onError(e);

            NullPointerException npe = new NullPointerException("Actually not, but can't throw other exceptions due to RS");
            npe.initCause(e);
            throw npe;
        }
    }

    /**
     * Operator implementations (both source and intermediate) should implement this method that
     * performs the necessary business logic.
     * <p>There is no need to call any of the plugin hooks on the current Flowable instance or
     * the Subscriber.
     * @param s the incoming Subscriber, never null
     */
    protected abstract void subscribeActual(Subscriber<? super T> s);

    /**
     * Subscribes a given Subscriber (subclass) to this Flowable and returns the given
     * Subscriber as is.
     * <p>Usage example:
     * <pre><code>
     * Flowable&lt;Integer&gt; source = Flowable.range(1, 10);
     * CompositeDisposable composite = new CompositeDisposable();
     *
     * ResourceSubscriber&lt;Integer&gt; rs = new ResourceSubscriber&lt;&gt;() {
     *     // ...
     * };
     *
     * composite.add(source.subscribeWith(rs));
     * </code></pre>
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The backpressure behavior/expectation is determined by the supplied {@code Subscriber}.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code subscribeWith} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <E> the type of the Subscriber to use and return
     * @param subscriber the Subscriber (subclass) to use and return, not null
     * @return the input {@code subscriber}
     * @throws NullPointerException if {@code subscriber} is null
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.SPECIAL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <E extends Subscriber<? super T>> E subscribeWith(E subscriber) {
        subscribe(subscriber);
        return subscriber;
    }

    /**
     * Asynchronously subscribes Subscribers to this Publisher on the specified {@link Scheduler}.
     * <p>
     * If there is a {@link #create(FlowableOnSubscribe, BackpressureStrategy)} type source up in the
     * chain, it is recommended to use {@code subscribeOn(scheduler, false)} instead
     * to avoid same-pool deadlock because requests may pile up behind an eager/blocking emitter.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/subscribeOn.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param scheduler
     *            the {@link Scheduler} to perform subscription actions on
     * @return the source Publisher modified so that its subscriptions happen on the
     *         specified {@link Scheduler}
     * @see <a href="http://reactivex.io/documentation/operators/subscribeon.html">ReactiveX operators documentation: SubscribeOn</a>
     * @see <a href="http://www.grahamlea.com/2014/07/rxjava-threading-examples/">RxJava Threading Examples</a>
     * @see #observeOn
     * @see #subscribeOn(Scheduler, boolean)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> subscribeOn(@NonNull Scheduler scheduler) {
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return subscribeOn(scheduler, !(this instanceof FlowableCreate));
    }

    /**
     * Asynchronously subscribes Subscribers to this Publisher on the specified {@link Scheduler}
     * optionally reroutes requests from other threads to the same {@link Scheduler} thread.
     * <p>
     * If there is a {@link #create(FlowableOnSubscribe, BackpressureStrategy)} type source up in the
     * chain, it is recommended to have {@code requestOn} false to avoid same-pool deadlock
     * because requests may pile up behind an eager/blocking emitter.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/subscribeOn.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param scheduler
     *            the {@link Scheduler} to perform subscription actions on
     * @param requestOn if true, requests are rerouted to the given Scheduler as well (strong pipelining)
     *                  if false, requests coming from any thread are simply forwarded to
     *                  the upstream on the same thread (weak pipelining)
     * @return the source Publisher modified so that its subscriptions happen on the
     *         specified {@link Scheduler}
     * @see <a href="http://reactivex.io/documentation/operators/subscribeon.html">ReactiveX operators documentation: SubscribeOn</a>
     * @see <a href="http://www.grahamlea.com/2014/07/rxjava-threading-examples/">RxJava Threading Examples</a>
     * @see #observeOn
     * @since 2.1.1 - experimental
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    @Experimental
    public final Flowable<T> subscribeOn(@NonNull Scheduler scheduler, boolean requestOn) {
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return RxJavaPlugins.onAssembly(new FlowableSubscribeOn<T>(this, scheduler, requestOn));
    }

    /**
     * Returns a Flowable that emits the items emitted by the source Publisher or the items of an alternate
     * Publisher if the source Publisher is empty.
     * <p>
     * <img width="640" height="255" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/switchifempty.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>If the source {@code Publisher} is empty, the alternate {@code Publisher} is expected to honor backpressure.
     *  If the source {@code Publisher} is non-empty, it is expected to honor backpressure as instead.
     *  In either case, if violated, a {@code MissingBackpressureException} <em>may</em> get
     *  signalled somewhere downstream.
     *  </dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code switchIfEmpty} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param other
     *              the alternate Publisher to subscribe to if the source does not emit any items
     * @return  a Publisher that emits the items emitted by the source Publisher or the items of an
     *          alternate Publisher if the source Publisher is empty.
     * @since 1.1.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> switchIfEmpty(Publisher<? extends T> other) {
        ObjectHelper.requireNonNull(other, "other is null");
        return RxJavaPlugins.onAssembly(new FlowableSwitchIfEmpty<T>(this, other));
    }

    /**
     * Returns a new Publisher by applying a function that you supply to each item emitted by the source
     * Publisher that returns a Publisher, and then emitting the items emitted by the most recently emitted
     * of these Publishers.
     * <p>
     * The resulting Publisher completes if both the upstream Publisher and the last inner Publisher, if any, complete.
     * If the upstream Publisher signals an onError, the inner Publisher is cancelled and the error delivered in-sequence.
     * <p>
     * <img width="640" height="350" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/switchMap.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The outer {@code Publisher} is consumed in an
     *  unbounded manner (i.e., without backpressure) and the inner {@code Publisher}s are expected to honor
     *  backpressure but it is not enforced; the operator won't signal a {@code MissingBackpressureException}
     *  but the violation <em>may</em> lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code switchMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the element type of the inner Publishers and the output
     * @param mapper
     *            a function that, when applied to an item emitted by the source Publisher, returns an
     *            Publisher
     * @return a Flowable that emits the items emitted by the Publisher returned from applying {@code func} to the most recently emitted item emitted by the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> switchMap(Function<? super T, ? extends Publisher<? extends R>> mapper) {
        return switchMap(mapper, bufferSize());
    }

    /**
     * Returns a new Publisher by applying a function that you supply to each item emitted by the source
     * Publisher that returns a Publisher, and then emitting the items emitted by the most recently emitted
     * of these Publishers.
     * <p>
     * The resulting Publisher completes if both the upstream Publisher and the last inner Publisher, if any, complete.
     * If the upstream Publisher signals an onError, the inner Publisher is cancelled and the error delivered in-sequence.
     * <p>
     * <img width="640" height="350" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/switchMap.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The outer {@code Publisher} is consumed in an
     *  unbounded manner (i.e., without backpressure) and the inner {@code Publisher}s are expected to honor
     *  backpressure but it is not enforced; the operator won't signal a {@code MissingBackpressureException}
     *  but the violation <em>may</em> lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code switchMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the element type of the inner Publishers and the output
     * @param mapper
     *            a function that, when applied to an item emitted by the source Publisher, returns an
     *            Publisher
     * @param bufferSize
     *            the number of elements to prefetch from the current active inner Publisher
     * @return a Flowable that emits the items emitted by the Publisher returned from applying {@code func} to the most recently emitted item emitted by the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> switchMap(Function<? super T, ? extends Publisher<? extends R>> mapper, int bufferSize) {
        return switchMap0(mapper, bufferSize, false);
    }

    /**
     * Returns a new Publisher by applying a function that you supply to each item emitted by the source
     * Publisher that returns a Publisher, and then emitting the items emitted by the most recently emitted
     * of these Publishers and delays any error until all Publishers terminate.
     * <p>
     * The resulting Publisher completes if both the upstream Publisher and the last inner Publisher, if any, complete.
     * If the upstream Publisher signals an onError, the termination of the last inner Publisher will emit that error as is
     * or wrapped into a CompositeException along with the other possible errors the former inner Publishers signalled.
     * <p>
     * <img width="640" height="350" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/switchMap.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The outer {@code Publisher} is consumed in an
     *  unbounded manner (i.e., without backpressure) and the inner {@code Publisher}s are expected to honor
     *  backpressure but it is not enforced; the operator won't signal a {@code MissingBackpressureException}
     *  but the violation <em>may</em> lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code switchMapDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the element type of the inner Publishers and the output
     * @param mapper
     *            a function that, when applied to an item emitted by the source Publisher, returns an
     *            Publisher
     * @return a Flowable that emits the items emitted by the Publisher returned from applying {@code func} to the most recently emitted item emitted by the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.SPECIAL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> switchMapDelayError(Function<? super T, ? extends Publisher<? extends R>> mapper) {
        return switchMapDelayError(mapper, bufferSize());
    }

    /**
     * Returns a new Publisher by applying a function that you supply to each item emitted by the source
     * Publisher that returns a Publisher, and then emitting the items emitted by the most recently emitted
     * of these Publishers and delays any error until all Publishers terminate.
     * <p>
     * The resulting Publisher completes if both the upstream Publisher and the last inner Publisher, if any, complete.
     * If the upstream Publisher signals an onError, the termination of the last inner Publisher will emit that error as is
     * or wrapped into a CompositeException along with the other possible errors the former inner Publishers signalled.
     * <p>
     * <img width="640" height="350" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/switchMap.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The outer {@code Publisher} is consumed in an
     *  unbounded manner (i.e., without backpressure) and the inner {@code Publisher}s are expected to honor
     *  backpressure but it is not enforced; the operator won't signal a {@code MissingBackpressureException}
     *  but the violation <em>may</em> lead to {@code OutOfMemoryError} due to internal buffer bloat.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code switchMapDelayError} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the element type of the inner Publishers and the output
     * @param mapper
     *            a function that, when applied to an item emitted by the source Publisher, returns an
     *            Publisher
     * @param bufferSize
     *            the number of elements to prefetch from the current active inner Publisher
     * @return a Flowable that emits the items emitted by the Publisher returned from applying {@code func} to the most recently emitted item emitted by the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/flatmap.html">ReactiveX operators documentation: FlatMap</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.SPECIAL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> switchMapDelayError(Function<? super T, ? extends Publisher<? extends R>> mapper, int bufferSize) {
        return switchMap0(mapper, bufferSize, true);
    }

    <R> Flowable<R> switchMap0(Function<? super T, ? extends Publisher<? extends R>> mapper, int bufferSize, boolean delayError) {
        ObjectHelper.requireNonNull(mapper, "mapper is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        if (this instanceof ScalarCallable) {
            @SuppressWarnings("unchecked")
            T v = ((ScalarCallable<T>)this).call();
            if (v == null) {
                return empty();
            }
            return FlowableScalarXMap.scalarXMap(v, mapper);
        }
        return RxJavaPlugins.onAssembly(new FlowableSwitchMap<T, R>(this, mapper, bufferSize, delayError));
    }

    /**
     * Returns a Flowable that emits only the first {@code count} items emitted by the source Publisher. If the source emits fewer than
     * {@code count} items then all of its items are emitted.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/take.png" alt="">
     * <p>
     * This method returns a Publisher that will invoke a subscribing {@link Subscriber}'s
     * {@link Subscriber#onNext onNext} function a maximum of {@code count} times before invoking
     * {@link Subscriber#onComplete onComplete}.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior in case the first request is smaller than the {@code count}. Otherwise, the source {@code Publisher}
     *  is consumed in an unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code take} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param count
     *            the maximum number of items to emit
     * @return a Flowable that emits only the first {@code count} items emitted by the source Publisher, or
     *         all of the items from the source Publisher if that Publisher emits fewer than {@code count} items
     * @see <a href="http://reactivex.io/documentation/operators/take.html">ReactiveX operators documentation: Take</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.SPECIAL) // may trigger UNBOUNDED_IN
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> take(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("count >= 0 required but it was " + count);
        }
        return RxJavaPlugins.onAssembly(new FlowableTake<T>(this, count));
    }

    /**
     * Returns a Flowable that emits those items emitted by source Publisher before a specified time runs
     * out.
     * <p>
     * If time runs out before the {@code Flowable} completes normally, the {@code onComplete} event will be
     * signaled on the default {@code computation} {@link Scheduler}.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/take.t.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code take} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param time
     *            the length of the time window
     * @param unit
     *            the time unit of {@code time}
     * @return a Flowable that emits those items emitted by the source Publisher before the time runs out
     * @see <a href="http://reactivex.io/documentation/operators/take.html">ReactiveX operators documentation: Take</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<T> take(long time, TimeUnit unit) {
        return takeUntil(timer(time, unit));
    }

    /**
     * Returns a Flowable that emits those items emitted by source Publisher before a specified time (on a
     * specified Scheduler) runs out.
     * <p>
     * If time runs out before the {@code Flowable} completes normally, the {@code onComplete} event will be
     * signaled on the provided {@link Scheduler}.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/take.ts.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param time
     *            the length of the time window
     * @param unit
     *            the time unit of {@code time}
     * @param scheduler
     *            the Scheduler used for time source
     * @return a Flowable that emits those items emitted by the source Publisher before the time runs out,
     *         according to the specified Scheduler
     * @see <a href="http://reactivex.io/documentation/operators/take.html">ReactiveX operators documentation: Take</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> take(long time, TimeUnit unit, Scheduler scheduler) {
        return takeUntil(timer(time, unit, scheduler));
    }

    /**
     * Returns a Flowable that emits at most the last {@code count} items emitted by the source Publisher. If the source emits fewer than
     * {@code count} items then all of its items are emitted.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/takeLast.n.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream if the {@code count} is non-zero; ignores
     *  backpressure if the {@code count} is zero as it doesn't signal any values.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code takeLast} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param count
     *            the maximum number of items to emit from the end of the sequence of items emitted by the source
     *            Publisher
     * @return a Flowable that emits at most the last {@code count} items emitted by the source Publisher
     * @throws IndexOutOfBoundsException
     *             if {@code count} is less than zero
     * @see <a href="http://reactivex.io/documentation/operators/takelast.html">ReactiveX operators documentation: TakeLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> takeLast(int count) {
        if (count < 0) {
            throw new IndexOutOfBoundsException("count >= 0 required but it was " + count);
        } else
        if (count == 0) {
            return RxJavaPlugins.onAssembly(new FlowableIgnoreElements<T>(this));
        } else
        if (count == 1) {
            return RxJavaPlugins.onAssembly(new FlowableTakeLastOne<T>(this));
        }
        return RxJavaPlugins.onAssembly(new FlowableTakeLast<T>(this, count));
    }

    /**
     * Returns a Flowable that emits at most a specified number of items from the source Publisher that were
     * emitted in a specified window of time before the Publisher completed.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/takeLast.tn.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., no backpressure is applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code takeLast} does not operate on any particular scheduler but uses the current time
     *  from the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param count
     *            the maximum number of items to emit
     * @param time
     *            the length of the time window
     * @param unit
     *            the time unit of {@code time}
     * @return a Flowable that emits at most {@code count} items from the source Publisher that were emitted
     *         in a specified window of time before the Publisher completed
     * @see <a href="http://reactivex.io/documentation/operators/takelast.html">ReactiveX operators documentation: TakeLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> takeLast(long count, long time, TimeUnit unit) {
        return takeLast(count, time, unit, Schedulers.computation(), false, bufferSize());
    }

    /**
     * Returns a Flowable that emits at most a specified number of items from the source Publisher that were
     * emitted in a specified window of time before the Publisher completed, where the timing information is
     * provided by a given Scheduler.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/takeLast.tns.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., no backpressure is applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use for tracking the current time</dd>
     * </dl>
     *
     * @param count
     *            the maximum number of items to emit
     * @param time
     *            the length of the time window
     * @param unit
     *            the time unit of {@code time}
     * @param scheduler
     *            the {@link Scheduler} that provides the timestamps for the observed items
     * @return a Flowable that emits at most {@code count} items from the source Publisher that were emitted
     *         in a specified window of time before the Publisher completed, where the timing information is
     *         provided by the given {@code scheduler}
     * @throws IndexOutOfBoundsException
     *             if {@code count} is less than zero
     * @see <a href="http://reactivex.io/documentation/operators/takelast.html">ReactiveX operators documentation: TakeLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> takeLast(long count, long time, TimeUnit unit, Scheduler scheduler) {
        return takeLast(count, time, unit, scheduler, false, bufferSize());
    }

    /**
     * Returns a Flowable that emits at most a specified number of items from the source Publisher that were
     * emitted in a specified window of time before the Publisher completed, where the timing information is
     * provided by a given Scheduler.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/takeLast.tns.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., no backpressure is applied to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use for tracking the current time</dd>
     * </dl>
     *
     * @param count
     *            the maximum number of items to emit
     * @param time
     *            the length of the time window
     * @param unit
     *            the time unit of {@code time}
     * @param scheduler
     *            the {@link Scheduler} that provides the timestamps for the observed items
     * @param delayError
     *            if true, an exception signalled by the current Flowable is delayed until the regular elements are consumed
     *            by the downstream; if false, an exception is immediately signalled and all regular elements dropped
     * @param bufferSize
     *            the hint about how many elements to expect to be last
     * @return a Flowable that emits at most {@code count} items from the source Publisher that were emitted
     *         in a specified window of time before the Publisher completed, where the timing information is
     *         provided by the given {@code scheduler}
     * @throws IndexOutOfBoundsException
     *             if {@code count} is less than zero
     * @see <a href="http://reactivex.io/documentation/operators/takelast.html">ReactiveX operators documentation: TakeLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> takeLast(long count, long time, TimeUnit unit, Scheduler scheduler, boolean delayError, int bufferSize) {
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        if (count < 0) {
            throw new IndexOutOfBoundsException("count >= 0 required but it was " + count);
        }
        return RxJavaPlugins.onAssembly(new FlowableTakeLastTimed<T>(this, count, time, unit, scheduler, bufferSize, delayError));
    }

    /**
     * Returns a Flowable that emits the items from the source Publisher that were emitted in a specified
     * window of time before the Publisher completed.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/takeLast.t.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., no backpressure is applied to it) but note that this <em>may</em>
     *  lead to {@code OutOfMemoryError} due to internal buffer bloat.
     *  Consider using {@link #takeLast(long, long, TimeUnit)} in this case.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code takeLast} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param time
     *            the length of the time window
     * @param unit
     *            the time unit of {@code time}
     * @return a Flowable that emits the items from the source Publisher that were emitted in the window of
     *         time before the Publisher completed specified by {@code time}
     * @see <a href="http://reactivex.io/documentation/operators/takelast.html">ReactiveX operators documentation: TakeLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<T> takeLast(long time, TimeUnit unit) {
        return takeLast(time, unit, Schedulers.computation(), false, bufferSize());
    }

    /**
     * Returns a Flowable that emits the items from the source Publisher that were emitted in a specified
     * window of time before the Publisher completed.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/takeLast.t.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., no backpressure is applied to it) but note that this <em>may</em>
     *  lead to {@code OutOfMemoryError} due to internal buffer bloat.
     *  Consider using {@link #takeLast(long, long, TimeUnit)} in this case.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code takeLast} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param time
     *            the length of the time window
     * @param unit
     *            the time unit of {@code time}
     * @param delayError
     *            if true, an exception signalled by the current Flowable is delayed until the regular elements are consumed
     *            by the downstream; if false, an exception is immediately signalled and all regular elements dropped
     * @return a Flowable that emits the items from the source Publisher that were emitted in the window of
     *         time before the Publisher completed specified by {@code time}
     * @see <a href="http://reactivex.io/documentation/operators/takelast.html">ReactiveX operators documentation: TakeLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<T> takeLast(long time, TimeUnit unit, boolean delayError) {
        return takeLast(time, unit, Schedulers.computation(), delayError, bufferSize());
    }

    /**
     * Returns a Flowable that emits the items from the source Publisher that were emitted in a specified
     * window of time before the Publisher completed, where the timing information is provided by a specified
     * Scheduler.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/takeLast.ts.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., no backpressure is applied to it) but note that this <em>may</em>
     *  lead to {@code OutOfMemoryError} due to internal buffer bloat.
     *  Consider using {@link #takeLast(long, long, TimeUnit, Scheduler)} in this case.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param time
     *            the length of the time window
     * @param unit
     *            the time unit of {@code time}
     * @param scheduler
     *            the Scheduler that provides the timestamps for the Observed items
     * @return a Flowable that emits the items from the source Publisher that were emitted in the window of
     *         time before the Publisher completed specified by {@code time}, where the timing information is
     *         provided by {@code scheduler}
     * @see <a href="http://reactivex.io/documentation/operators/takelast.html">ReactiveX operators documentation: TakeLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> takeLast(long time, TimeUnit unit, Scheduler scheduler) {
        return takeLast(time, unit, scheduler, false, bufferSize());
    }

    /**
     * Returns a Flowable that emits the items from the source Publisher that were emitted in a specified
     * window of time before the Publisher completed, where the timing information is provided by a specified
     * Scheduler.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/takeLast.ts.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., no backpressure is applied to it) but note that this <em>may</em>
     *  lead to {@code OutOfMemoryError} due to internal buffer bloat.
     *  Consider using {@link #takeLast(long, long, TimeUnit, Scheduler)} in this case.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param time
     *            the length of the time window
     * @param unit
     *            the time unit of {@code time}
     * @param scheduler
     *            the Scheduler that provides the timestamps for the Observed items
     * @param delayError
     *            if true, an exception signalled by the current Flowable is delayed until the regular elements are consumed
     *            by the downstream; if false, an exception is immediately signalled and all regular elements dropped
     * @return a Flowable that emits the items from the source Publisher that were emitted in the window of
     *         time before the Publisher completed specified by {@code time}, where the timing information is
     *         provided by {@code scheduler}
     * @see <a href="http://reactivex.io/documentation/operators/takelast.html">ReactiveX operators documentation: TakeLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> takeLast(long time, TimeUnit unit, Scheduler scheduler, boolean delayError) {
        return takeLast(time, unit, scheduler, delayError, bufferSize());
    }

    /**
     * Returns a Flowable that emits the items from the source Publisher that were emitted in a specified
     * window of time before the Publisher completed, where the timing information is provided by a specified
     * Scheduler.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/takeLast.ts.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., no backpressure is applied to it) but note that this <em>may</em>
     *  lead to {@code OutOfMemoryError} due to internal buffer bloat.
     *  Consider using {@link #takeLast(long, long, TimeUnit, Scheduler)} in this case.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param time
     *            the length of the time window
     * @param unit
     *            the time unit of {@code time}
     * @param scheduler
     *            the Scheduler that provides the timestamps for the Observed items
     * @param delayError
     *            if true, an exception signalled by the current Flowable is delayed until the regular elements are consumed
     *            by the downstream; if false, an exception is immediately signalled and all regular elements dropped
     * @param bufferSize
     *            the hint about how many elements to expect to be last
     * @return a Flowable that emits the items from the source Publisher that were emitted in the window of
     *         time before the Publisher completed specified by {@code time}, where the timing information is
     *         provided by {@code scheduler}
     * @see <a href="http://reactivex.io/documentation/operators/takelast.html">ReactiveX operators documentation: TakeLast</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> takeLast(long time, TimeUnit unit, Scheduler scheduler, boolean delayError, int bufferSize) {
        return takeLast(Long.MAX_VALUE, time, unit, scheduler, delayError, bufferSize);
    }

    /**
     * Returns a Flowable that emits items emitted by the source Publisher, checks the specified predicate
     * for each item, and then completes when the condition is satisfied.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/takeUntil.p.png" alt="">
     * <p>
     * The difference between this operator and {@link #takeWhile(Predicate)} is that here, the condition is
     * evaluated <em>after</em> the item is emitted.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator is a pass-through for backpressure; the backpressure behavior is determined by the upstream
     *  source and the downstream consumer.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code takeUntil} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param stopPredicate
     *            a function that evaluates an item emitted by the source Publisher and returns a Boolean
     * @return a Flowable that first emits items emitted by the source Publisher, checks the specified
     *         condition after each item, and then completes when the condition is satisfied.
     * @see <a href="http://reactivex.io/documentation/operators/takeuntil.html">ReactiveX operators documentation: TakeUntil</a>
     * @see Flowable#takeWhile(Predicate)
     * @since 1.1.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> takeUntil(Predicate<? super T> stopPredicate) {
        ObjectHelper.requireNonNull(stopPredicate, "stopPredicate is null");
        return RxJavaPlugins.onAssembly(new FlowableTakeUntilPredicate<T>(this, stopPredicate));
    }

    /**
     * Returns a Flowable that emits the items emitted by the source Publisher until a second Publisher
     * emits an item.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/takeUntil.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code takeUntil} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param other
     *            the Publisher whose first emitted item will cause {@code takeUntil} to stop emitting items
     *            from the source Publisher
     * @param <U>
     *            the type of items emitted by {@code other}
     * @return a Flowable that emits the items emitted by the source Publisher until such time as {@code other} emits its first item
     * @see <a href="http://reactivex.io/documentation/operators/takeuntil.html">ReactiveX operators documentation: TakeUntil</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U> Flowable<T> takeUntil(Publisher<U> other) {
        ObjectHelper.requireNonNull(other, "other is null");
        return RxJavaPlugins.onAssembly(new FlowableTakeUntil<T, U>(this, other));
    }

    /**
     * Returns a Flowable that emits items emitted by the source Publisher so long as each item satisfied a
     * specified condition, and then completes as soon as this condition is not satisfied.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/takeWhile.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code takeWhile} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param predicate
     *            a function that evaluates an item emitted by the source Publisher and returns a Boolean
     * @return a Flowable that emits the items from the source Publisher so long as each item satisfies the
     *         condition defined by {@code predicate}, then completes
     * @see <a href="http://reactivex.io/documentation/operators/takewhile.html">ReactiveX operators documentation: TakeWhile</a>
     * @see Flowable#takeUntil(Predicate)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<T> takeWhile(Predicate<? super T> predicate) {
        ObjectHelper.requireNonNull(predicate, "predicate is null");
        return RxJavaPlugins.onAssembly(new FlowableTakeWhile<T>(this, predicate));
    }

    /**
     * Returns a Flowable that emits only the first item emitted by the source Publisher during sequential
     * time windows of a specified duration.
     * <p>
     * This differs from {@link #throttleLast} in that this only tracks passage of time whereas
     * {@link #throttleLast} ticks at scheduled intervals.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/throttleFirst.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time to control data flow.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code throttleFirst} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param windowDuration
     *            time to wait before emitting another item after emitting the last item
     * @param unit
     *            the unit of time of {@code windowDuration}
     * @return a Flowable that performs the throttle operation
     * @see <a href="http://reactivex.io/documentation/operators/sample.html">ReactiveX operators documentation: Sample</a>
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Backpressure">RxJava wiki: Backpressure</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<T> throttleFirst(long windowDuration, TimeUnit unit) {
        return throttleFirst(windowDuration, unit, Schedulers.computation());
    }

    /**
     * Returns a Flowable that emits only the first item emitted by the source Publisher during sequential
     * time windows of a specified duration, where the windows are managed by a specified Scheduler.
     * <p>
     * This differs from {@link #throttleLast} in that this only tracks passage of time whereas
     * {@link #throttleLast} ticks at scheduled intervals.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/throttleFirst.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time to control data flow.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param skipDuration
     *            time to wait before emitting another item after emitting the last item
     * @param unit
     *            the unit of time of {@code skipDuration}
     * @param scheduler
     *            the {@link Scheduler} to use internally to manage the timers that handle timeout for each
     *            event
     * @return a Flowable that performs the throttle operation
     * @see <a href="http://reactivex.io/documentation/operators/sample.html">ReactiveX operators documentation: Sample</a>
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Backpressure">RxJava wiki: Backpressure</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> throttleFirst(long skipDuration, TimeUnit unit, Scheduler scheduler) {
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return RxJavaPlugins.onAssembly(new FlowableThrottleFirstTimed<T>(this, skipDuration, unit, scheduler));
    }

    /**
     * Returns a Flowable that emits only the last item emitted by the source Publisher during sequential
     * time windows of a specified duration.
     * <p>
     * This differs from {@link #throttleFirst} in that this ticks along at a scheduled interval whereas
     * {@link #throttleFirst} does not tick, it just tracks passage of time.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/throttleLast.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time to control data flow.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code throttleLast} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param intervalDuration
     *            duration of windows within which the last item emitted by the source Publisher will be
     *            emitted
     * @param unit
     *            the unit of time of {@code intervalDuration}
     * @return a Flowable that performs the throttle operation
     * @see <a href="http://reactivex.io/documentation/operators/sample.html">ReactiveX operators documentation: Sample</a>
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Backpressure">RxJava wiki: Backpressure</a>
     * @see #sample(long, TimeUnit)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<T> throttleLast(long intervalDuration, TimeUnit unit) {
        return sample(intervalDuration, unit);
    }

    /**
     * Returns a Flowable that emits only the last item emitted by the source Publisher during sequential
     * time windows of a specified duration, where the duration is governed by a specified Scheduler.
     * <p>
     * This differs from {@link #throttleFirst} in that this ticks along at a scheduled interval whereas
     * {@link #throttleFirst} does not tick, it just tracks passage of time.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/throttleLast.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time to control data flow.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param intervalDuration
     *            duration of windows within which the last item emitted by the source Publisher will be
     *            emitted
     * @param unit
     *            the unit of time of {@code intervalDuration}
     * @param scheduler
     *            the {@link Scheduler} to use internally to manage the timers that handle timeout for each
     *            event
     * @return a Flowable that performs the throttle operation
     * @see <a href="http://reactivex.io/documentation/operators/sample.html">ReactiveX operators documentation: Sample</a>
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Backpressure">RxJava wiki: Backpressure</a>
     * @see #sample(long, TimeUnit, Scheduler)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> throttleLast(long intervalDuration, TimeUnit unit, Scheduler scheduler) {
        return sample(intervalDuration, unit, scheduler);
    }

    /**
     * Returns a Flowable that only emits those items emitted by the source Publisher that are not followed
     * by another emitted item within a specified time window.
     * <p>
     * <em>Note:</em> If the source Publisher keeps emitting items more frequently than the length of the time
     * window then no items will be emitted by the resulting Publisher.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/throttleWithTimeout.png" alt="">
     * <p>
     * Information on debounce vs throttle:
     * <ul>
     * <li><a href="http://drupalmotion.com/article/debounce-and-throttle-visual-explanation">Debounce and Throttle: visual explanation</a></li>
     * <li><a href="http://unscriptable.com/2009/03/20/debouncing-javascript-methods/">Debouncing: javascript methods</a></li>
     * <li><a href="http://www.illyriad.co.uk/blog/index.php/2011/09/javascript-dont-spam-your-server-debounce-and-throttle/">Javascript - don't spam your server: debounce and throttle</a></li>
     * </ul>
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time to control data flow.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code throttleWithTimeout} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param timeout
     *            the length of the window of time that must pass after the emission of an item from the source
     *            Publisher in which that Publisher emits no items in order for the item to be emitted by the
     *            resulting Publisher
     * @param unit
     *            the {@link TimeUnit} of {@code timeout}
     * @return a Flowable that filters out items that are too quickly followed by newer items
     * @see <a href="http://reactivex.io/documentation/operators/debounce.html">ReactiveX operators documentation: Debounce</a>
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Backpressure">RxJava wiki: Backpressure</a>
     * @see #debounce(long, TimeUnit)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<T> throttleWithTimeout(long timeout, TimeUnit unit) {
        return debounce(timeout, unit);
    }

    /**
     * Returns a Flowable that only emits those items emitted by the source Publisher that are not followed
     * by another emitted item within a specified time window, where the time window is governed by a specified
     * Scheduler.
     * <p>
     * <em>Note:</em> If the source Publisher keeps emitting items more frequently than the length of the time
     * window then no items will be emitted by the resulting Publisher.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/throttleWithTimeout.s.png" alt="">
     * <p>
     * Information on debounce vs throttle:
     * <ul>
     * <li><a href="http://drupalmotion.com/article/debounce-and-throttle-visual-explanation">Debounce and Throttle: visual explanation</a></li>
     * <li><a href="http://unscriptable.com/2009/03/20/debouncing-javascript-methods/">Debouncing: javascript methods</a></li>
     * <li><a href="http://www.illyriad.co.uk/blog/index.php/2011/09/javascript-dont-spam-your-server-debounce-and-throttle/">Javascript - don't spam your server: debounce and throttle</a></li>
     * </ul>
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as it uses time to control data flow.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param timeout
     *            the length of the window of time that must pass after the emission of an item from the source
     *            Publisher in which that Publisher emits no items in order for the item to be emitted by the
     *            resulting Publisher
     * @param unit
     *            the {@link TimeUnit} of {@code timeout}
     * @param scheduler
     *            the {@link Scheduler} to use internally to manage the timers that handle the timeout for each
     *            item
     * @return a Flowable that filters out items that are too quickly followed by newer items
     * @see <a href="http://reactivex.io/documentation/operators/debounce.html">ReactiveX operators documentation: Debounce</a>
     * @see <a href="https://github.com/ReactiveX/RxJava/wiki/Backpressure">RxJava wiki: Backpressure</a>
     * @see #debounce(long, TimeUnit, Scheduler)
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> throttleWithTimeout(long timeout, TimeUnit unit, Scheduler scheduler) {
        return debounce(timeout, unit, scheduler);
    }

    /**
     * Returns a Flowable that emits records of the time interval between consecutive items emitted by the
     * source Publisher.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timeInterval.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code timeInterval} does not operate on any particular scheduler but uses the current time
     *  from the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a Flowable that emits time interval information items
     * @see <a href="http://reactivex.io/documentation/operators/timeinterval.html">ReactiveX operators documentation: TimeInterval</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<Timed<T>> timeInterval() {
        return timeInterval(TimeUnit.MILLISECONDS, Schedulers.computation());
    }

    /**
     * Returns a Flowable that emits records of the time interval between consecutive items emitted by the
     * source Publisher, where this interval is computed on a specified Scheduler.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timeInterval.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>The operator does not operate on any particular scheduler but uses the current time
     *  from the specified {@link Scheduler}.</dd>
     * </dl>
     *
     * @param scheduler
     *            the {@link Scheduler} used to compute time intervals
     * @return a Flowable that emits time interval information items
     * @see <a href="http://reactivex.io/documentation/operators/timeinterval.html">ReactiveX operators documentation: TimeInterval</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE) // Supplied scheduler is only used for creating timestamps.
    public final Flowable<Timed<T>> timeInterval(Scheduler scheduler) {
        return timeInterval(TimeUnit.MILLISECONDS, scheduler);
    }

    /**
     * Returns a Flowable that emits records of the time interval between consecutive items emitted by the
     * source Publisher.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timeInterval.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code timeInterval} does not operate on any particular scheduler but uses the current time
     *  from the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param unit the time unit for the current time
     * @return a Flowable that emits time interval information items
     * @see <a href="http://reactivex.io/documentation/operators/timeinterval.html">ReactiveX operators documentation: TimeInterval</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<Timed<T>> timeInterval(TimeUnit unit) {
        return timeInterval(unit, Schedulers.computation());
    }

    /**
     * Returns a Flowable that emits records of the time interval between consecutive items emitted by the
     * source Publisher, where this interval is computed on a specified Scheduler.
     * <p>
     * <img width="640" height="315" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timeInterval.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>The operator does not operate on any particular scheduler but uses the current time
     *  from the specified {@link Scheduler}.</dd>
     * </dl>
     *
     * @param unit the time unit for the current time
     * @param scheduler
     *            the {@link Scheduler} used to compute time intervals
     * @return a Flowable that emits time interval information items
     * @see <a href="http://reactivex.io/documentation/operators/timeinterval.html">ReactiveX operators documentation: TimeInterval</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE) // Supplied scheduler is only used for creating timestamps.
    public final Flowable<Timed<T>> timeInterval(TimeUnit unit, Scheduler scheduler) {
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return RxJavaPlugins.onAssembly(new FlowableTimeInterval<T>(this, unit, scheduler));
    }

    /**
     * Returns a Flowable that mirrors the source Publisher, but notifies Subscribers of a
     * {@code TimeoutException} if an item emitted by the source Publisher doesn't arrive within a window of
     * time after the emission of the previous item, where that period of time is measured by a Publisher that
     * is a function of the previous item.
     * <p>
     * <img width="640" height="400" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timeout3.png" alt="">
     * <p>
     * Note: The arrival of the first source item is never timed out.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The {@code Publisher}
     *  sources are expected to honor backpressure as well.
     *  If any of the source {@code Publisher}s violate this, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code timeout} operates by default on the {@code immediate} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <V>
     *            the timeout value type (ignored)
     * @param itemTimeoutIndicator
     *            a function that returns a Publisher for each item emitted by the source
     *            Publisher and that determines the timeout window for the subsequent item
     * @return a Flowable that mirrors the source Publisher, but notifies Subscribers of a
     *         {@code TimeoutException} if an item emitted by the source Publisher takes longer to arrive than
     *         the time window defined by the selector for the previously emitted item
     * @see <a href="http://reactivex.io/documentation/operators/timeout.html">ReactiveX operators documentation: Timeout</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <V> Flowable<T> timeout(Function<? super T, ? extends Publisher<V>> itemTimeoutIndicator) {
        return timeout0(null, itemTimeoutIndicator, null);
    }

    /**
     * Returns a Flowable that mirrors the source Publisher, but that switches to a fallback Publisher if
     * an item emitted by the source Publisher doesn't arrive within a window of time after the emission of the
     * previous item, where that period of time is measured by a Publisher that is a function of the previous
     * item.
     * <p>
     * <img width="640" height="400" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timeout4.png" alt="">
     * <p>
     * Note: The arrival of the first source item is never timed out.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The {@code Publisher}
     *  sources are expected to honor backpressure as well.
     *  If any of the source {@code Publisher}s violate this, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code timeout} operates by default on the {@code immediate} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <V>
     *            the timeout value type (ignored)
     * @param itemTimeoutIndicator
     *            a function that returns a Publisher, for each item emitted by the source Publisher, that
     *            determines the timeout window for the subsequent item
     * @param other
     *            the fallback Publisher to switch to if the source Publisher times out
     * @return a Flowable that mirrors the source Publisher, but switches to mirroring a fallback Publisher
     *         if an item emitted by the source Publisher takes longer to arrive than the time window defined
     *         by the selector for the previously emitted item
     * @see <a href="http://reactivex.io/documentation/operators/timeout.html">ReactiveX operators documentation: Timeout</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <V> Flowable<T> timeout(Function<? super T, ? extends Publisher<V>> itemTimeoutIndicator, Flowable<? extends T> other) {
        ObjectHelper.requireNonNull(other, "other is null");
        return timeout0(null, itemTimeoutIndicator, other);
    }

    /**
     * Returns a Flowable that mirrors the source Publisher but applies a timeout policy for each emitted
     * item. If the next item isn't emitted within the specified timeout duration starting from its predecessor,
     * the resulting Publisher terminates and notifies Subscribers of a {@code TimeoutException}.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timeout.1.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code timeout} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param timeout
     *            maximum duration between emitted items before a timeout occurs
     * @param timeUnit
     *            the unit of time that applies to the {@code timeout} argument.
     * @return the source Publisher modified to notify Subscribers of a {@code TimeoutException} in case of a
     *         timeout
     * @see <a href="http://reactivex.io/documentation/operators/timeout.html">ReactiveX operators documentation: Timeout</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<T> timeout(long timeout, TimeUnit timeUnit) {
        return timeout0(timeout, timeUnit, null, Schedulers.computation());
    }

    /**
     * Returns a Flowable that mirrors the source Publisher but applies a timeout policy for each emitted
     * item. If the next item isn't emitted within the specified timeout duration starting from its predecessor,
     * the resulting Publisher begins instead to mirror a fallback Publisher.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timeout.2.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The {@code Publisher}
     *  sources are expected to honor backpressure as well.
     *  If any of the source {@code Publisher}s violate this, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code timeout} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param timeout
     *            maximum duration between items before a timeout occurs
     * @param timeUnit
     *            the unit of time that applies to the {@code timeout} argument
     * @param other
     *            the fallback Publisher to use in case of a timeout
     * @return the source Publisher modified to switch to the fallback Publisher in case of a timeout
     * @see <a href="http://reactivex.io/documentation/operators/timeout.html">ReactiveX operators documentation: Timeout</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<T> timeout(long timeout, TimeUnit timeUnit, Publisher<? extends T> other) {
        ObjectHelper.requireNonNull(other, "other is null");
        return timeout0(timeout, timeUnit, other, Schedulers.computation());
    }

    /**
     * Returns a Flowable that mirrors the source Publisher but applies a timeout policy for each emitted
     * item using a specified Scheduler. If the next item isn't emitted within the specified timeout duration
     * starting from its predecessor, the resulting Publisher begins instead to mirror a fallback Publisher.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timeout.2s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The {@code Publisher}
     *  sources are expected to honor backpressure as well.
     *  If any of the source {@code Publisher}s violate this, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param timeout
     *            maximum duration between items before a timeout occurs
     * @param timeUnit
     *            the unit of time that applies to the {@code timeout} argument
     * @param scheduler
     *            the {@link Scheduler} to run the timeout timers on
     * @param other
     *            the Publisher to use as the fallback in case of a timeout
     * @return the source Publisher modified so that it will switch to the fallback Publisher in case of a
     *         timeout
     * @see <a href="http://reactivex.io/documentation/operators/timeout.html">ReactiveX operators documentation: Timeout</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> timeout(long timeout, TimeUnit timeUnit, Scheduler scheduler, Publisher<? extends T> other) {
        ObjectHelper.requireNonNull(other, "other is null");
        return timeout0(timeout, timeUnit, other, scheduler);
    }

    /**
     * Returns a Flowable that mirrors the source Publisher but applies a timeout policy for each emitted
     * item, where this policy is governed on a specified Scheduler. If the next item isn't emitted within the
     * specified timeout duration starting from its predecessor, the resulting Publisher terminates and
     * notifies Subscribers of a {@code TimeoutException}.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timeout.1s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param timeout
     *            maximum duration between items before a timeout occurs
     * @param timeUnit
     *            the unit of time that applies to the {@code timeout} argument
     * @param scheduler
     *            the Scheduler to run the timeout timers on
     * @return the source Publisher modified to notify Subscribers of a {@code TimeoutException} in case of a
     *         timeout
     * @see <a href="http://reactivex.io/documentation/operators/timeout.html">ReactiveX operators documentation: Timeout</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> timeout(long timeout, TimeUnit timeUnit, Scheduler scheduler) {
        return timeout0(timeout, timeUnit, null, scheduler);
    }

    /**
     * Returns a Flowable that mirrors the source Publisher, but notifies Subscribers of a
     * {@code TimeoutException} if either the first item emitted by the source Publisher or any subsequent item
     * doesn't arrive within time windows defined by other Publishers.
     * <p>
     * <img width="640" height="400" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timeout5.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. Both this and the returned {@code Publisher}s
     *  are expected to honor backpressure as well. If any of then violates this rule, it <em>may</em> throw an
     *  {@code IllegalStateException} when the {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code timeout} does not operates by default on any {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the first timeout value type (ignored)
     * @param <V>
     *            the subsequent timeout value type (ignored)
     * @param firstTimeoutIndicator
     *            a function that returns a Publisher that determines the timeout window for the first source
     *            item
     * @param itemTimeoutIndicator
     *            a function that returns a Publisher for each item emitted by the source Publisher and that
     *            determines the timeout window in which the subsequent source item must arrive in order to
     *            continue the sequence
     * @return a Flowable that mirrors the source Publisher, but notifies Subscribers of a
     *         {@code TimeoutException} if either the first item or any subsequent item doesn't arrive within
     *         the time windows specified by the timeout selectors
     * @see <a href="http://reactivex.io/documentation/operators/timeout.html">ReactiveX operators documentation: Timeout</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, V> Flowable<T> timeout(Publisher<U> firstTimeoutIndicator,
            Function<? super T, ? extends Publisher<V>> itemTimeoutIndicator) {
        ObjectHelper.requireNonNull(firstTimeoutIndicator, "firstTimeoutIndicator is null");
        return timeout0(firstTimeoutIndicator, itemTimeoutIndicator, null);
    }

    /**
     * Returns a Flowable that mirrors the source Publisher, but switches to a fallback Publisher if either
     * the first item emitted by the source Publisher or any subsequent item doesn't arrive within time windows
     * defined by other Publishers.
     * <p>
     * <img width="640" height="400" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timeout6.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream. The {@code Publisher}
     *  sources are expected to honor backpressure as well.
     *  If any of the source {@code Publisher}s violate this, it <em>may</em> throw an
     *  {@code IllegalStateException} when the source {@code Publisher} completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code timeout} does not operates by default on any {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the first timeout value type (ignored)
     * @param <V>
     *            the subsequent timeout value type (ignored)
     * @param firstTimeoutIndicator
     *            a function that returns a Publisher which determines the timeout window for the first source
     *            item
     * @param itemTimeoutIndicator
     *            a function that returns a Publisher for each item emitted by the source Publisher and that
     *            determines the timeout window in which the subsequent source item must arrive in order to
     *            continue the sequence
     * @param other
     *            the fallback Publisher to switch to if the source Publisher times out
     * @return a Flowable that mirrors the source Publisher, but switches to the {@code other} Publisher if
     *         either the first item emitted by the source Publisher or any subsequent item doesn't arrive
     *         within time windows defined by the timeout selectors
     * @throws NullPointerException
     *             if {@code itemTimeoutIndicator} is null
     * @see <a href="http://reactivex.io/documentation/operators/timeout.html">ReactiveX operators documentation: Timeout</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, V> Flowable<T> timeout(
            Publisher<U> firstTimeoutIndicator,
            Function<? super T, ? extends Publisher<V>> itemTimeoutIndicator,
                    Publisher<? extends T> other) {
        ObjectHelper.requireNonNull(firstTimeoutIndicator, "firstTimeoutSelector is null");
        ObjectHelper.requireNonNull(other, "other is null");
        return timeout0(firstTimeoutIndicator, itemTimeoutIndicator, other);
    }

    private Flowable<T> timeout0(long timeout, TimeUnit timeUnit, Publisher<? extends T> other,
            Scheduler scheduler) {
        ObjectHelper.requireNonNull(timeUnit, "timeUnit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return RxJavaPlugins.onAssembly(new FlowableTimeoutTimed<T>(this, timeout, timeUnit, scheduler, other));
    }

    private <U, V> Flowable<T> timeout0(
            Publisher<U> firstTimeoutIndicator,
            Function<? super T, ? extends Publisher<V>> itemTimeoutIndicator,
                    Publisher<? extends T> other) {
        ObjectHelper.requireNonNull(itemTimeoutIndicator, "itemTimeoutIndicator is null");
        return RxJavaPlugins.onAssembly(new FlowableTimeout<T, U, V>(this, firstTimeoutIndicator, itemTimeoutIndicator, other));
    }

    /**
     * Returns a Flowable that emits each item emitted by the source Publisher, wrapped in a
     * {@link Timed} object.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timestamp.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code timestamp} does not operate on any particular scheduler but uses the current time
     *  from the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a Flowable that emits timestamped items from the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/timestamp.html">ReactiveX operators documentation: Timestamp</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<Timed<T>> timestamp() {
        return timestamp(TimeUnit.MILLISECONDS, Schedulers.computation());
    }

    /**
     * Returns a Flowable that emits each item emitted by the source Publisher, wrapped in a
     * {@link Timed} object whose timestamps are provided by a specified Scheduler.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timestamp.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This operator does not operate on any particular scheduler but uses the current time
     *  from the specified {@link Scheduler}.</dd>
     * </dl>
     *
     * @param scheduler
     *            the {@link Scheduler} to use as a time source
     * @return a Flowable that emits timestamped items from the source Publisher with timestamps provided by
     *         the {@code scheduler}
     * @see <a href="http://reactivex.io/documentation/operators/timestamp.html">ReactiveX operators documentation: Timestamp</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE) // Supplied scheduler is only used for creating timestamps.
    public final Flowable<Timed<T>> timestamp(Scheduler scheduler) {
        return timestamp(TimeUnit.MILLISECONDS, scheduler);
    }

    /**
     * Returns a Flowable that emits each item emitted by the source Publisher, wrapped in a
     * {@link Timed} object.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timestamp.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code timestamp} does not operate on any particular scheduler but uses the current time
     *  from the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param unit the time unit for the current time
     * @return a Flowable that emits timestamped items from the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/timestamp.html">ReactiveX operators documentation: Timestamp</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<Timed<T>> timestamp(TimeUnit unit) {
        return timestamp(unit, Schedulers.computation());
    }

    /**
     * Returns a Flowable that emits each item emitted by the source Publisher, wrapped in a
     * {@link Timed} object whose timestamps are provided by a specified Scheduler.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/timestamp.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This operator does not operate on any particular scheduler but uses the current time
     *  from the specified {@link Scheduler}.</dd>
     * </dl>
     *
     * @param unit the time unit for the current time
     * @param scheduler
     *            the {@link Scheduler} to use as a time source
     * @return a Flowable that emits timestamped items from the source Publisher with timestamps provided by
     *         the {@code scheduler}
     * @see <a href="http://reactivex.io/documentation/operators/timestamp.html">ReactiveX operators documentation: Timestamp</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE) // Supplied scheduler is only used for creating timestamps.
    public final Flowable<Timed<T>> timestamp(final TimeUnit unit, final Scheduler scheduler) {
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return map(Functions.<T>timestampWith(unit, scheduler));
    }

    /**
     * Calls the specified converter function during assembly time and returns its resulting value.
     * <p>
     * This allows fluent conversion to any other type.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The backpressure behavior depends on what happens in the {@code converter} function.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code to} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param <R> the resulting object type
     * @param converter the function that receives the current Flowable instance and returns a value
     * @return the value returned by the function
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.SPECIAL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> R to(Function<? super Flowable<T>, R> converter) {
        try {
            return ObjectHelper.requireNonNull(converter, "converter is null").apply(this);
        } catch (Throwable ex) {
            Exceptions.throwIfFatal(ex);
            throw ExceptionHelper.wrapOrThrow(ex);
        }
    }

    /**
     * Returns a Single that emits a single item, a list composed of all the items emitted by the
     * finite upstream source Publisher.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/toList.png" alt="">
     * <p>
     * Normally, a Publisher that returns multiple items will do so by invoking its {@link Subscriber}'s
     * {@link Subscriber#onNext onNext} method for each such item. You can change this behavior, instructing the
     * Publisher to compose a list of all of these items and then to invoke the Subscriber's {@code onNext}
     * function once, passing it the entire list, by calling the Publisher's {@code toList} method prior to
     * calling its {@link #subscribe} method.
     * <p>
     * Note that this operator requires the upstream to signal {@code onComplete} for the accumulated list to
     * be emitted. Sources that are infinite and never complete will never emit anything through this
     * operator and an infinite source may lead to a fatal {@code OutOfMemoryError}.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toList} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @return a Single that emits a single item: a List containing all of the items emitted by the source
     *         Publisher
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX operators documentation: To</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<List<T>> toList() {
        return RxJavaPlugins.onAssembly(new FlowableToListSingle<T, List<T>>(this));
    }

    /**
     * Returns a Single that emits a single item, a list composed of all the items emitted by the
     * finite source Publisher.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/toList.png" alt="">
     * <p>
     * Normally, a Publisher that returns multiple items will do so by invoking its {@link Subscriber}'s
     * {@link Subscriber#onNext onNext} method for each such item. You can change this behavior, instructing the
     * Publisher to compose a list of all of these items and then to invoke the Subscriber's {@code onNext}
     * function once, passing it the entire list, by calling the Publisher's {@code toList} method prior to
     * calling its {@link #subscribe} method.
     * <p>
     * Note that this operator requires the upstream to signal {@code onComplete} for the accumulated list to
     * be emitted. Sources that are infinite and never complete will never emit anything through this
     * operator and an infinite source may lead to a fatal {@code OutOfMemoryError}.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toList} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param capacityHint
     *         the number of elements expected from the current Flowable
     * @return a Flowable that emits a single item: a List containing all of the items emitted by the source
     *         Publisher
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX operators documentation: To</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<List<T>> toList(final int capacityHint) {
        ObjectHelper.verifyPositive(capacityHint, "capacityHint");
        return RxJavaPlugins.onAssembly(new FlowableToListSingle<T, List<T>>(this, Functions.<T>createArrayList(capacityHint)));
    }

    /**
     * Returns a Single that emits a single item, a list composed of all the items emitted by the
     * finite source Publisher.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/toList.png" alt="">
     * <p>
     * Normally, a Publisher that returns multiple items will do so by invoking its {@link Subscriber}'s
     * {@link Subscriber#onNext onNext} method for each such item. You can change this behavior, instructing the
     * Publisher to compose a list of all of these items and then to invoke the Subscriber's {@code onNext}
     * function once, passing it the entire list, by calling the Publisher's {@code toList} method prior to
     * calling its {@link #subscribe} method.
     * <p>
     * Note that this operator requires the upstream to signal {@code onComplete} for the accumulated collection to
     * be emitted. Sources that are infinite and never complete will never emit anything through this
     * operator and an infinite source may lead to a fatal {@code OutOfMemoryError}.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toList} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the subclass of a collection of Ts
     * @param collectionSupplier
     *               the Callable returning the collection (for each individual Subscriber) to be filled in
     * @return a Single that emits a single item: a List containing all of the items emitted by the source
     *         Publisher
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX operators documentation: To</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U extends Collection<? super T>> Single<U> toList(Callable<U> collectionSupplier) {
        ObjectHelper.requireNonNull(collectionSupplier, "collectionSupplier is null");
        return RxJavaPlugins.onAssembly(new FlowableToListSingle<T, U>(this, collectionSupplier));
    }

    /**
     * Returns a Single that emits a single HashMap containing all items emitted by the source Publisher,
     * mapped by the keys returned by a specified {@code keySelector} function.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/toMap.png" alt="">
     * <p>
     * If more than one source item maps to the same key, the HashMap will contain the latest of those items.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <K> the key type of the Map
     * @param keySelector
     *            the function that extracts the key from a source item to be used in the HashMap
     * @return a Single that emits a single item: a HashMap containing the mapped items from the source
     *         Publisher
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX operators documentation: To</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <K> Single<Map<K, T>> toMap(final Function<? super T, ? extends K> keySelector) {
        ObjectHelper.requireNonNull(keySelector, "keySelector is null");
        return collect(HashMapSupplier.<K, T>asCallable(), Functions.toMapKeySelector(keySelector));
    }

    /**
     * Returns a Single that emits a single HashMap containing values corresponding to items emitted by the
     * source Publisher, mapped by the keys returned by a specified {@code keySelector} function.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/toMap.png" alt="">
     * <p>
     * If more than one source item maps to the same key, the HashMap will contain a single entry that
     * corresponds to the latest of those items.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <K> the key type of the Map
     * @param <V> the value type of the Map
     * @param keySelector
     *            the function that extracts the key from a source item to be used in the HashMap
     * @param valueSelector
     *            the function that extracts the value from a source item to be used in the HashMap
     * @return a Single that emits a single item: a HashMap containing the mapped items from the source
     *         Publisher
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX operators documentation: To</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <K, V> Single<Map<K, V>> toMap(final Function<? super T, ? extends K> keySelector, final Function<? super T, ? extends V> valueSelector) {
        ObjectHelper.requireNonNull(keySelector, "keySelector is null");
        ObjectHelper.requireNonNull(valueSelector, "valueSelector is null");
        return collect(HashMapSupplier.<K, V>asCallable(), Functions.toMapKeyValueSelector(keySelector, valueSelector));
    }

    /**
     * Returns a Single that emits a single Map, returned by a specified {@code mapFactory} function, that
     * contains keys and values extracted from the items emitted by the source Publisher.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/toMap.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toMap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <K> the key type of the Map
     * @param <V> the value type of the Map
     * @param keySelector
     *            the function that extracts the key from a source item to be used in the Map
     * @param valueSelector
     *            the function that extracts the value from the source items to be used as value in the Map
     * @param mapSupplier
     *            the function that returns a Map instance to be used
     * @return a Flowable that emits a single item: a Map that contains the mapped items emitted by the
     *         source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX operators documentation: To</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <K, V> Single<Map<K, V>> toMap(final Function<? super T, ? extends K> keySelector,
            final Function<? super T, ? extends V> valueSelector,
            final Callable<? extends Map<K, V>> mapSupplier) {
        ObjectHelper.requireNonNull(keySelector, "keySelector is null");
        ObjectHelper.requireNonNull(valueSelector, "valueSelector is null");
        return collect(mapSupplier, Functions.toMapKeyValueSelector(keySelector, valueSelector));
    }

    /**
     * Returns a Single that emits a single HashMap that contains an ArrayList of items emitted by the
     * source Publisher keyed by a specified {@code keySelector} function.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/toMultiMap.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator does not support backpressure as by intent it is requesting and buffering everything.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toMultimap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <K> the key type of the Map
     * @param keySelector
     *            the function that extracts the key from the source items to be used as key in the HashMap
     * @return a Single that emits a single item: a HashMap that contains an ArrayList of items mapped from
     *         the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX operators documentation: To</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <K> Single<Map<K, Collection<T>>> toMultimap(Function<? super T, ? extends K> keySelector) {
        Function<T, T> valueSelector = Functions.identity();
        Callable<Map<K, Collection<T>>> mapSupplier = HashMapSupplier.asCallable();
        Function<K, List<T>> collectionFactory = ArrayListSupplier.asFunction();
        return toMultimap(keySelector, valueSelector, mapSupplier, collectionFactory);
    }

    /**
     * Returns a Single that emits a single HashMap that contains an ArrayList of values extracted by a
     * specified {@code valueSelector} function from items emitted by the source Publisher, keyed by a
     * specified {@code keySelector} function.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/toMultiMap.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toMultimap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <K> the key type of the Map
     * @param <V> the value type of the Map
     * @param keySelector
     *            the function that extracts a key from the source items to be used as key in the HashMap
     * @param valueSelector
     *            the function that extracts a value from the source items to be used as value in the HashMap
     * @return a Single that emits a single item: a HashMap that contains an ArrayList of items mapped from
     *         the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX operators documentation: To</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <K, V> Single<Map<K, Collection<V>>> toMultimap(Function<? super T, ? extends K> keySelector, Function<? super T, ? extends V> valueSelector) {
        Callable<Map<K, Collection<V>>> mapSupplier = HashMapSupplier.asCallable();
        Function<K, List<V>> collectionFactory = ArrayListSupplier.asFunction();
        return toMultimap(keySelector, valueSelector, mapSupplier, collectionFactory);
    }

    /**
     * Returns a Single that emits a single Map, returned by a specified {@code mapFactory} function, that
     * contains a custom collection of values, extracted by a specified {@code valueSelector} function from
     * items emitted by the source Publisher, and keyed by the {@code keySelector} function.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/toMultiMap.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toMultimap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <K> the key type of the Map
     * @param <V> the value type of the Map
     * @param keySelector
     *            the function that extracts a key from the source items to be used as the key in the Map
     * @param valueSelector
     *            the function that extracts a value from the source items to be used as the value in the Map
     * @param mapSupplier
     *            the function that returns a Map instance to be used
     * @param collectionFactory
     *            the function that returns a Collection instance for a particular key to be used in the Map
     * @return a Single that emits a single item: a Map that contains the collection of mapped items from
     *         the source Publisher
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX operators documentation: To</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <K, V> Single<Map<K, Collection<V>>> toMultimap(
            final Function<? super T, ? extends K> keySelector,
            final Function<? super T, ? extends V> valueSelector,
            final Callable<? extends Map<K, Collection<V>>> mapSupplier,
            final Function<? super K, ? extends Collection<? super V>> collectionFactory) {
        ObjectHelper.requireNonNull(keySelector, "keySelector is null");
        ObjectHelper.requireNonNull(valueSelector, "valueSelector is null");
        ObjectHelper.requireNonNull(mapSupplier, "mapSupplier is null");
        ObjectHelper.requireNonNull(collectionFactory, "collectionFactory is null");
        return collect(mapSupplier, Functions.toMultimapKeyValueSelector(keySelector, valueSelector, collectionFactory));
    }

    /**
     * Returns a Single that emits a single Map, returned by a specified {@code mapFactory} function, that
     * contains an ArrayList of values, extracted by a specified {@code valueSelector} function from items
     * emitted by the source Publisher and keyed by the {@code keySelector} function.
     * <p>
     * <img width="640" height="305" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/toMultiMap.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toMultimap} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <K> the key type of the Map
     * @param <V> the value type of the Map
     * @param keySelector
     *            the function that extracts a key from the source items to be used as the key in the Map
     * @param valueSelector
     *            the function that extracts a value from the source items to be used as the value in the Map
     * @param mapSupplier
     *            the function that returns a Map instance to be used
     * @return a Single that emits a single item: a Map that contains a list items mapped from the source
     *         Publisher
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX operators documentation: To</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <K, V> Single<Map<K, Collection<V>>> toMultimap(
            Function<? super T, ? extends K> keySelector,
            Function<? super T, ? extends V> valueSelector,
            Callable<Map<K, Collection<V>>> mapSupplier
            ) {
        return toMultimap(keySelector, valueSelector, mapSupplier, ArrayListSupplier.<V, K>asFunction());
    }

    /**
     * Converts the current Flowable into a non-backpressured {@link Observable}.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>Observables don't support backpressure thus the current Flowable is consumed in an unbounded
     *  manner (by requesting Long.MAX_VALUE).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toObservable} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @return the new Observable instance
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.NONE)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Observable<T> toObservable() {
        return RxJavaPlugins.onAssembly(new ObservableFromPublisher<T>(this));
    }

    /**
     * Returns a Single that emits a list that contains the items emitted by the source Publisher, in a
     * sorted order. Each item emitted by the Publisher must implement {@link Comparable} with respect to all
     * other items in the sequence.
     *
     * <p>If any item emitted by this Flowable does not implement {@link Comparable} with respect to
     *             all other items emitted by this Flowable, no items will be emitted and the
     *             sequence is terminated with a {@link ClassCastException}.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/toSortedList.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toSortedList} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @return a Single that emits a list that contains the items emitted by the source Publisher in
     *         sorted order
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX operators documentation: To</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<List<T>> toSortedList() {
        return toSortedList(Functions.naturalComparator());
    }

    /**
     * Returns a Single that emits a list that contains the items emitted by the source Publisher, in a
     * sorted order based on a specified comparison function.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/toSortedList.f.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toSortedList} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param comparator
     *            a function that compares two items emitted by the source Publisher and returns an Integer
     *            that indicates their sort order
     * @return a Single that emits a list that contains the items emitted by the source Publisher in
     *         sorted order
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX operators documentation: To</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<List<T>> toSortedList(final Comparator<? super T> comparator) {
        ObjectHelper.requireNonNull(comparator, "comparator is null");
        return toList().map(Functions.listSorter(comparator));
    }

    /**
     * Returns a Single that emits a list that contains the items emitted by the source Publisher, in a
     * sorted order based on a specified comparison function.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/toSortedList.f.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toSortedList} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param comparator
     *            a function that compares two items emitted by the source Publisher and returns an Integer
     *            that indicates their sort order
     * @param capacityHint
     *             the initial capacity of the ArrayList used to accumulate items before sorting
     * @return a Single that emits a list that contains the items emitted by the source Publisher in
     *         sorted order
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX operators documentation: To</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<List<T>> toSortedList(final Comparator<? super T> comparator, int capacityHint) {
        ObjectHelper.requireNonNull(comparator, "comparator is null");
        return toList(capacityHint).map(Functions.listSorter(comparator));
    }

    /**
     * Returns a Flowable that emits a list that contains the items emitted by the source Publisher, in a
     * sorted order. Each item emitted by the Publisher must implement {@link Comparable} with respect to all
     * other items in the sequence.
     *
     * <p>If any item emitted by this Flowable does not implement {@link Comparable} with respect to
     *             all other items emitted by this Flowable, no items will be emitted and the
     *             sequence is terminated with a {@link ClassCastException}.
     * <p>
     * <img width="640" height="310" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/toSortedList.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure from downstream and consumes the source {@code Publisher} in an
     *  unbounded manner (i.e., without applying backpressure to it).</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code toSortedList} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param capacityHint
     *             the initial capacity of the ArrayList used to accumulate items before sorting
     * @return a Flowable that emits a list that contains the items emitted by the source Publisher in
     *         sorted order
     * @see <a href="http://reactivex.io/documentation/operators/to.html">ReactiveX operators documentation: To</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Single<List<T>> toSortedList(int capacityHint) {
        return toSortedList(Functions.naturalComparator(), capacityHint);
    }

    /**
     * Modifies the source Publisher so that subscribers will cancel it on a specified
     * {@link Scheduler}.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator doesn't interfere with backpressure which is determined by the source {@code Publisher}'s backpressure
     *  behavior.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param scheduler
     *            the {@link Scheduler} to perform cancellation actions on
     * @return the source Publisher modified so that its cancellations happen on the specified
     *         {@link Scheduler}
     * @see <a href="http://reactivex.io/documentation/operators/subscribeon.html">ReactiveX operators documentation: SubscribeOn</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<T> unsubscribeOn(Scheduler scheduler) {
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        return RxJavaPlugins.onAssembly(new FlowableUnsubscribeOn<T>(this, scheduler));
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping windows, each containing {@code count} items. When the source
     * Publisher completes or encounters an error, the resulting Publisher emits the current window and
     * propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="400" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window3.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure of its inner and outer subscribers, however, the inner Publisher uses an
     *  unbounded buffer that may hold at most {@code count} elements.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code window} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param count
     *            the maximum size of each window before it should be emitted
     * @return a Flowable that emits connected, non-overlapping windows, each containing at most
     *         {@code count} items from the source Publisher
     * @throws IllegalArgumentException if either count is non-positive
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<Flowable<T>> window(long count) {
        return window(count, count, bufferSize());
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher emits windows every {@code skip} items, each containing no more than {@code count} items. When
     * the source Publisher completes or encounters an error, the resulting Publisher emits the current window
     * and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="365" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window4.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure of its inner and outer subscribers, however, the inner Publisher uses an
     *  unbounded buffer that may hold at most {@code count} elements.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code window} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param count
     *            the maximum size of each window before it should be emitted
     * @param skip
     *            how many items need to be skipped before starting a new window. Note that if {@code skip} and
     *            {@code count} are equal this is the same operation as {@link #window(long)}.
     * @return a Flowable that emits windows every {@code skip} items containing at most {@code count} items
     *         from the source Publisher
     * @throws IllegalArgumentException if either count or skip is non-positive
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<Flowable<T>> window(long count, long skip) {
        return window(count, skip, bufferSize());
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher emits windows every {@code skip} items, each containing no more than {@code count} items. When
     * the source Publisher completes or encounters an error, the resulting Publisher emits the current window
     * and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="365" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window4.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator honors backpressure of its inner and outer subscribers, however, the inner Publisher uses an
     *  unbounded buffer that may hold at most {@code count} elements.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code window} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param count
     *            the maximum size of each window before it should be emitted
     * @param skip
     *            how many items need to be skipped before starting a new window. Note that if {@code skip} and
     *            {@code count} are equal this is the same operation as {@link #window(long)}.
     * @param bufferSize
     *            the capacity hint for the buffer in the inner windows
     * @return a Flowable that emits windows every {@code skip} items containing at most {@code count} items
     *         from the source Publisher
     * @throws IllegalArgumentException if either count or skip is non-positive
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final Flowable<Flowable<T>> window(long count, long skip, int bufferSize) {
        ObjectHelper.verifyPositive(skip, "skip");
        ObjectHelper.verifyPositive(count, "count");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return RxJavaPlugins.onAssembly(new FlowableWindow<T>(this, count, skip, bufferSize));
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher starts a new window periodically, as determined by the {@code timeskip} argument. It emits
     * each window after a fixed timespan, specified by the {@code timespan} argument. When the source
     * Publisher completes or Publisher completes or encounters an error, the resulting Publisher emits the
     * current window and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="335" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window7.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner.
     *  The returned {@code Publisher} doesn't support backpressure as it uses
     *  time to control the creation of windows. The returned inner {@code Publisher}s honor
     *  backpressure but have an unbounded inner buffer that <em>may</em> lead to {@code OutOfMemoryError}
     *  if left unconsumed.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code window} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each window collects items before it should be emitted
     * @param timeskip
     *            the period of time after which a new window will be created
     * @param unit
     *            the unit of time that applies to the {@code timespan} and {@code timeskip} arguments
     * @return a Flowable that emits new windows periodically as a fixed timespan elapses
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<Flowable<T>> window(long timespan, long timeskip, TimeUnit unit) {
        return window(timespan, timeskip, unit, Schedulers.computation(), bufferSize());
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher starts a new window periodically, as determined by the {@code timeskip} argument. It emits
     * each window after a fixed timespan, specified by the {@code timespan} argument. When the source
     * Publisher completes or Publisher completes or encounters an error, the resulting Publisher emits the
     * current window and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="335" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window7.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner.
     *  The returned {@code Publisher} doesn't support backpressure as it uses
     *  time to control the creation of windows. The returned inner {@code Publisher}s honor
     *  backpressure but have an unbounded inner buffer that <em>may</em> lead to {@code OutOfMemoryError}
     *  if left unconsumed.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each window collects items before it should be emitted
     * @param timeskip
     *            the period of time after which a new window will be created
     * @param unit
     *            the unit of time that applies to the {@code timespan} and {@code timeskip} arguments
     * @param scheduler
     *            the {@link Scheduler} to use when determining the end and start of a window
     * @return a Flowable that emits new windows periodically as a fixed timespan elapses
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<Flowable<T>> window(long timespan, long timeskip, TimeUnit unit, Scheduler scheduler) {
        return window(timespan, timeskip, unit, scheduler, bufferSize());
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher starts a new window periodically, as determined by the {@code timeskip} argument. It emits
     * each window after a fixed timespan, specified by the {@code timespan} argument. When the source
     * Publisher completes or Publisher completes or encounters an error, the resulting Publisher emits the
     * current window and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="335" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window7.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner.
     *  The returned {@code Publisher} doesn't support backpressure as it uses
     *  time to control the creation of windows. The returned inner {@code Publisher}s honor
     *  backpressure but have an unbounded inner buffer that <em>may</em> lead to {@code OutOfMemoryError}
     *  if left unconsumed.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each window collects items before it should be emitted
     * @param timeskip
     *            the period of time after which a new window will be created
     * @param unit
     *            the unit of time that applies to the {@code timespan} and {@code timeskip} arguments
     * @param scheduler
     *            the {@link Scheduler} to use when determining the end and start of a window
     * @param bufferSize
     *            the capacity hint for the buffer in the inner windows
     * @return a Flowable that emits new windows periodically as a fixed timespan elapses
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<Flowable<T>> window(long timespan, long timeskip, TimeUnit unit, Scheduler scheduler, int bufferSize) {
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        ObjectHelper.verifyPositive(timespan, "timespan");
        ObjectHelper.verifyPositive(timeskip, "timeskip");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        ObjectHelper.requireNonNull(unit, "unit is null");
        return RxJavaPlugins.onAssembly(new FlowableWindowTimed<T>(this, timespan, timeskip, unit, scheduler, Long.MAX_VALUE, bufferSize, false));
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping windows, each of a fixed duration specified by the
     * {@code timespan} argument. When the source Publisher completes or encounters an error, the resulting
     * Publisher emits the current window and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="375" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window5.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner.
     *  The returned {@code Publisher} doesn't support backpressure as it uses
     *  time to control the creation of windows. The returned inner {@code Publisher}s honor
     *  backpressure and may hold up to {@code count} elements at most.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code window} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each window collects items before it should be emitted and replaced with a
     *            new window
     * @param unit
     *            the unit of time that applies to the {@code timespan} argument
     * @return a Flowable that emits connected, non-overlapping windows representing items emitted by the
     *         source Publisher during fixed, consecutive durations
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<Flowable<T>> window(long timespan, TimeUnit unit) {
        return window(timespan, unit, Schedulers.computation(), Long.MAX_VALUE, false);
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping windows, each of a fixed duration as specified by the
     * {@code timespan} argument or a maximum size as specified by the {@code count} argument (whichever is
     * reached first). When the source Publisher completes or encounters an error, the resulting Publisher
     * emits the current window and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="370" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window6.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner.
     *  The returned {@code Publisher} doesn't support backpressure as it uses
     *  time to control the creation of windows. The returned inner {@code Publisher}s honor
     *  backpressure and may hold up to {@code count} elements at most.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code window} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each window collects items before it should be emitted and replaced with a
     *            new window
     * @param unit
     *            the unit of time that applies to the {@code timespan} argument
     * @param count
     *            the maximum size of each window before it should be emitted
     * @return a Flowable that emits connected, non-overlapping windows of items from the source Publisher
     *         that were emitted during a fixed duration of time or when the window has reached maximum capacity
     *         (whichever occurs first)
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<Flowable<T>> window(long timespan, TimeUnit unit,
            long count) {
        return window(timespan, unit, Schedulers.computation(), count, false);
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping windows, each of a fixed duration as specified by the
     * {@code timespan} argument or a maximum size as specified by the {@code count} argument (whichever is
     * reached first). When the source Publisher completes or encounters an error, the resulting Publisher
     * emits the current window and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="370" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window6.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner.
     *  The returned {@code Publisher} doesn't support backpressure as it uses
     *  time to control the creation of windows. The returned inner {@code Publisher}s honor
     *  backpressure and may hold up to {@code count} elements at most.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code window} operates by default on the {@code computation} {@link Scheduler}.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each window collects items before it should be emitted and replaced with a
     *            new window
     * @param unit
     *            the unit of time that applies to the {@code timespan} argument
     * @param count
     *            the maximum size of each window before it should be emitted
     * @param restart
     *            if true, when a window reaches the capacity limit, the timer is restarted as well
     * @return a Flowable that emits connected, non-overlapping windows of items from the source Publisher
     *         that were emitted during a fixed duration of time or when the window has reached maximum capacity
     *         (whichever occurs first)
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.COMPUTATION)
    public final Flowable<Flowable<T>> window(long timespan, TimeUnit unit,
            long count, boolean restart) {
        return window(timespan, unit, Schedulers.computation(), count, restart);
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping windows, each of a fixed duration as specified by the
     * {@code timespan} argument. When the source Publisher completes or encounters an error, the resulting
     * Publisher emits the current window and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="375" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window5.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner.
     *  The returned {@code Publisher} doesn't support backpressure as it uses
     *  time to control the creation of windows. The returned inner {@code Publisher}s honor
     *  backpressure but have an unbounded inner buffer that <em>may</em> lead to {@code OutOfMemoryError}
     *  if left unconsumed.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each window collects items before it should be emitted and replaced with a
     *            new window
     * @param unit
     *            the unit of time which applies to the {@code timespan} argument
     * @param scheduler
     *            the {@link Scheduler} to use when determining the end and start of a window
     * @return a Flowable that emits connected, non-overlapping windows containing items emitted by the
     *         source Publisher within a fixed duration
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<Flowable<T>> window(long timespan, TimeUnit unit,
            Scheduler scheduler) {
        return window(timespan, unit, scheduler, Long.MAX_VALUE, false);
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping windows, each of a fixed duration specified by the
     * {@code timespan} argument or a maximum size specified by the {@code count} argument (whichever is reached
     * first). When the source Publisher completes or encounters an error, the resulting Publisher emits the
     * current window and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="370" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window6.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner.
     *  The returned {@code Publisher} doesn't support backpressure as it uses
     *  time to control the creation of windows. The returned inner {@code Publisher}s honor
     *  backpressure and may hold up to {@code count} elements at most.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each window collects items before it should be emitted and replaced with a
     *            new window
     * @param unit
     *            the unit of time which applies to the {@code timespan} argument
     * @param count
     *            the maximum size of each window before it should be emitted
     * @param scheduler
     *            the {@link Scheduler} to use when determining the end and start of a window
     * @return a Flowable that emits connected, non-overlapping windows of items from the source Publisher
     *         that were emitted during a fixed duration of time or when the window has reached maximum capacity
     *         (whichever occurs first)
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<Flowable<T>> window(long timespan, TimeUnit unit,
            Scheduler scheduler, long count) {
        return window(timespan, unit, scheduler, count, false);
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping windows, each of a fixed duration specified by the
     * {@code timespan} argument or a maximum size specified by the {@code count} argument (whichever is reached
     * first). When the source Publisher completes or encounters an error, the resulting Publisher emits the
     * current window and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="370" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window6.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner.
     *  The returned {@code Publisher} doesn't support backpressure as it uses
     *  time to control the creation of windows. The returned inner {@code Publisher}s honor
     *  backpressure and may hold up to {@code count} elements at most.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each window collects items before it should be emitted and replaced with a
     *            new window
     * @param unit
     *            the unit of time which applies to the {@code timespan} argument
     * @param count
     *            the maximum size of each window before it should be emitted
     * @param scheduler
     *            the {@link Scheduler} to use when determining the end and start of a window
     * @param restart
     *            if true, when a window reaches the capacity limit, the timer is restarted as well
     * @return a Flowable that emits connected, non-overlapping windows of items from the source Publisher
     *         that were emitted during a fixed duration of time or when the window has reached maximum capacity
     *         (whichever occurs first)
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<Flowable<T>> window(long timespan, TimeUnit unit,
            Scheduler scheduler, long count, boolean restart) {
        return window(timespan, unit, scheduler, count, restart, bufferSize());
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping windows, each of a fixed duration specified by the
     * {@code timespan} argument or a maximum size specified by the {@code count} argument (whichever is reached
     * first). When the source Publisher completes or encounters an error, the resulting Publisher emits the
     * current window and propagates the notification from the source Publisher.
     * <p>
     * <img width="640" height="370" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window6.s.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner.
     *  The returned {@code Publisher} doesn't support backpressure as it uses
     *  time to control the creation of windows. The returned inner {@code Publisher}s honor
     *  backpressure and may hold up to {@code count} elements at most.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>You specify which {@link Scheduler} this operator will use.</dd>
     * </dl>
     *
     * @param timespan
     *            the period of time each window collects items before it should be emitted and replaced with a
     *            new window
     * @param unit
     *            the unit of time which applies to the {@code timespan} argument
     * @param count
     *            the maximum size of each window before it should be emitted
     * @param scheduler
     *            the {@link Scheduler} to use when determining the end and start of a window
     * @param restart
     *            if true, when a window reaches the capacity limit, the timer is restarted as well
     * @param bufferSize
     *            the capacity hint for the buffer in the inner windows
     * @return a Flowable that emits connected, non-overlapping windows of items from the source Publisher
     *         that were emitted during a fixed duration of time or when the window has reached maximum capacity
     *         (whichever occurs first)
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.CUSTOM)
    public final Flowable<Flowable<T>> window(
            long timespan, TimeUnit unit, Scheduler scheduler,
            long count, boolean restart, int bufferSize) {
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        ObjectHelper.requireNonNull(scheduler, "scheduler is null");
        ObjectHelper.requireNonNull(unit, "unit is null");
        ObjectHelper.verifyPositive(count, "count");
        return RxJavaPlugins.onAssembly(new FlowableWindowTimed<T>(this, timespan, timespan, unit, scheduler, count, bufferSize, restart));
    }

    /**
     * Returns a Flowable that emits non-overlapping windows of items it collects from the source Publisher
     * where the boundary of each window is determined by the items emitted from a specified boundary-governing
     * Publisher.
     * <p>
     * <img width="640" height="475" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window8.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The outer Publisher of this operator does not support backpressure as it uses a {@code boundary} Publisher to control data
     *      flow. The inner Publishers honor backpressure and buffer everything until the boundary signals the next element.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code window} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <B>
     *            the window element type (ignored)
     * @param boundaryIndicator
     *            a Publisher whose emitted items close and open windows
     * @return a Flowable that emits non-overlapping windows of items it collects from the source Publisher
     *         where the boundary of each window is determined by the items emitted from the {@code boundary}
     *         Publisher
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <B> Flowable<Flowable<T>> window(Publisher<B> boundaryIndicator) {
        return window(boundaryIndicator, bufferSize());
    }

    /**
     * Returns a Flowable that emits non-overlapping windows of items it collects from the source Publisher
     * where the boundary of each window is determined by the items emitted from a specified boundary-governing
     * Publisher.
     * <p>
     * <img width="640" height="475" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window8.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The outer Publisher of this operator does not support backpressure as it uses a {@code boundary} Publisher to control data
     *      flow. The inner Publishers honor backpressure and buffer everything until the boundary signals the next element.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code window} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <B>
     *            the window element type (ignored)
     * @param boundaryIndicator
     *            a Publisher whose emitted items close and open windows
     * @param bufferSize
     *            the capacity hint for the buffer in the inner windows
     * @return a Flowable that emits non-overlapping windows of items it collects from the source Publisher
     *         where the boundary of each window is determined by the items emitted from the {@code boundary}
     *         Publisher
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <B> Flowable<Flowable<T>> window(Publisher<B> boundaryIndicator, int bufferSize) {
        ObjectHelper.requireNonNull(boundaryIndicator, "boundaryIndicator is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return RxJavaPlugins.onAssembly(new FlowableWindowBoundary<T, B>(this, boundaryIndicator, bufferSize));
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher emits windows that contain those items emitted by the source Publisher between the time when
     * the {@code windowOpenings} Publisher emits an item and when the Publisher returned by
     * {@code closingSelector} emits an item.
     * <p>
     * <img width="640" height="550" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window2.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The outer Publisher of this operator doesn't support backpressure because the emission of new
     *  inner Publishers are controlled by the {@code windowOpenings} Publisher.
     *  The inner Publishers honor backpressure and buffer everything until the associated closing
     *  Publisher signals or completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code window} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the element type of the window-opening Publisher
     * @param <V> the element type of the window-closing Publishers
     * @param openingIndicator
     *            a Publisher that, when it emits an item, causes another window to be created
     * @param closingIndicator
     *            a {@link Function} that produces a Publisher for every window created. When this Publisher
     *            emits an item, the associated window is closed and emitted
     * @return a Flowable that emits windows of items emitted by the source Publisher that are governed by
     *         the specified window-governing Publishers
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, V> Flowable<Flowable<T>> window(
            Publisher<U> openingIndicator,
            Function<? super U, ? extends Publisher<V>> closingIndicator) {
        return window(openingIndicator, closingIndicator, bufferSize());
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher emits windows that contain those items emitted by the source Publisher between the time when
     * the {@code windowOpenings} Publisher emits an item and when the Publisher returned by
     * {@code closingSelector} emits an item.
     * <p>
     * <img width="640" height="550" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window2.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The outer Publisher of this operator doesn't support backpressure because the emission of new
     *  inner Publishers are controlled by the {@code windowOpenings} Publisher.
     *  The inner Publishers honor backpressure and buffer everything until the associated closing
     *  Publisher signals or completes.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code window} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the element type of the window-opening Publisher
     * @param <V> the element type of the window-closing Publishers
     * @param openingIndicator
     *            a Publisher that, when it emits an item, causes another window to be created
     * @param closingIndicator
     *            a {@link Function} that produces a Publisher for every window created. When this Publisher
     *            emits an item, the associated window is closed and emitted
     * @param bufferSize
     *            the capacity hint for the buffer in the inner windows
     * @return a Flowable that emits windows of items emitted by the source Publisher that are governed by
     *         the specified window-governing Publishers
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, V> Flowable<Flowable<T>> window(
            Publisher<U> openingIndicator,
            Function<? super U, ? extends Publisher<V>> closingIndicator, int bufferSize) {
        ObjectHelper.requireNonNull(openingIndicator, "openingIndicator is null");
        ObjectHelper.requireNonNull(closingIndicator, "closingIndicator is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return RxJavaPlugins.onAssembly(new FlowableWindowBoundarySelector<T, U, V>(this, openingIndicator, closingIndicator, bufferSize));
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping windows. It emits the current window and opens a new one
     * whenever the Publisher produced by the specified {@code closingSelector} emits an item.
     * <p>
     * <img width="640" height="455" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window1.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner.
     *  The returned {@code Publisher} doesn't support backpressure as it uses
     *  the {@code closingSelector} to control the creation of windows. The returned inner {@code Publisher}s honor
     *  backpressure but have an unbounded inner buffer that <em>may</em> lead to {@code OutOfMemoryError}
     *  if left unconsumed.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code window} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <B> the element type of the boundary Publisher
     * @param boundaryIndicatorSupplier
     *            a {@link Callable} that returns a {@code Publisher} that governs the boundary between windows.
     *            When the source {@code Publisher} emits an item, {@code window} emits the current window and begins
     *            a new one.
     * @return a Flowable that emits connected, non-overlapping windows of items from the source Publisher
     *         whenever {@code closingSelector} emits an item
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <B> Flowable<Flowable<T>> window(Callable<? extends Publisher<B>> boundaryIndicatorSupplier) {
        return window(boundaryIndicatorSupplier, bufferSize());
    }

    /**
     * Returns a Flowable that emits windows of items it collects from the source Publisher. The resulting
     * Publisher emits connected, non-overlapping windows. It emits the current window and opens a new one
     * whenever the Publisher produced by the specified {@code closingSelector} emits an item.
     * <p>
     * <img width="640" height="455" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/window1.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator consumes the source {@code Publisher} in an unbounded manner.
     *  The returned {@code Publisher} doesn't support backpressure as it uses
     *  the {@code closingSelector} to control the creation of windows. The returned inner {@code Publisher}s honor
     *  backpressure but have an unbounded inner buffer that <em>may</em> lead to {@code OutOfMemoryError}
     *  if left unconsumed.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This version of {@code window} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <B> the element type of the boundary Publisher
     * @param boundaryIndicatorSupplier
     *            a {@link Callable} that returns a {@code Publisher} that governs the boundary between windows.
     *            When the source {@code Publisher} emits an item, {@code window} emits the current window and begins
     *            a new one.
     * @param bufferSize
     *            the capacity hint for the buffer in the inner windows
     * @return a Flowable that emits connected, non-overlapping windows of items from the source Publisher
     *         whenever {@code closingSelector} emits an item
     * @see <a href="http://reactivex.io/documentation/operators/window.html">ReactiveX operators documentation: Window</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.ERROR)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <B> Flowable<Flowable<T>> window(Callable<? extends Publisher<B>> boundaryIndicatorSupplier, int bufferSize) {
        ObjectHelper.requireNonNull(boundaryIndicatorSupplier, "boundaryIndicatorSupplier is null");
        ObjectHelper.verifyPositive(bufferSize, "bufferSize");
        return RxJavaPlugins.onAssembly(new FlowableWindowBoundarySupplier<T, B>(this, boundaryIndicatorSupplier, bufferSize));
    }

    /**
     * Merges the specified Publisher into this Publisher sequence by using the {@code resultSelector}
     * function only when the source Publisher (this instance) emits an item.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/withLatestFrom.png" alt="">
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator is a pass-through for backpressure: the backpressure support
     *  depends on the upstream and downstream's backpressure behavior. The other Publisher
     *  is consumed in an unbounded fashion.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This operator, by default, doesn't run any particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U> the element type of the other Publisher
     * @param <R> the result type of the combination
     * @param other
     *            the other Publisher
     * @param combiner
     *            the function to call when this Publisher emits an item and the other Publisher has already
     *            emitted an item, to generate the item to be emitted by the resulting Publisher
     * @return a Flowable that merges the specified Publisher into this Publisher by using the
     *         {@code resultSelector} function only when the source Publisher sequence (this instance) emits an
     *         item
     * @since 2.0
     * @see <a href="http://reactivex.io/documentation/operators/combinelatest.html">ReactiveX operators documentation: CombineLatest</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, R> Flowable<R> withLatestFrom(Publisher<? extends U> other,
            BiFunction<? super T, ? super U, ? extends R> combiner) {
        ObjectHelper.requireNonNull(other, "other is null");
        ObjectHelper.requireNonNull(combiner, "combiner is null");

        return RxJavaPlugins.onAssembly(new FlowableWithLatestFrom<T, U, R>(this, combiner, other));
    }

    /**
     * Combines the value emission from this Publisher with the latest emissions from the
     * other Publishers via a function to produce the output item.
     *
     * <p>Note that this operator doesn't emit anything until all other sources have produced at
     * least one value. The resulting emission only happens when this Publisher emits (and
     * not when any of the other sources emit, unlike combineLatest).
     * If a source doesn't produce any value and just completes, the sequence is completed immediately.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator is a pass-through for backpressure behavior between the source {@code Publisher}
     *  and the downstream Subscriber. The other {@code Publisher}s are consumed in an unbounded manner.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This operator does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the first other source's value type
     * @param <T2> the second other source's value type
     * @param <R> the result value type
     * @param source1 the first other Publisher
     * @param source2 the second other Publisher
     * @param combiner the function called with an array of values from each participating Publisher
     * @return the new Publisher instance
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <T1, T2, R> Flowable<R> withLatestFrom(Publisher<T1> source1, Publisher<T2> source2,
            Function3<? super T, ? super T1, ? super T2, R> combiner) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        Function<Object[], R> f = Functions.toFunction(combiner);
        return withLatestFrom(new Publisher[] { source1, source2 }, f);
    }

    /**
     * Combines the value emission from this Publisher with the latest emissions from the
     * other Publishers via a function to produce the output item.
     *
     * <p>Note that this operator doesn't emit anything until all other sources have produced at
     * least one value. The resulting emission only happens when this Publisher emits (and
     * not when any of the other sources emit, unlike combineLatest).
     * If a source doesn't produce any value and just completes, the sequence is completed immediately.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator is a pass-through for backpressure behavior between the source {@code Publisher}
     *  and the downstream Subscriber. The other {@code Publisher}s are consumed in an unbounded manner.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This operator does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the first other source's value type
     * @param <T2> the second other source's value type
     * @param <T3> the third other source's value type
     * @param <R> the result value type
     * @param source1 the first other Publisher
     * @param source2 the second other Publisher
     * @param source3 the third other Publisher
     * @param combiner the function called with an array of values from each participating Publisher
     * @return the new Publisher instance
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <T1, T2, T3, R> Flowable<R> withLatestFrom(
            Publisher<T1> source1, Publisher<T2> source2,
            Publisher<T3> source3,
            Function4<? super T, ? super T1, ? super T2, ? super T3, R> combiner) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        Function<Object[], R> f = Functions.toFunction(combiner);
        return withLatestFrom(new Publisher[] { source1, source2, source3 }, f);
    }

    /**
     * Combines the value emission from this Publisher with the latest emissions from the
     * other Publishers via a function to produce the output item.
     *
     * <p>Note that this operator doesn't emit anything until all other sources have produced at
     * least one value. The resulting emission only happens when this Publisher emits (and
     * not when any of the other sources emit, unlike combineLatest).
     * If a source doesn't produce any value and just completes, the sequence is completed immediately.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator is a pass-through for backpressure behavior between the source {@code Publisher}
     *  and the downstream Subscriber. The other {@code Publisher}s are consumed in an unbounded manner.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This operator does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <T1> the first other source's value type
     * @param <T2> the second other source's value type
     * @param <T3> the third other source's value type
     * @param <T4> the fourth other source's value type
     * @param <R> the result value type
     * @param source1 the first other Publisher
     * @param source2 the second other Publisher
     * @param source3 the third other Publisher
     * @param source4 the fourth other Publisher
     * @param combiner the function called with an array of values from each participating Publisher
     * @return the new Publisher instance
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <T1, T2, T3, T4, R> Flowable<R> withLatestFrom(
            Publisher<T1> source1, Publisher<T2> source2,
            Publisher<T3> source3, Publisher<T4> source4,
            Function5<? super T, ? super T1, ? super T2, ? super T3, ? super T4, R> combiner) {
        ObjectHelper.requireNonNull(source1, "source1 is null");
        ObjectHelper.requireNonNull(source2, "source2 is null");
        ObjectHelper.requireNonNull(source3, "source3 is null");
        ObjectHelper.requireNonNull(source4, "source4 is null");
        Function<Object[], R> f = Functions.toFunction(combiner);
        return withLatestFrom(new Publisher[] { source1, source2, source3, source4 }, f);
    }

    /**
     * Combines the value emission from this Publisher with the latest emissions from the
     * other Publishers via a function to produce the output item.
     *
     * <p>Note that this operator doesn't emit anything until all other sources have produced at
     * least one value. The resulting emission only happens when this Publisher emits (and
     * not when any of the other sources emit, unlike combineLatest).
     * If a source doesn't produce any value and just completes, the sequence is completed immediately.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator is a pass-through for backpressure behavior between the source {@code Publisher}
     *  and the downstream Subscriber. The other {@code Publisher}s are consumed in an unbounded manner.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This operator does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the result value type
     * @param others the array of other sources
     * @param combiner the function called with an array of values from each participating Publisher
     * @return the new Publisher instance
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> withLatestFrom(Publisher<?>[] others, Function<? super Object[], R> combiner) {
        ObjectHelper.requireNonNull(others, "others is null");
        ObjectHelper.requireNonNull(combiner, "combiner is null");
        return RxJavaPlugins.onAssembly(new FlowableWithLatestFromMany<T, R>(this, others, combiner));
    }

    /**
     * Combines the value emission from this Publisher with the latest emissions from the
     * other Publishers via a function to produce the output item.
     *
     * <p>Note that this operator doesn't emit anything until all other sources have produced at
     * least one value. The resulting emission only happens when this Publisher emits (and
     * not when any of the other sources emit, unlike combineLatest).
     * If a source doesn't produce any value and just completes, the sequence is completed immediately.
     *
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>This operator is a pass-through for backpressure behavior between the source {@code Publisher}
     *  and the downstream Subscriber. The other {@code Publisher}s are consumed in an unbounded manner.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>This operator does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <R> the result value type
     * @param others the iterable of other sources
     * @param combiner the function called with an array of values from each participating Publisher
     * @return the new Publisher instance
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.PASS_THROUGH)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <R> Flowable<R> withLatestFrom(Iterable<? extends Publisher<?>> others, Function<? super Object[], R> combiner) {
        ObjectHelper.requireNonNull(others, "others is null");
        ObjectHelper.requireNonNull(combiner, "combiner is null");
        return RxJavaPlugins.onAssembly(new FlowableWithLatestFromMany<T, R>(this, others, combiner));
    }

    /**
     * Returns a Flowable that emits items that are the result of applying a specified function to pairs of
     * values, one each from the source Publisher and a specified Iterable sequence.
     * <p>
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.i.png" alt="">
     * <p>
     * Note that the {@code other} Iterable is evaluated as items are observed from the source Publisher; it is
     * not pre-consumed. This allows you to zip infinite streams on either side.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zipWith} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the type of items in the {@code other} Iterable
     * @param <R>
     *            the type of items emitted by the resulting Publisher
     * @param other
     *            the Iterable sequence
     * @param zipper
     *            a function that combines the pairs of items from the Publisher and the Iterable to generate
     *            the items to be emitted by the resulting Publisher
     * @return a Flowable that pairs up values from the source Publisher and the {@code other} Iterable
     *         sequence and emits the results of {@code zipFunction} applied to these pairs
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, R> Flowable<R> zipWith(Iterable<U> other,  BiFunction<? super T, ? super U, ? extends R> zipper) {
        ObjectHelper.requireNonNull(other, "other is null");
        ObjectHelper.requireNonNull(zipper, "zipper is null");
        return RxJavaPlugins.onAssembly(new FlowableZipIterable<T, U, R>(this, other, zipper));
    }

    /**
     * Returns a Flowable that emits items that are the result of applying a specified function to pairs of
     * values, one each from the source Publisher and another specified Publisher.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>range(1, 5).doOnComplete(action1).zipWith(range(6, 5).doOnComplete(action2), (a, b) -&gt; a + b)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     *
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zipWith} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the type of items emitted by the {@code other} Publisher
     * @param <R>
     *            the type of items emitted by the resulting Publisher
     * @param other
     *            the other Publisher
     * @param zipper
     *            a function that combines the pairs of items from the two Publishers to generate the items to
     *            be emitted by the resulting Publisher
     * @return a Flowable that pairs up values from the source Publisher and the {@code other} Publisher
     *         and emits the results of {@code zipFunction} applied to these pairs
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, R> Flowable<R> zipWith(Publisher<? extends U> other, BiFunction<? super T, ? super U, ? extends R> zipper) {
        ObjectHelper.requireNonNull(other, "other is null");
        return zip(this, other, zipper);
    }

    /**
     * Returns a Flowable that emits items that are the result of applying a specified function to pairs of
     * values, one each from the source Publisher and another specified Publisher.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>range(1, 5).doOnComplete(action1).zipWith(range(6, 5).doOnComplete(action2), (a, b) -&gt; a + b)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     *
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zipWith} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the type of items emitted by the {@code other} Publisher
     * @param <R>
     *            the type of items emitted by the resulting Publisher
     * @param other
     *            the other Publisher
     * @param zipper
     *            a function that combines the pairs of items from the two Publishers to generate the items to
     *            be emitted by the resulting Publisher
     * @param delayError
     *            if true, errors from the current Flowable or the other Publisher is delayed until both terminate
     * @return a Flowable that pairs up values from the source Publisher and the {@code other} Publisher
     *         and emits the results of {@code zipFunction} applied to these pairs
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, R> Flowable<R> zipWith(Publisher<? extends U> other,
            BiFunction<? super T, ? super U, ? extends R> zipper, boolean delayError) {
        return zip(this, other, zipper, delayError);
    }

    /**
     * Returns a Flowable that emits items that are the result of applying a specified function to pairs of
     * values, one each from the source Publisher and another specified Publisher.
     * <p>
     * The operator subscribes to its sources in order they are specified and completes eagerly if
     * one of the sources is shorter than the rest while cancelling the other sources. Therefore, it
     * is possible those other sources will never be able to run to completion (and thus not calling
     * {@code doOnComplete()}). This can also happen if the sources are exactly the same length; if
     * source A completes and B has been consumed and is about to complete, the operator detects A won't
     * be sending further values and it will cancel B immediately. For example:
     * <pre><code>range(1, 5).doOnComplete(action1).zipWith(range(6, 5).doOnComplete(action2), (a, b) -&gt; a + b)</code></pre>
     * {@code action1} will be called but {@code action2} won't.
     * <br>To work around this termination property,
     * use {@link #doOnCancel(Action)} as well or use {@code using()} to do cleanup in case of completion
     * or cancellation.
     *
     * <img width="640" height="380" src="https://raw.github.com/wiki/ReactiveX/RxJava/images/rx-operators/zip.png" alt="">
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The operator expects backpressure from the sources and honors backpressure from the downstream.
     *  (I.e., zipping with {@link #interval(long, TimeUnit)} may result in MissingBackpressureException, use
     *  one of the {@code onBackpressureX} to handle similar, backpressure-ignoring sources.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code zipWith} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     *
     * @param <U>
     *            the type of items emitted by the {@code other} Publisher
     * @param <R>
     *            the type of items emitted by the resulting Publisher
     * @param other
     *            the other Publisher
     * @param zipper
     *            a function that combines the pairs of items from the two Publishers to generate the items to
     *            be emitted by the resulting Publisher
     * @param bufferSize
     *            the capacity hint for the buffer in the inner windows
     * @param delayError
     *            if true, errors from the current Flowable or the other Publisher is delayed until both terminate
     * @return a Flowable that pairs up values from the source Publisher and the {@code other} Publisher
     *         and emits the results of {@code zipFunction} applied to these pairs
     * @see <a href="http://reactivex.io/documentation/operators/zip.html">ReactiveX operators documentation: Zip</a>
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final <U, R> Flowable<R> zipWith(Publisher<? extends U> other,
            BiFunction<? super T, ? super U, ? extends R> zipper, boolean delayError, int bufferSize) {
        return zip(this, other, zipper, delayError, bufferSize);
    }

    // -------------------------------------------------------------------------
    // Fluent test support, super handy and reduces test preparation boilerplate
    // -------------------------------------------------------------------------
    /**
     * Creates a TestSubscriber that requests Long.MAX_VALUE and subscribes
     * it to this Flowable.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned TestSubscriber consumes this Flowable in an unbounded fashion.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code test} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @return the new TestSubscriber instance
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.UNBOUNDED_IN)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final TestSubscriber<T> test() { // NoPMD
        TestSubscriber<T> ts = new TestSubscriber<T>();
        subscribe(ts);
        return ts;
    }

    /**
     * Creates a TestSubscriber with the given initial request amount and subscribes
     * it to this Flowable.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned TestSubscriber requests the given {@code initialRequest} amount upfront.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code test} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param initialRequest the initial request amount, positive
     * @return the new TestSubscriber instance
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final TestSubscriber<T> test(long initialRequest) { // NoPMD
        TestSubscriber<T> ts = new TestSubscriber<T>(initialRequest);
        subscribe(ts);
        return ts;
    }

    /**
     * Creates a TestSubscriber with the given initial request amount,
     * optionally cancels it before the subscription and subscribes
     * it to this Flowable.
     * <dl>
     *  <dt><b>Backpressure:</b></dt>
     *  <dd>The returned TestSubscriber requests the given {@code initialRequest} amount upfront.</dd>
     *  <dt><b>Scheduler:</b></dt>
     *  <dd>{@code test} does not operate by default on a particular {@link Scheduler}.</dd>
     * </dl>
     * @param initialRequest the initial request amount, positive
     * @param cancel should the TestSubscriber be cancelled before the subscription?
     * @return the new TestSubscriber instance
     * @since 2.0
     */
    @CheckReturnValue
    @BackpressureSupport(BackpressureKind.FULL)
    @SchedulerSupport(SchedulerSupport.NONE)
    public final TestSubscriber<T> test(long initialRequest, boolean cancel) { // NoPMD
        TestSubscriber<T> ts = new TestSubscriber<T>(initialRequest);
        if (cancel) {
            ts.cancel();
        }
        subscribe(ts);
        return ts;
    }

}
