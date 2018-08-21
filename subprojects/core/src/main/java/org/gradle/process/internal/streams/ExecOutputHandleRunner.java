/*
 * Copyright 2010 the original author or authors.
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

package org.gradle.process.internal.streams;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.concurrent.CompositeStoppable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channels;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.CountDownLatch;

public class ExecOutputHandleRunner implements Runnable {
    private final static Logger LOGGER = Logging.getLogger(ExecOutputHandleRunner.class);

    private final String displayName;
    private final ReadableByteChannel inputChannel;
    private final WritableByteChannel outputChannel;
    private final int bufferSize;
    private final CountDownLatch completed;

    public ExecOutputHandleRunner(String displayName, InputStream inputStream, OutputStream outputStream, CountDownLatch completed) {
        this(displayName, inputStream, outputStream, 2048, completed);
    }

    ExecOutputHandleRunner(String displayName, InputStream inputStream, OutputStream outputStream, int bufferSize, CountDownLatch completed) {
        this.displayName = displayName;
        this.inputChannel = Channels.newChannel(inputStream);
        this.outputChannel = Channels.newChannel(outputStream);
        this.bufferSize = bufferSize;
        this.completed = completed;
    }

    public void run() {
        try {
            forwardContent();
        } finally {
            completed.countDown();
        }
    }

    private void forwardContent() {
        ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
        try {
            while (inputChannel.read(buffer) >= 0 || buffer.position() != 0) {
                ((Buffer)buffer).flip();
                outputChannel.write(buffer);
                buffer.compact();
            }
            CompositeStoppable.stoppable(inputChannel, outputChannel).stop();
        } catch (AsynchronousCloseException ignored) {
        } catch (Throwable t) {
            if (wasInterrupted(t)) {
                return;
            }
            LOGGER.error(String.format("Could not %s.", displayName), t);
        }
    }

    /**
     * This can happen e.g. on IBM JDK when a remote process was terminated. Instead of
     * returning -1 on the next read() call, it will interrupt the current read call.
     */
    private boolean wasInterrupted(Throwable t) {
        return t instanceof IOException && "Interrupted system call".equals(t.getMessage());
    }

    public void closeInput() throws IOException {
        inputChannel.close();
    }

    @Override
    public String toString() {
        return displayName;
    }
}
