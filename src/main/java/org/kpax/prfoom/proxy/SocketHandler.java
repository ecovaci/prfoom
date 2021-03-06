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
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.io.DefaultHttpRequestParser;
import org.apache.http.impl.io.HttpTransportMetricsImpl;
import org.apache.http.impl.io.SessionInputBufferImpl;
import org.apache.http.io.HttpMessageParser;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.util.EntityUtils;
import org.kpax.prfoom.SystemConfig;
import org.kpax.prfoom.UserConfig;
import org.kpax.prfoom.util.CloseableRepeater;
import org.kpax.prfoom.util.CrlfFormat;
import org.kpax.prfoom.util.HttpUtils;
import org.kpax.prfoom.util.LocalIOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.Arrays;
import java.util.List;

/**
 * @author Eugen Covaci
 */
@Component
@Scope(scopeName = ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class SocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(SocketHandler.class);

    private static final List<String> ENTITY_BANNED_HEADERS = Arrays.asList(HttpHeaders.CONTENT_LENGTH, HttpHeaders.CONTENT_TYPE,
            HttpHeaders.CONTENT_ENCODING, HttpHeaders.PROXY_AUTHORIZATION);

    private static final List<String> DEFAULT_BANNED_HEADERS = Arrays.asList(HttpHeaders.PROXY_AUTHORIZATION);

    @Autowired
    private SystemConfig systemConfig;

    @Autowired
    private UserConfig userConfig;

    @Autowired
    private ProxyContext proxyContext;

    @Autowired
    private CustomProxyClient proxyClient;

    private AsynchronousSocketChannelWrapper localSocketChannel;

    public SocketHandler bind(AsynchronousSocketChannel socketChannel) {
        Assert.isNull(localSocketChannel, "Socket already binded!");
        this.localSocketChannel = new AsynchronousSocketChannelWrapper(socketChannel);
        return this;
    }

    public void handleRequest() {
        logger.debug("Connection received");
        try {
            // Prepare request parsing
            HttpTransportMetricsImpl metrics = new HttpTransportMetricsImpl();
            SessionInputBufferImpl inputBuffer = new SessionInputBufferImpl(metrics, LocalIOUtils.DEFAULT_BUFFER_SIZE);
            inputBuffer.bind(localSocketChannel.getInputStream());

            // Parse the request (all but the message body )
            HttpMessageParser<HttpRequest> requestParser = new DefaultHttpRequestParser(inputBuffer);
            HttpRequest request = requestParser.parse();
            RequestLine requestLine = request.getRequestLine();
            logger.debug("Start processing request line {}", requestLine);

            if (HttpUtils.HTTP_CONNECT.equalsIgnoreCase(requestLine.getMethod())) {
                handleConnect(requestLine);
            } else {
                handleRequest(request, inputBuffer);
            }

            logger.debug("End processing request line {}", requestLine);
        } catch (ConnectionClosedException e) {
            logger.debug(e.getMessage(), e);
        } catch (Throwable e) {
            logger.error("Error on handling local socket connection", e);
        } finally {
            LocalIOUtils.close(localSocketChannel);
        }

    }

    /**
     * Creates a tunnel through proxy, then let the client and the remote proxy
     * communicate via the local socket channel instance.
     *
     * @param requestLine The first line of the request.
     * @throws Exception
     */
    private void handleConnect(RequestLine requestLine) throws Exception {
        URI uri = HttpUtils.parseConnectUri(requestLine.getUri());
        logger.debug("Handle proxy connect request");

        HttpHost proxy = new HttpHost(userConfig.getProxyHost(), userConfig.getProxyPort());
        HttpHost target = new HttpHost(uri.getHost(), uri.getPort());

        Socket socket = null;
        try {
            // Creates a tunnel through proxy.
            socket = proxyClient.tunnel(proxy, target, requestLine.getProtocolVersion(), localSocketChannel.getOutputStream());
            final OutputStream socketOutputStream = socket.getOutputStream();
            proxyContext.executeAsync(() -> LocalIOUtils.copyQuietly(localSocketChannel.getInputStream(), socketOutputStream));
            LocalIOUtils.copyQuietly(socket.getInputStream(), localSocketChannel.getOutputStream());
        } catch (org.apache.http.impl.execchain.TunnelRefusedException tre) {
            try {
                HttpResponse errorResponse = tre.getResponse();
                if (errorResponse != null) {
                    StatusLine errorStatusLine = errorResponse.getStatusLine();
                    logger.debug("errorStatusLine {}", errorStatusLine);

                    OutputStream localOutputStream = localSocketChannel.getOutputStream();
                    localOutputStream.write(CrlfFormat.format(errorStatusLine.toString()));

                    logger.debug("Start writing error headers");
                    for (Header header : errorResponse.getAllHeaders()) {
                        localOutputStream.write(CrlfFormat.format(header.toString()));
                    }

                    // Empty line
                    localOutputStream.write(CrlfFormat.CRLF.getBytes());

                    HttpEntity entity = errorResponse.getEntity();
                    if (entity != null) {
                        logger.debug("Start writing error entity content");
                        entity.writeTo(localOutputStream);
                        logger.debug("End writing error entity content");
                    }
                    EntityUtils.consume(entity);
                }
            } catch (Exception e) {
                logger.error("Error on sending error response", e);
            }
        } catch (Exception e) {
            logger.error("Error on creating/handling proxy tunnel", e);
        } finally {
            LocalIOUtils.close(socket);
        }
    }

    private void handleRequest(HttpRequest request, SessionInputBufferImpl inputBuffer) throws Exception {
        RequestLine requestLine = request.getRequestLine();
        logger.debug("Handle non-connect request {}", requestLine);

        // Set our streaming entity
        if (request instanceof BasicHttpEntityEnclosingRequest) {
            logger.debug("Create and set PseudoBufferedHttpEntity instance");
            HttpEntity entity = new PseudoBufferedHttpEntity(inputBuffer, request);
            ((BasicHttpEntityEnclosingRequest) request).setEntity(entity);
        } else if (logger.isDebugEnabled()) {
            logger.debug("No enclosing entity");
        }

        final boolean retryRequest = !(request instanceof BasicHttpEntityEnclosingRequest)
                || ((BasicHttpEntityEnclosingRequest) request).getEntity().isRepeatable();
        logger.debug("retryRequest {} ", retryRequest);

        URI uri = HttpUtils.parseUri(requestLine.getUri());
        CloseableHttpClient httpClient = proxyContext.getHttpClientBuilder(retryRequest);

        try {
            List<String> bannedHeaders = request instanceof BasicHttpEntityEnclosingRequest ?
                    ENTITY_BANNED_HEADERS : DEFAULT_BANNED_HEADERS;
            // Remove banned headers
            for (Header header : request.getAllHeaders()) {
                if (bannedHeaders.contains(header.getName())) {
                    request.removeHeader(header);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Request header {} removed", header);
                    }
                } else {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Allow request header {}", header);
                    }
                }
            }

            // Execute the request
            try {
                HttpHost target = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
                CloseableHttpResponse response;
                if (retryRequest) {
                    response = new CloseableRepeater<CloseableHttpResponse>().repeat(() -> httpClient.execute(target, request),
                            (t) -> t.getStatusLine().getStatusCode() != HttpStatus.SC_PROXY_AUTHENTICATION_REQUIRED,
                            systemConfig.getRepeatsOnFailure());
                } else {
                    response = httpClient.execute(target, request);
                }
                try {
                    String statusLine = response.getStatusLine().toString();
                    logger.debug("Response status line: {}", statusLine);

                    OutputStream outputStream = localSocketChannel.getOutputStream();
                    outputStream.write(CrlfFormat.format(statusLine));

                    logger.debug("Start writing response headers");
                    for (Header header : response.getAllHeaders()) {
                        if (HttpHeaders.TRANSFER_ENCODING.equals(header.getName())) {

                            // Strip 'chunked' from Transfer-Encoding header's value
                            String nonChunkedTransferEncoding = HttpUtils.stripChunked(header.getValue());
                            if (nonChunkedTransferEncoding != null && !nonChunkedTransferEncoding.isEmpty()) {
                                outputStream.write(CrlfFormat.format(HttpUtils.createHttpHeaderAsString(HttpHeaders.TRANSFER_ENCODING,
                                        nonChunkedTransferEncoding)));
                                logger.debug("Add chunk-striped header response");
                            } else {
                                logger.debug("Remove transfer encoding chunked header response");
                            }
                        } else {
                            String strHeader = header.toString();
                            logger.debug("Write response header: {}", strHeader);
                            outputStream.write(CrlfFormat.format(strHeader));
                        }
                    }

                    // Empty line marking the end
                    // of header's section
                    outputStream.write(CrlfFormat.CRLF.getBytes());

                    // Now write the request body, if any
                    HttpEntity entity = response.getEntity();
                    if (entity != null) {
                        logger.debug("Start writing entity content");
                        entity.writeTo(outputStream);
                        logger.debug("End writing entity content");
                    }

                    // Make sure the entity is fully consumed
                    EntityUtils.consume(entity);
                } finally {
                    LocalIOUtils.close(response);
                }

            } catch (org.apache.http.client.ClientProtocolException e) {
                logger.debug("Error in the HTTP protocol", e);
            } catch (Throwable e) {
                logger.debug("Error on executing HTTP request", e);
            }
            logger.debug("End handling non-connect request {}", requestLine);
        } finally {
            LocalIOUtils.close(httpClient);
        }
    }

}
