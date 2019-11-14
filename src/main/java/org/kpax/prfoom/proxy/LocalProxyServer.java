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
import org.kpax.prfoom.exception.CommandExecutionException;
import org.kpax.prfoom.exception.InvalidKdcException;
import org.kpax.prfoom.exception.KdcNotFoundException;
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
import java.security.GeneralSecurityException;

/**
 * The local proxy server.
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
            proxyContext.start();

            // Test the proxy configuration


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
                    // handle this connection
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
                    if (!(exc instanceof AsynchronousCloseException)) {
                        logger.warn("SocketServer failed", exc);
                    }
                }
            });
            started = true;
            logger.info("Server started, listening on port: " + userConfig.getLocalPort());
        } catch (Exception e) {
            // Cleanup on exception
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (Exception e1) {
                    logger.warn("Error on closing server socket", e1);
                }
            }
            throw e;
        }
    }

    @Override
    public void close() {
        logger.info("Stop running local proxy server");
        if (serverSocket != null) {
            try {
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
