/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.dispatch;

import org.gradle.internal.UncheckedException;
import org.gradle.internal.concurrent.AsyncStoppable;
import org.gradle.internal.operations.CurrentBuildOperationPreservingRunnable;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <p>A {@link Dispatch} implementation which delivers messages asynchronously. Calls to
 * {@link #dispatch} queue the message. Worker threads deliver the messages in the order they have been received to one
 * of a pool of delegate {@link Dispatch} instances.</p>
 */
public class AsyncDispatch<T> implements Dispatch<T>, AsyncStoppable {
    private enum State {
        Init, Stopped
    }

    private static final int MAX_QUEUE_SIZE = 200;
    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private final LinkedList<T> queue = new LinkedList<T>();
    private final Executor executor;
    private final int maxQueueSize;
    private final List<Dispatcher> dispatchers = new ArrayList<Dispatcher>();
    private State state;

    public AsyncDispatch(Executor executor) {
        this(executor, null, MAX_QUEUE_SIZE);
    }

    public AsyncDispatch(Executor executor, final Dispatch<? super T> dispatch, int maxQueueSize) {
        this.executor = executor;
        this.maxQueueSize = maxQueueSize;
        state = State.Init;
        if (dispatch != null) {
            dispatchTo(dispatch);
        }
    }

    /**
     * Starts dispatching messages to the given handler. The handler does not need to be thread-safe.
     */
    public void dispatchTo(final Dispatch<? super T> dispatch) {
        Dispatcher dispatcher = new Dispatcher(dispatch);
        onDispatchThreadStart(dispatcher);
        executor.execute(new CurrentBuildOperationPreservingRunnable(dispatcher));
    }

    private void onDispatchThreadStart(Dispatcher dispatcher) {
        lock.lock();
        try {
            if (state != State.Init) {
                throw new IllegalStateException("This dispatch has been stopped.");
            }
            dispatchers.add(dispatcher);
        } finally {
            lock.unlock();
        }
    }

    private void onDispatchThreadExit(Dispatcher dispatcher) {
        lock.lock();
        try {
            dispatchers.remove(dispatcher);
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void setState(State state) {
        this.state = state;
        condition.signalAll();
    }

    public void dispatch(final T message) {
        lock.lock();
        try {
            boolean interrupted = false;
            while (state != State.Stopped && queue.size() >= maxQueueSize) {
                try {
                    condition.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (state == State.Stopped) {
                throw new IllegalStateException("Cannot dispatch message, as this message dispatch has been stopped. Message: " + message);
            }
            queue.add(message);
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Commences a shutdown of this dispatch.
     */
    public void requestStop() {
        lock.lock();
        try {
            doRequestStop();
        } finally {
            lock.unlock();
        }
    }

    private void doRequestStop() {
        setState(State.Stopped);
    }

    /**
     * Stops accepting new messages, and blocks until all queued messages have been dispatched.
     */
    public void stop() {
        lock.lock();
        try {
            setState(State.Stopped);
            waitForAllMessages();
        } finally {
            lock.unlock();
        }
    }

    private void waitForAllMessages() {
        boolean interrupted = false;
        while (!dispatchers.isEmpty()) {
            try {
                condition.await();
            } catch (InterruptedException e) {
                interrupted = true;
                for (Dispatcher dispatcher : dispatchers) {
                    dispatcher.interrupt();
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            throw UncheckedException.throwAsUncheckedException(new InterruptedException());
        }
        if (!queue.isEmpty()) {
            throw new IllegalStateException(
                "Cannot wait for messages to be dispatched, as there are no dispatch threads running.");
        }
    }

    private class Dispatcher implements Runnable {
        private final Dispatch<? super T> dispatch;
        private Thread thread;

        private Dispatcher(Dispatch<? super T> dispatch) {
            this.dispatch = dispatch;
        }

        public void run() {
            thread = Thread.currentThread();
            try {
                dispatchMessages(dispatch);
            } finally {
                onDispatchThreadExit(this);
            }
        }

        public void interrupt() {
            thread.interrupt();
        }

        private void dispatchMessages(Dispatch<? super T> dispatch) {
            while (true) {
                T message = waitForNextMessage();
                if (message == null) {
                    return;
                }
                dispatch.dispatch(message);
            }
        }

        private T waitForNextMessage() {
            lock.lock();
            try {
                boolean interrupted = false;
                while (state != State.Stopped && queue.isEmpty()) {
                    try {
                        condition.await();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                if (!queue.isEmpty()) {
                    T message = queue.remove();
                    condition.signalAll();
                    return message;
                }
            } finally {
                lock.unlock();
            }
            return null;
        }
    }
}
