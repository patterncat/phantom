/*
 * Copyright 2012-2015, the original author or authors.
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
package com.flipkart.phantom.netty.uds;

import org.jboss.netty.channel.*;
import org.jboss.netty.util.ThreadRenamingRunnable;
import org.jboss.netty.util.internal.DeadLockProofWorker;

import java.net.SocketAddress;
import java.util.concurrent.Executor;

import static org.jboss.netty.channel.Channels.*;

/**
 * Based on: org.jboss.netty.channel.socket.oio.OioDatagramPipelineSink
 * OIO package modified to work for Unix Domain Sockets instead of ServerSocket.
 * 
 * @author devashishshankar
 * @version 1.0, 19th April 2013
 */
class OioDatagramPipelineSink extends AbstractChannelSink {

    private final Executor workerExecutor;

    OioDatagramPipelineSink(Executor workerExecutor) {
        this.workerExecutor = workerExecutor;
    }

    public void eventSunk(
            ChannelPipeline pipeline, ChannelEvent e) throws Exception {
        OioDatagramChannel channel = (OioDatagramChannel) e.getChannel();
        ChannelFuture future = e.getFuture();
        if (e instanceof ChannelStateEvent) {
            ChannelStateEvent stateEvent = (ChannelStateEvent) e;
            ChannelState state = stateEvent.getState();
            Object value = stateEvent.getValue();
            switch (state) {
            case OPEN:
                if (Boolean.FALSE.equals(value)) {
                    OioDatagramWorker.close(channel, future);
                }
                break;
            case BOUND:
                if (value != null) {
                    bind(channel, future, (SocketAddress) value);
                } else {
                    OioDatagramWorker.close(channel, future);
                }
                break;
            case CONNECTED:
                if (value != null) {
                    connect(channel, future, (SocketAddress) value);
                } else {
                    OioDatagramWorker.disconnect(channel, future);
                }
                break;
            case INTEREST_OPS:
                OioDatagramWorker.setInterestOps(channel, future, ((Integer) value).intValue());
                break;
            }
        } else if (e instanceof MessageEvent) {
            MessageEvent evt = (MessageEvent) e;
            OioDatagramWorker.write(
                    channel, future, evt.getMessage(), evt.getRemoteAddress());
        }
    }

    private void bind(
            OioDatagramChannel channel, ChannelFuture future,
            SocketAddress localAddress) {
        boolean bound = false;
        boolean workerStarted = false;
        try {
            channel.socket.bind(localAddress);
            bound = true;

            // Fire events
            future.setSuccess();
            fireChannelBound(channel, channel.getLocalAddress());

            // Start the business.
            DeadLockProofWorker.start(
                    workerExecutor,
                    new ThreadRenamingRunnable(
                            new OioDatagramWorker(channel),
                            "Old I/O datagram worker (" + channel + ')'));
            workerStarted = true;
        } catch (Throwable t) {
            future.setFailure(t);
            fireExceptionCaught(channel, t);
        } finally {
            if (bound && !workerStarted) {
                OioDatagramWorker.close(channel, future);
            }
        }
    }

    private void connect(
            OioDatagramChannel channel, ChannelFuture future,
            SocketAddress remoteAddress) {

        boolean bound = channel.isBound();
        boolean connected = false;
        boolean workerStarted = false;

        future.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);

        // Clear the cached address so that the next getRemoteAddress() call
        // updates the cache.
        channel.remoteAddress = null;

        try {
            channel.socket.connect(remoteAddress);
            connected = true;

            // Fire events.
            future.setSuccess();
            if (!bound) {
                fireChannelBound(channel, channel.getLocalAddress());
            }
            fireChannelConnected(channel, channel.getRemoteAddress());

            String threadName = "Old I/O datagram worker (" + channel + ')';
            if (!bound) {
                // Start the business.
                DeadLockProofWorker.start(
                        workerExecutor,
                        new ThreadRenamingRunnable(
                                new OioDatagramWorker(channel), threadName));
            } else {
                // Worker started by bind() - just rename.
                Thread workerThread = channel.workerThread;
                if (workerThread != null) {
                    try {
                        workerThread.setName(threadName);
                    } catch (SecurityException e) {
                        // Ignore.
                    }
                }
            }

            workerStarted = true;
        } catch (Throwable t) {
            future.setFailure(t);
            fireExceptionCaught(channel, t);
        } finally {
            if (connected && !workerStarted) {
                OioDatagramWorker.close(channel, future);
            }
        }
    }
}
