/*******************************************************************************
 * Copyright (c) 2018 Eugen Covaci.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 *
 * Contributors:
 *     Eugen Covaci - initial design and implementation
 *******************************************************************************/

package org.kpax.prfoom.proxy;

import org.kpax.prfoom.SystemConfig;
import org.kpax.prfoom.UserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;

/**
 * The local proxy server.
 * We rely on the Spring context to close this instance!
 *
 * @author Eugen Covaci
 */
@Component
public class LocalProxyServer implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(LocalProxyServer.class);

    @Autowired
    private SystemConfig systemConfig;

    @Autowired
    private UserConfig userConfig;

    @Autowired
    private ProxyContext proxyContext;

    @Autowired
    private ApplicationContext applicationContext;

    private AsynchronousServerSocketChannel serverSocket;

    private boolean started;

    /**
     * Starts the local proxy server.
     * If the server had been started, an {@link IllegalStateException} would be thrown.
     *
     * @throws Exception
     */
    public synchronized void start()
            throws Exception {
        if (started) {
            throw new IllegalStateException("Server already started!");
        }
        logger.info("Start local proxy server with userConfig {}", userConfig);
        try {
            serverSocket = AsynchronousServerSocketChannel.open()
                    .bind(new InetSocketAddress(userConfig.getLocalPort()));
            serverSocket.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
                public void completed(AsynchronousSocketChannel socketChanel, Void att) {
                    try {
                        // accept the next connection
                        serverSocket.accept(null, this);
                    } catch (Exception e) {
                        logger.error("Error on accepting the next connection", e);
                    }

                    // Handle this connection.
                    // Tune the socket for better performance.
                    try {
                        applicationContext.getBean(SocketHandler.class)
                                .bind(socketChanel.setOption(StandardSocketOptions.TCP_NODELAY, true)
                                        .setOption(StandardSocketOptions.SO_RCVBUF,
                                                systemConfig.getServerSocketBufferSize())
                                        .setOption(StandardSocketOptions.SO_SNDBUF,
                                                systemConfig.getServerSocketBufferSize()))
                                .handleRequest();
                    } catch (Exception e) {
                        logger.error("Error on handling connection", e);
                    }
                }

                public void failed(Throwable exc, Void att) {

                    // Ignore java.nio.channels.AsynchronousCloseException error
                    if (!(exc instanceof AsynchronousCloseException)) {
                        logger.warn("SocketServer failed", exc);
                    }
                }
            });
            proxyContext.start();
            started = true;
            logger.info("Server started, listening on port: " + userConfig.getLocalPort());
        } catch (Exception e) {
            // Cleanup on exception
            close();
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        logger.info("Stop running the local proxy server, if any");
        if (serverSocket != null) {
            try {
                logger.info("Close the server socket");
                serverSocket.close();
            } catch (Exception e) {
                logger.warn("Error on closing server socket", e);
            }
        }
    }

    public boolean isStarted() {
        return started;
    }

}
