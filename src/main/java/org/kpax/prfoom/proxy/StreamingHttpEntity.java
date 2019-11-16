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

import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.impl.io.SessionInputBufferImpl;
import org.kpax.prfoom.util.HttpUtils;
import org.kpax.prfoom.util.LocalIOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @author Eugen Covaci
 */
class StreamingHttpEntity extends AbstractHttpEntity {

    private static final Logger logger = LoggerFactory.getLogger(StreamingHttpEntity.class);

    private static final int INTERNAL_BUFFER_LENGTH = 100 * 1024;

    private final SessionInputBufferImpl inputBuffer;

    private final long contentLength;

    /**
     * Pre-write into this buffer to determine whether the entity
     * should be declared repeatable or not.
     */
    private byte[] bufferedBytes;

    private boolean repeatable;

    StreamingHttpEntity(SessionInputBufferImpl inputBuffer, HttpRequest request)
            throws IOException {
        this.inputBuffer = inputBuffer;
        this.contentType = request.getFirstHeader(HttpHeaders.CONTENT_TYPE);
        this.contentEncoding = request.getFirstHeader(HttpHeaders.CONTENT_ENCODING);
        this.contentLength = HttpUtils.getContentLength(request);

        // Set buffer and repeatable
        if (contentLength > INTERNAL_BUFFER_LENGTH) {
            this.bufferedBytes = new byte[0];
            this.repeatable = false;
        } else {
            logger.debug("Read buffered bytes");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeTo(out, contentLength < 0 ? INTERNAL_BUFFER_LENGTH : contentLength);
            this.bufferedBytes = out.toByteArray();
            this.repeatable = !(contentLength < 0 && LocalIOUtils.isAvailable(this.inputBuffer));
        }

        logger.debug("bufferedBytes {}", this.bufferedBytes.length);
    }

    private void writeTo(OutputStream out, long maxLength) throws IOException {
        byte[] buffer = new byte[OUTPUT_BUFFER_SIZE];
        int length;
        if (maxLength < 0) {
            // consume until EOF
            while (LocalIOUtils.isAvailable(inputBuffer)) {
                length = inputBuffer.read(buffer);
                if (length == -1) {
                    break;
                }
                out.write(buffer, 0, length);
                out.flush();
            }
        } else {
            // consume no more than maxLength
            long remaining = maxLength;
            while (remaining > 0 && LocalIOUtils.isAvailable(inputBuffer)) {
                length = inputBuffer.read(buffer, 0, (int) Math.min(OUTPUT_BUFFER_SIZE, remaining));
                if (length == -1) {
                    break;
                }
                out.write(buffer, 0, length);
                out.flush();
                remaining -= length;
            }
        }
    }

    @Override
    public void writeTo(OutputStream outputStream) throws IOException {

        // Write the initial buffer
        // This is the repeatable case
        if (bufferedBytes.length > 0) {
            logger.debug("Write initial buffer");
            outputStream.write(bufferedBytes);
            outputStream.flush();
        }

        // Write the remaining bytes when non-repeatable
        if (!repeatable) {
            logger.debug("Write the remaining bytes");
            long remaining = contentLength < 0 ? contentLength : contentLength - bufferedBytes.length;
            writeTo(outputStream, remaining);
        }

    }

    @Override
    public boolean isStreaming() {
        return true;
    }

    @Override
    public boolean isRepeatable() {
        return repeatable;
    }

    @Override
    public long getContentLength() {
        return contentLength;
    }

    @Override
    public InputStream getContent() {
        throw new UnsupportedOperationException("No content available");
    }

}