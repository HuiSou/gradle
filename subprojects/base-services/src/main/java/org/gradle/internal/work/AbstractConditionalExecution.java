/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.internal.work;

import org.gradle.internal.UncheckedException;
import org.gradle.internal.resources.ResourceLock;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class AbstractConditionalExecution<T> implements ConditionalExecution<T> {
    private final CountDownLatch finished = new CountDownLatch(1);
    private final InterruptibleRunnable runnable;
    private final ResourceLock resourceLock;

    private T result;
    private Throwable failure;

    public AbstractConditionalExecution(final Callable<T> callable, ResourceLock resourceLock) {
        this.runnable = new InterruptibleRunnable(callable);
        this.resourceLock = resourceLock;
    }

    @Override
    public ResourceLock getResourceLock() {
        return resourceLock;
    }

    @Override
    public Runnable getExecution() {
        return runnable;
    }

    @Override
    public T await() {
        boolean interrupted = false;
        while(true) {
            try {
                finished.await();
                break;
            } catch(InterruptedException e) {
                runnable.interrupt();
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw UncheckedException.throwAsUncheckedException(failure);
        } else {
            return result;
        }
    }

    @Override
    public void complete() {
        finished.countDown();
    }

    @Override
    public boolean isComplete() {
        return finished.getCount() == 0;
    }

    @Override
    public void registerFailure(Throwable t) {
        this.failure = t;
    }

    private class InterruptibleRunnable implements Runnable {
        private final Callable<T> callable;
        private final Lock stateLock = new ReentrantLock();
        private boolean interrupted;
        private Thread thread;

        public InterruptibleRunnable(Callable<T> callable) {
            this.callable = callable;
        }

        @Override
        public void run() {

            beforeRun();
            try {
                result = callable.call();
            } catch (Throwable t) {
                registerFailure(t);
            } finally {
                afterRun();
            }

        }

        private void beforeRun() {
            stateLock.lock();
            try {
                thread = Thread.currentThread();
                if (interrupted) {
                    thread.interrupt();
                }
            } finally {
                stateLock.unlock();
            }
        }

        private void afterRun() {
            stateLock.lock();
            try {
                Thread.interrupted();
                thread = null;
            } finally {
                stateLock.unlock();
            }
        }

        public void interrupt() {
            stateLock.lock();
            try {
                if (thread == null) {
                    interrupted = true;
                } else {
                    thread.interrupt();
                }
            } finally {
                stateLock.unlock();
            }
        }
    }
}
