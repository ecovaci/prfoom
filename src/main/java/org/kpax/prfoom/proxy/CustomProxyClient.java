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

import org.apache.http.*;
import org.apache.http.auth.AUTH;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.auth.AuthState;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.protocol.RequestClientConnControl;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ManagedHttpClientConnection;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.RouteInfo.LayerType;
import org.apache.http.conn.routing.RouteInfo.TunnelType;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.auth.HttpAuthenticator;
import org.apache.http.impl.auth.NTLMSchemeFactory;
import org.apache.http.impl.client.ProxyAuthenticationStrategy;
import org.apache.http.impl.conn.ManagedHttpClientConnectionFactory;
import org.apache.http.impl.execchain.TunnelRefusedException;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.protocol.*;
import org.apache.http.util.Args;
import org.apache.http.util.EntityUtils;
import org.kpax.prfoom.SystemConfig;
import org.kpax.prfoom.auth.Authentication;
import org.kpax.prfoom.util.CrlfFormat;
import org.kpax.prfoom.util.HttpUtils;
import org.kpax.prfoom.util.LocalIOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

/**
 * ProxyClient can be used to establish a tunnel via an HTTP proxy.
 *
 * @author Eugen Covaci
 */
@Component
@Scope(scopeName = ConfigurableBeanFactory.SCOPE_PROTOTYPE)
class CustomProxyClient {

    private static final Logger logger = LoggerFactory.getLogger(CustomProxyClient.class);

    @Autowired
    private SystemConfig systemConfig;

    @Autowired
    private Authentication authentication;

    private HttpProcessor httpProcessor;
    private HttpRequestExecutor requestExec;
    private ProxyAuthenticationStrategy proxyAuthStrategy;
    private HttpAuthenticator authenticator;
    private AuthState proxyAuthState;
    private ConnectionReuseStrategy reuseStrategy;

    private Registry<AuthSchemeProvider> authSchemeRegistry;

    @PostConstruct
    public void init() {
        this.httpProcessor = new ImmutableHttpProcessor(new RequestTargetHost(), new RequestClientConnControl(), new RequestUserAgent());
        this.requestExec = new HttpRequestExecutor();
        this.proxyAuthStrategy = new ProxyAuthenticationStrategy();
        this.authenticator = new HttpAuthenticator();
        this.proxyAuthState = new AuthState();
        this.reuseStrategy = new DefaultConnectionReuseStrategy();
        this.authSchemeRegistry = RegistryBuilder.<AuthSchemeProvider>create()
                .register(AuthSchemes.NTLM, new NTLMSchemeFactory())
                .build();
    }

    public Socket tunnel(final HttpHost proxy, final HttpHost target,
                         final ProtocolVersion protocolVersion, final OutputStream responseStream)
            throws IOException, HttpException {
        Args.notNull(proxy, "Proxy host");
        Args.notNull(target, "Target host");
        Args.notNull(responseStream, "responseStream");
        HttpHost host = target;
        if (host.getPort() <= 0) {
            host = new HttpHost(host.getHostName(), 80, host.getSchemeName());
        }
        final HttpRoute route = new HttpRoute(host, RequestConfig.DEFAULT.getLocalAddress(),
                proxy, false, TunnelType.TUNNELLED, LayerType.PLAIN);
        final ManagedHttpClientConnection connection = ManagedHttpClientConnectionFactory.INSTANCE.create(route, ConnectionConfig.DEFAULT);
        final HttpContext context = new BasicHttpContext();
        final HttpRequest connect = new BasicHttpRequest(HttpUtils.HTTP_CONNECT, host.toHostString(), protocolVersion);

        // Populate the execution context
        context.setAttribute(HttpCoreContext.HTTP_TARGET_HOST, target);
        context.setAttribute(HttpCoreContext.HTTP_CONNECTION, connection);
        context.setAttribute(HttpCoreContext.HTTP_REQUEST, connect);
        context.setAttribute(HttpClientContext.HTTP_ROUTE, route);
        context.setAttribute(HttpClientContext.PROXY_AUTH_STATE, this.proxyAuthState);
        context.setAttribute(HttpClientContext.CREDS_PROVIDER, authentication.getCredentialsProvider());
        context.setAttribute(HttpClientContext.REQUEST_CONFIG, RequestConfig.DEFAULT);
        context.setAttribute(HttpClientContext.AUTHSCHEME_REGISTRY, this.authSchemeRegistry);

        this.requestExec.preProcess(connect, this.httpProcessor, context);

        HttpResponse response;
        while (true) {
            if (!connection.isOpen()) {
                final Socket socket = HttpUtils.tuneSocket(new Socket(proxy.getHostName(),
                        proxy.getPort()), systemConfig.getSocketBufferSize());
                connection.bind(socket);
            }

            this.authenticator.generateAuthResponse(connect, this.proxyAuthState, context);
            response = this.requestExec.execute(connect, connection, context);

            final int status = response.getStatusLine().getStatusCode();
            logger.debug("Tunnel status code: {}", status);
            if (status < 200) {
                throw new HttpException("Unexpected response to CONNECT request: " + response.getStatusLine());
            }

            if (this.authenticator.isAuthenticationRequested(proxy, response, this.proxyAuthStrategy, this.proxyAuthState, context)) {
                if (this.authenticator.handleAuthChallenge(proxy, response, this.proxyAuthStrategy, this.proxyAuthState, context)) {
                    // Retry request
                    if (this.reuseStrategy.keepAlive(response, context)) {
                        // Consume response content
                        logger.debug("Now consume entity");
                        EntityUtils.consume(response.getEntity());
                    } else {
                        logger.debug("Close tunnel connection");
                        LocalIOUtils.close(connection);
                    }
                    // discard previous auth header
                    connect.removeHeaders(AUTH.PROXY_AUTH_RESP);
                } else {
                    break;
                }
            } else {
                break;
            }

        }

        final int status = response.getStatusLine().getStatusCode();
        logger.debug("Tunnel final status code: {}", status);

        if (status > 299) { // Error case

            // Buffer response content
            final HttpEntity entity = response.getEntity();
            if (entity != null) {
                response.setEntity(new BufferedHttpEntity(entity));
            }
            logger.debug("Close tunnel connection");
            LocalIOUtils.close(connection);
            throw new TunnelRefusedException("CONNECT refused by proxy: " + response.getStatusLine(), response);
        }

        // Write the status line
        responseStream.write(CrlfFormat.format(response.getStatusLine().toString()));

        // Write the response headers.
        // The client might not be interested,
        // therefore only debug the error.
        try {
            logger.debug("Start writing tunnel response headers");
            for (Header header : response.getAllHeaders()) {
                String strHeader = header.toString();
                logger.debug("Write response tunnel header: {}", strHeader);
                responseStream.write(CrlfFormat.format(strHeader));
            }

            // Empty line to separate the headers
            responseStream.write(CrlfFormat.CRLF.getBytes());
        } catch (IOException e) {
            logger.debug("Error on writing headers", e);
        }

        return connection.getSocket();
    }

}
