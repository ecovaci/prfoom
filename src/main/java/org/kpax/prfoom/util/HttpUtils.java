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

package org.kpax.prfoom.util;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.*;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HTTP;
import org.kpax.prfoom.UserConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.CredentialException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author Eugen Covaci
 */
public final class HttpUtils {

    public static final String STATUS_LINE_CONNECTION_ESTABLISHED = "200 Connection established";

    public static final String CONNECTION_ESTABLISHED = "Connection established";

    public static final String HTTP_CONNECT = "CONNECT";

    public static final String PROXY_CONNECTION = "Proxy-Connection";

    public static final int MAX_PORT_VALUE = 65535;

    private static final Logger logger = LoggerFactory.getLogger(HttpUtils.class);

    private HttpUtils() {
    }

    public static URI parseConnectUri(String uri) throws NumberFormatException, URISyntaxException {
        String[] split = uri.split(":");
        return new URI(null, null, split[0], Integer.parseInt(split[1]), null, null, null);
    }

    public static URI parseUri(String url) throws URISyntaxException {
        int index = url.indexOf("?");
        if (index > -1 && index < url.length()) {
            URIBuilder uriBuilder = new URIBuilder(url.substring(0, index));
            List<NameValuePair> nvps = URLEncodedUtils.parse(url.substring(index + 1), StandardCharsets.UTF_8);
            uriBuilder.addParameters(nvps);
            return uriBuilder.build();
        }
        return new URIBuilder(url).build();
    }

    public static String connectionEstablished(ProtocolVersion version) {
        return version.toString() + StringUtils.SPACE + STATUS_LINE_CONNECTION_ESTABLISHED;
    }

    public static String stripChunked(String value) {
        return Arrays.stream(value.split(",")).filter((item) -> !HTTP.CHUNK_CODING.equalsIgnoreCase(item)).collect(Collectors.joining(","));
    }

    public static Optional<Header> getFirstHeader(HttpRequest request, String name) {
        return Optional.ofNullable(request.getFirstHeader(name));
    }

    public static Optional<String> getFirstHeaderValue(HttpRequest request, String name) {
        return getFirstHeader(request, name).map(h -> h.getValue());
    }

    public static long getContentLength(HttpRequest request) {
        return getFirstHeaderValue(request, HttpHeaders.CONTENT_LENGTH).map(Long::parseLong).orElse(-1L);
    }

    public static Header createHttpHeader(String name, String value) {
        return new BasicHeader(name, value);
    }

    public static String createHttpHeaderAsString(String name, String value) {
        return createHttpHeader(name, value).toString();
    }

    public static Socket tuneSocket(Socket socket, int bufferSize) throws SocketException {
        socket.setTcpNoDelay(true);
        socket.setReceiveBufferSize(bufferSize);
        socket.setSendBufferSize(bufferSize);
        return socket;
    }

    public static void testProxyConfig(UserConfig userConfig)
            throws IOException, CredentialException {
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY,
                new NTCredentials(userConfig.getUsername(), userConfig.getPassword(), null, userConfig.getDomain()));
        try (CloseableHttpClient httpClient = HttpClients.custom().useSystemProperties()
                .setDefaultCredentialsProvider(credentialsProvider).build()) {
            HttpHost target = HttpHost.create(userConfig.getProxyTestUrl());
            HttpHost proxy = new HttpHost(userConfig.getProxyHost(), userConfig.getProxyPort(), "http");

            RequestConfig config = RequestConfig.custom()
                    .setProxy(proxy)
                    .build();
            HttpGet request = new HttpGet("/");
            request.setConfig(config);
            logger.info("Executing request " + request.getRequestLine() + " to " + target + " via " + proxy);
            try (CloseableHttpResponse response = httpClient.execute(target, request);) {
                logger.info("Test response status {}", response.getStatusLine());
                if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                } else if (response.getStatusLine().getStatusCode() == HttpStatus.SC_PROXY_AUTHENTICATION_REQUIRED) {
                    throw new CredentialException();
                }
            }
        } catch (Exception e) {
            logger.error("Error on testing proxy config", e);
            throw e;
        }
    }

}
