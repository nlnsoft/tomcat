/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.websocket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.websocket.ClientEndpointConfiguration;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.xml.bind.DatatypeConverter;

import org.apache.tomcat.util.res.StringManager;

public class WsWebSocketContainer implements WebSocketContainer {

    private static final StringManager sm =
            StringManager.getManager(Constants.PACKAGE_NAME);
    private static final Random random = new Random();
    private static final Charset iso88591 = Charset.forName("ISO-8859-1");
    private static final byte[] crlf = new byte[] {13, 10};
    private static final int DEFAULT_BUFFER_SIZE = 8 * 1024;

    private long defaultAsyncTimeout = -1;
    private int maxBinaryMessageBufferSize = DEFAULT_BUFFER_SIZE;
    private int maxTextMessageBufferSize = DEFAULT_BUFFER_SIZE;

    @Override
    public Session connectToServer(Class<?> annotatedEndpointClass, URI path)
            throws DeploymentException {
        // TODO Auto-generated method stub
        return null;
    }


    @Override
    public Session connectToServer(Class<? extends Endpoint> clazz,
            ClientEndpointConfiguration clientEndpointConfiguration, URI path)
            throws DeploymentException {

        String scheme = path.getScheme();
        if (!("http".equalsIgnoreCase(scheme) ||
                "https".equalsIgnoreCase(scheme))) {
            throw new DeploymentException(sm.getString(
                    "wsWebSocketContainer.pathWrongScheme", scheme));
        }
        String host = path.getHost();
        if (host == null) {
            throw new DeploymentException(
                    sm.getString("wsWebSocketContainer.pathNoHost"));
        }
        int port = path.getPort();
        Map<String,List<String>> reqHeaders = createRequestHeaders(host, port);
        clientEndpointConfiguration.beforeRequest(reqHeaders);

        ByteBuffer request = createRequest(path.getRawPath(), reqHeaders);

        SocketAddress sa;
        if (port == -1) {
            if ("http".equalsIgnoreCase(scheme)) {
                sa = new InetSocketAddress(host, 80);
            } else {
                // TODO HTTPS support
                // sa = new InetSocketAddress(host, 443);
                throw new DeploymentException("TODO: HTTPS");
            }
        } else {
            sa = new InetSocketAddress(host, port);
        }

        AsynchronousSocketChannel channel;
        try {
            channel = AsynchronousSocketChannel.open();
        } catch (IOException ioe) {
            throw new DeploymentException("TODO", ioe);
        }
        Future<Void> fConnect = channel.connect(sa);

        ByteBuffer response;
        try {
            fConnect.get();

            int toWrite = request.limit();

            Future<Integer> fWrite = channel.write(request);
            Integer thisWrite = fWrite.get();
            toWrite -= thisWrite.intValue();

            while (toWrite > 0) {
                fWrite = channel.write(request);
                thisWrite = fWrite.get();
                toWrite -= thisWrite.intValue();
            }
            // Same size as the WsFrame input buffer
            response = ByteBuffer.allocate(maxBinaryMessageBufferSize);

            HandshakeResponse handshakeResponse =
                    processResponse(response, channel);
            clientEndpointConfiguration.afterResponse(handshakeResponse);
        } catch (ExecutionException | InterruptedException e) {
            throw new DeploymentException("", e);
        }

        // Switch to WebSocket
        WsRemoteEndpointClient wsRemoteEndpointClient =
                new WsRemoteEndpointClient(channel);

        Endpoint endpoint;
        try {
            endpoint = clazz.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new DeploymentException(sm.getString(
                    "wsWebSocketContainer.endpointCreateFail", clazz.getName()),
                    e);
        }
        WsSession wsSession =
                new WsSession(endpoint, wsRemoteEndpointClient, this);

        endpoint.onOpen(wsSession, clientEndpointConfiguration);

        // Object creation will trigger input processing
        @SuppressWarnings("unused")
        WsFrameClient wsFrameClient = new WsFrameClient(response, channel,
                maxBinaryMessageBufferSize, maxTextMessageBufferSize,
                wsSession);

        return wsSession;
    }


    private Map<String,List<String>> createRequestHeaders(String host,
            int port) {

        Map<String,List<String>> headers = new HashMap<>();

        // Host header
        List<String> hostValues = new ArrayList<>(1);
        if (port == -1) {
            hostValues.add(host);
        } else {
            hostValues.add(host + ':' + port);
        }

        headers.put(Constants.HOST_HEADER_NAME, hostValues);

        // Upgrade header
        List<String> upgradeValues = new ArrayList<>(1);
        upgradeValues.add(Constants.UPGRADE_HEADER_VALUE);
        headers.put(Constants.UPGRADE_HEADER_NAME, upgradeValues);

        // Connection header
        List<String> connectionValues = new ArrayList<>(1);
        connectionValues.add(Constants.CONNECTION_HEADER_VALUE);
        headers.put(Constants.CONNECTION_HEADER_NAME, connectionValues);

        // WebSocket version header
        List<String> wsVersionValues = new ArrayList<>(1);
        wsVersionValues.add(Constants.WS_VERSION_HEADER_VALUE);
        headers.put(Constants.WS_VERSION_HEADER_NAME, wsVersionValues);

        // WebSocket key
        List<String> wsKeyValues = new ArrayList<>(1);
        wsKeyValues.add(generateWsKeyValue());
        headers.put(Constants.WS_KEY_HEADER_NAME, wsKeyValues);

        return headers;
    }


    private String generateWsKeyValue() {
        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        return DatatypeConverter.printBase64Binary(keyBytes);
    }


    private ByteBuffer createRequest(String rawPath,
            Map<String,List<String>> reqHeaders) {
        ByteBuffer result = ByteBuffer.allocate(4 * 1024);

        // Request line
        result.put("GET ".getBytes(iso88591));
        result.put(rawPath.getBytes(iso88591));
        result.put(" HTTP/1.1\r\n".getBytes(iso88591));

        // Headers
        Iterator<Entry<String,List<String>>> iter =
                reqHeaders.entrySet().iterator();
        while (iter.hasNext()) {
            Entry<String,List<String>> entry = iter.next();
            addHeader(result, entry.getKey(), entry.getValue());
        }

        // Terminating CRLF
        result.put(crlf);

        result.flip();

        return result;
    }


    private void addHeader(ByteBuffer result, String key, List<String> values) {
        StringBuilder sb = new StringBuilder();

        Iterator<String> iter = values.iterator();
        if (!iter.hasNext()) {
            return;
        }
        sb.append(iter.next());
        while (iter.hasNext()) {
            sb.append(',');
            sb.append(iter.next());
        }

        result.put(key.getBytes(iso88591));
        result.put(": ".getBytes(iso88591));
        result.put(sb.toString().getBytes(iso88591));
        result.put(crlf);
    }


    /**
     * Process response, blocking until HTTP response has been fully received.
     * @throws ExecutionException
     * @throws InterruptedException
     * @throws DeploymentException
     */
    private HandshakeResponse processResponse(ByteBuffer response,
            AsynchronousSocketChannel channel) throws InterruptedException,
            ExecutionException, DeploymentException {

        Map<String,List<String>> headers = new HashMap<>();

        boolean readStatus = false;
        boolean readHeaders = false;
        String line = null;
        while (!readHeaders) {
            // Blocking read
            Future<Integer> written = channel.read(response);
            written.get();
            response.flip();
            while (response.hasRemaining()) {
                if (line == null) {
                    line = readLine(response);
                } else {
                    line += readLine(response);
                }
                if ("\r\n".equals(line)) {
                    readHeaders = true;
                } else if (line.endsWith("\r\n")) {
                    if (readStatus) {
                        parseHeaders(line, headers);
                    } else {
                        parseStatus(line);
                        readStatus = true;
                    }
                    line = null;
                }
            }
        }

        return new WsHandshakeResponse(headers);
    }


    private void parseStatus(String line) throws DeploymentException {
        // This client only understands HTTP 1.1
        // RFC2616 is case specific
        if (!line.startsWith("HTTP/1.1 101")) {
            throw new DeploymentException(sm.getString(
                    "wsWebSocketContainer.invalidStatus", line));
        }
    }


    private void parseHeaders(String line, Map<String,List<String>> headers) {
        // Treat headers as single values by default.

        int index = line.indexOf(':');
        if (index == -1) {
            // TODO Log invalid header
            return;
        }
        String headerName = line.substring(0, index).trim().toLowerCase();
        // TODO handle known multi-value headers
        String headerValue = line.substring(index + 1).trim();

        List<String> values = headers.get(headerName);
        if (values == null) {
            values = new ArrayList<>(1);
            headers.put(headerName, values);
        }
        values.add(headerValue);
    }


    private String readLine(ByteBuffer response) {
        // All ISO-8859-1
        StringBuilder sb = new StringBuilder();

        char c = 0;
        while (response.hasRemaining() && c != 10) {
            c = (char) response.get();
            sb.append(c);
        }

        return sb.toString();
    }


    @Override
    public Set<Session> getOpenSessions() {
        // TODO Auto-generated method stub
        return null;
    }


    @Override
    public long getMaxSessionIdleTimeout() {
        // TODO Auto-generated method stub
        return 0;
    }


    @Override
    public void setMaxSessionIdleTimeout(long timeout) {
        // TODO Auto-generated method stub
    }


    @Override
    public long getMaxBinaryMessageBufferSize() {
        return maxBinaryMessageBufferSize;
    }


    @Override
    public void setMaxBinaryMessageBufferSize(long max) {
        if (max > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    sm.getString("wsWebSocketContainer.maxBuffer"));
        }
        maxBinaryMessageBufferSize = (int) max;
    }


    @Override
    public long getMaxTextMessageBufferSize() {
        return maxTextMessageBufferSize;
    }


    @Override
    public void setMaxTextMessageBufferSize(long max) {
        if (max > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    sm.getString("wsWebSocketContainer.maxBuffer"));
        }
        maxTextMessageBufferSize = (int) max;
    }


    @Override
    public Set<Extension> getInstalledExtensions() {
        // TODO Auto-generated method stub
        return null;
    }


    /**
     * {@inheritDoc}
     *
     * The default value for this implementation is -1.
     */
    @Override
    public long getDefaultAsyncSendTimeout() {
        return defaultAsyncTimeout;
    }


    /**
     * {@inheritDoc}
     *
     * The default value for this implementation is -1.
     */
    @Override
    public void setAsyncSendTimeout(long timeout) {
        this.defaultAsyncTimeout = timeout;
    }

    private static class WsHandshakeResponse implements HandshakeResponse {

        private final Map<String,List<String>> headers;

        public WsHandshakeResponse(Map<String,List<String>> headers) {
            this.headers = headers;
        }

        @Override
        public Map<String,List<String>> getHeaders() {
            return headers;
        }
    }
}
