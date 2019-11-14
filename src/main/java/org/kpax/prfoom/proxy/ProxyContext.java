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

import org.apache.http.HeaderElement;
import org.apache.http.HeaderElementIterator;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.impl.client.*;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.kpax.prfoom.SystemConfig;
import org.kpax.prfoom.UserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.*;

/**
 * @author Eugen Covaci
 */
@Component
public class ProxyContext implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(ProxyContext.class);

    @Autowired
    private SystemConfig systemConfig;

    @Autowired
    private UserConfig userConfig;

    private ThreadPoolExecutor threadPool;

    private PoolingHttpClientConnectionManager connectionManager;

    private volatile SocketConfig socketConfig;

    private volatile RequestConfig proxyRequestConfig;

    private Timer connectionEvictionTimer;

    private CredentialsProvider credentialsProvider;

    @PostConstruct
    public void init() {
        logger.info("Create thread pool");
        threadPool = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());

        logger.info("Create pooling connection manager");
        connectionManager = new PoolingHttpClientConnectionManager();

        logger.info("Configure connection manager");
        if (systemConfig.getMaxConnections() != null) {
            connectionManager.setMaxTotal(systemConfig.getMaxConnections());
        }
        if (systemConfig.getMaxConnectionsPerRoute() != null) {
            connectionManager.setDefaultMaxPerRoute(systemConfig.getMaxConnectionsPerRoute());
        }

        if (systemConfig.isEvictionEnabled()) {
            logger.info("Create connection eviction timer");
            connectionEvictionTimer = new Timer();
            connectionEvictionTimer.schedule(new EvictionTask(), 0, systemConfig.getEvictionPeriod() * 1000);
        }

        logger.info("Done proxy context's initialization");
    }

    public void start() {
        this.credentialsProvider = createCredentialsProvider();
}

    public CloseableHttpClient getHttpClientBuilder(boolean retries) {
        HttpClientBuilder builder = HttpClients.custom().useSystemProperties()
                .setDefaultCredentialsProvider(getCredentialsProvider())
                .setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy())
                .setProxy(getProxyRequestConfig().getProxy())
                .setDefaultRequestConfig(getProxyRequestConfig())
                .setDefaultSocketConfig(getSocketConfig())
                .setConnectionManager(connectionManager)
                .setConnectionManagerShared(true)
                .setKeepAliveStrategy(new CustomConnectionKeepAliveStrategy())
                .disableRedirectHandling()
                .disableCookieManagement();
        if (!retries) {
            builder.disableAutomaticRetries();
        }
        return builder.build();
    }

    public void executeAsync(Runnable runnable) {
        threadPool.execute(runnable);
    }

    public Future<?> submitAsync(Runnable runnable) {
        return threadPool.submit(runnable);
    }

    public <T> Future<T> submitAsync(Callable<T> callable) {
        return threadPool.submit(callable);
    }

    public RequestConfig getProxyRequestConfig() {
        if (proxyRequestConfig == null) {
            synchronized (this) {
                if (proxyRequestConfig == null) {
                    logger.info("Create proxy request config");
                    HttpHost proxy = new HttpHost(userConfig.getProxyHost(), userConfig.getProxyPort());
                    List<String> proxyPreferredAuthSchemes = new ArrayList<>();
                    proxyPreferredAuthSchemes.add(AuthSchemes.SPNEGO);
                    proxyPreferredAuthSchemes.add(AuthSchemes.NTLM);
                    proxyPreferredAuthSchemes.add(AuthSchemes.DIGEST);
                    proxyPreferredAuthSchemes.add(AuthSchemes.BASIC);
                    proxyRequestConfig = RequestConfig.custom()
                            .setProxy(proxy)
                            .setProxyPreferredAuthSchemes(proxyPreferredAuthSchemes)
                            .setCircularRedirectsAllowed(true)
                            .build();
                }
            }
        }
        return proxyRequestConfig;
    }

    public SocketConfig getSocketConfig() {
        if (socketConfig == null) {
            synchronized (this) {
                if (socketConfig == null) {
                    logger.info("Create socket config");
                    socketConfig = SocketConfig.custom()
                            .setTcpNoDelay(true)
                            .setSndBufSize(systemConfig.getSocketBufferSize())
                            .setRcvBufSize(systemConfig.getSocketBufferSize())
                            .build();
                }
            }
        }
        return socketConfig;
    }

    private CredentialsProvider createCredentialsProvider() {
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY,
                new NTCredentials(userConfig.getUsername(), userConfig.getPassword(), null, userConfig.getDomain()));
        return credentialsProvider;
    }

    public CredentialsProvider getCredentialsProvider() {
        return credentialsProvider;
    }

    @Override
    public void close() {
        logger.info("Close all context's resources");

        try {
            threadPool.shutdownNow();
        } catch (Exception e) {
            logger.warn("Error on closing thread pool", e);
        }

        try {
            connectionManager.close();
        } catch (Exception e) {
            logger.warn("Error on closing PoolingHttpClientConnectionManager instance", e);
        }

        if (connectionEvictionTimer != null) {
            try {
                connectionEvictionTimer.cancel();
            } catch (Exception e) {
                logger.warn("Error on closing connectionEvictionTimer", e);
            }
        }
    }


    private class CustomConnectionKeepAliveStrategy implements ConnectionKeepAliveStrategy {

        @Override
        public long getKeepAliveDuration(HttpResponse response, HttpContext context) {

            // Honor 'keep-alive' header
            HeaderElementIterator it = new BasicHeaderElementIterator(response.headerIterator(HTTP.CONN_KEEP_ALIVE));
            while (it.hasNext()) {
                HeaderElement headerElement = it.nextElement();
                String value = headerElement.getValue();
                if (value != null && headerElement.getName().equalsIgnoreCase("timeout")) {
                    try {
                        return Long.parseLong(value) * 1000;
                    } catch (NumberFormatException ignore) {
                    }
                }
            }

            return systemConfig.getMaxConnectionIdle() * 1000;
        }

    }

    private class EvictionTask extends TimerTask {

        @Override
        public void run() {

            // Close expired connections
            connectionManager.closeExpiredConnections();

            // Close connections
            // that have been idle
            // longer than MAX_CONNECTION_IDLE seconds
            connectionManager.closeIdleConnections(systemConfig.getMaxConnectionIdle(), TimeUnit.SECONDS);

        }
    }

}
