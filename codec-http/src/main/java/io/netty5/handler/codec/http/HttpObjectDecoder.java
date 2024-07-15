/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.handler.codec.http;

import io.netty5.buffer.Buffer;
import io.netty5.buffer.BufferAllocator;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelPipeline;
import io.netty5.handler.codec.ByteToMessageDecoder;
import io.netty5.handler.codec.DecoderResult;
import io.netty5.handler.codec.PrematureChannelClosureException;
import io.netty5.handler.codec.TooLongFrameException;
import io.netty5.handler.codec.http.headers.HttpHeaders;
import io.netty5.handler.codec.http.headers.HttpHeadersFactory;
import io.netty5.util.AsciiString;
import io.netty5.util.ByteProcessor;
import io.netty5.util.internal.AppendableCharSequence;

import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decodes {@link Buffer}s into {@link HttpMessage}s and
 * {@link HttpContent}s.
 *
 * <h3>Parameters that prevents excessive memory consumption</h3>
 * <table border="1">
 * <tr>
 * <th>Name</th><th>Default value</th><th>Meaning</th>
 * </tr>
 * <tr>
 * <td>{@code maxInitialLineLength}</td>
 * <td>{@value #DEFAULT_MAX_INITIAL_LINE_LENGTH}</td>
 * <td>The maximum length of the initial line
 *     (e.g. {@code "GET / HTTP/1.0"} or {@code "HTTP/1.0 200 OK"})
 *     If the length of the initial line exceeds this value, a
 *     {@link TooLongHttpLineException} will be raised.</td>
 * </tr>
 * <tr>
 * <td>{@code maxHeaderSize}</td>
 * <td>{@value #DEFAULT_MAX_HEADER_SIZE}</td>
 * <td>The maximum length of all headers.  If the sum of the length of each
 *     header exceeds this value, a {@link TooLongHttpHeaderException} will be raised.</td>
 * </tr>
 * </table>
 *
 * <h3>Parameters that control parsing behavior</h3>
 * <table border="1">
 * <tr>
 * <th>Name</th><th>Default value</th><th>Meaning</th>
 * </tr>
 * <tr>
 * <td>{@code allowDuplicateContentLengths}</td>
 * <td>{@value #DEFAULT_ALLOW_DUPLICATE_CONTENT_LENGTHS}</td>
 * <td>When set to {@code false}, will reject any messages that contain multiple Content-Length header fields.
 *     When set to {@code true}, will allow multiple Content-Length headers only if they are all the same decimal value.
 *     The duplicated field-values will be replaced with a single valid Content-Length field.
 *     See <a href="https://tools.ietf.org/html/rfc7230#section-3.3.2">RFC 7230, Section 3.3.2</a>.</td>
 * </tr>
 * </table>
 *
 * <h3>Chunked Content</h3>
 *
 * If the content of an HTTP message is greater than {@code maxChunkSize} or
 * the transfer encoding of the HTTP message is 'chunked', this decoder
 * generates one {@link HttpMessage} instance and its following
 * {@link HttpContent}s per single HTTP message to avoid excessive memory
 * consumption. For example, the following HTTP message:
 * <pre>
 * GET / HTTP/1.1
 * Transfer-Encoding: chunked
 *
 * 1a
 * abcdefghijklmnopqrstuvwxyz
 * 10
 * 1234567890abcdef
 * 0
 * Content-MD5: ...
 * <i>[blank line]</i>
 * </pre>
 * triggers {@link HttpRequestDecoder} to generate 3 objects:
 * <ol>
 * <li>An {@link HttpRequest},</li>
 * <li>The first {@link HttpContent} whose content is {@code 'abcdefghijklmnopqrstuvwxyz'},</li>
 * <li>The second {@link LastHttpContent} whose content is {@code '1234567890abcdef'}, which marks
 * the end of the content.</li>
 * </ol>
 *
 * If you prefer not to handle {@link HttpContent}s by yourself for your
 * convenience, insert {@link HttpObjectAggregator} after this decoder in the
 * {@link ChannelPipeline}.  However, please note that your server might not
 * be as memory efficient as without the aggregator.
 *
 * <h3>Extensibility</h3>
 *
 * Please note that this decoder is designed to be extended to implement
 * a protocol derived from HTTP, such as
 * <a href="https://en.wikipedia.org/wiki/Real_Time_Streaming_Protocol">RTSP</a> and
 * <a href="https://en.wikipedia.org/wiki/Internet_Content_Adaptation_Protocol">ICAP</a>.
 * To implement the decoder of such a derived protocol, extend this class and
 * implement all abstract methods properly.
 *
 * <h3>Header Validation</h3>
 *
 * It is recommended to always enable header validation.
 * <p>
 * Without header validation, your system can become vulnerable to
 * <a href="https://cwe.mitre.org/data/definitions/113.html">
 *     CWE-113: Improper Neutralization of CRLF Sequences in HTTP Headers ('HTTP Response Splitting')
 * </a>.
 * <p>
 * This recommendation stands even when both peers in the HTTP exchange are trusted,
 * as it helps with defence-in-depth.
 */
public abstract class HttpObjectDecoder extends ByteToMessageDecoder {
    public static final int DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096;
    public static final int DEFAULT_MAX_HEADER_SIZE = 8192;
    public static final boolean DEFAULT_CHUNKED_SUPPORTED = true;
    public static final boolean DEFAULT_VALIDATE_HEADERS = true;
    public static final int DEFAULT_INITIAL_BUFFER_SIZE = 128;
    public static final boolean DEFAULT_ALLOW_DUPLICATE_CONTENT_LENGTHS = false;

    private static final String EMPTY_VALUE = "";

    private final boolean chunkedSupported;
    protected final HttpHeadersFactory headersFactory;
    protected final HttpHeadersFactory trailersFactory;
    private final boolean allowDuplicateContentLengths;
    private final HeaderParser headerParser;
    private final LineParser lineParser;

    private HttpMessage message;
    private long chunkSize;
    private long contentLength = Long.MIN_VALUE;
    private boolean chunked;
    private boolean isSwitchingToNonHttp1Protocol;
    private final AtomicBoolean resetRequested = new AtomicBoolean();

    // These will be updated by splitHeader(...)
    private CharSequence name;
    private CharSequence value;

    private LastHttpContent<?> trailer;

    /**
     * The internal state of {@link HttpObjectDecoder}.
     * <em>Internal use only</em>.
     */
    private enum State {
        SKIP_CONTROL_CHARS,
        READ_INITIAL,
        READ_HEADER,
        READ_VARIABLE_LENGTH_CONTENT,
        READ_FIXED_LENGTH_CONTENT,
        READ_CHUNK_SIZE,
        READ_CHUNKED_CONTENT,
        READ_CHUNK_DELIMITER,
        READ_CHUNK_FOOTER,
        BAD_MESSAGE,
        UPGRADED
    }

    private State currentState = State.SKIP_CONTROL_CHARS;

    /**
     * Creates a new instance with the default
     * {@code maxInitialLineLength} ({@value DEFAULT_MAX_INITIAL_LINE_LENGTH}),
     * {@code maxHeaderSize} ({@value DEFAULT_MAX_HEADER_SIZE}),
     * and {@code chunkedSupported} ({@value DEFAULT_CHUNKED_SUPPORTED}).
     */
    protected HttpObjectDecoder() {
        this(new HttpDecoderConfig());
    }

    /**
     * Creates a new instance with the specified configuration.
     */
    protected HttpObjectDecoder(HttpDecoderConfig config) {
        headersFactory = config.getHeadersFactory();
        trailersFactory = config.getTrailersFactory();
        AppendableCharSequence seq = new AppendableCharSequence(config.getInitialBufferSize());
        lineParser = new LineParser(seq, config.getMaxInitialLineLength());
        headerParser = new HeaderParser(seq, config.getMaxHeaderSize());
        chunkedSupported = config.isChunkedSupported();
        allowDuplicateContentLengths = config.isAllowDuplicateContentLengths();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, Buffer buffer) throws Exception {
        if (resetRequested.get()) {
            resetNow();
        }

        switch (currentState) {
        case SKIP_CONTROL_CHARS:
            // Fall-through
        case READ_INITIAL: try {
            AppendableCharSequence line = lineParser.parse(buffer);
            if (line == null) {
                return;
            }
            String[] initialLine = splitInitialLine(line);
            if (initialLine.length < 3) {
                // Invalid initial line - ignore.
                currentState = State.SKIP_CONTROL_CHARS;
                return;
            }

            message = createMessage(initialLine);
            currentState = State.READ_HEADER;
            // fall-through
        } catch (Exception e) {
            ctx.fireChannelRead(invalidMessage(ctx, message, buffer, e));
            return;
        }
        case READ_HEADER: try {
            State nextState = readHeaders(buffer);
            if (nextState == null) {
                return;
            }
            currentState = nextState;
            switch (nextState) {
            case SKIP_CONTROL_CHARS:
                // fast-path
                // No content is expected.
                addCurrentMessage(ctx);
                ctx.fireChannelRead(new EmptyLastHttpContent(ctx.bufferAllocator()));
                resetNow();
                return;
            case READ_CHUNK_SIZE:
                if (!chunkedSupported) {
                    throw new IllegalArgumentException("Chunked messages not supported");
                }
                // Chunked encoding - generate HttpMessage first.  HttpChunks will follow.
                addCurrentMessage(ctx);
                return;
            default:
                /*
                  RFC 7230, 3.3.3 (https://tools.ietf.org/html/rfc7230#section-3.3.3) states that if a
                  request does not have either a transfer-encoding or a content-length header then the message body
                  length is 0. However for a response the body length is the number of octets received prior to the
                  server closing the connection. So we treat this as variable length chunked encoding.
                 */
                if (contentLength == 0 || contentLength == -1 && isDecodingRequest()) {
                    ctx.fireChannelRead(message);
                    ctx.fireChannelRead(new EmptyLastHttpContent(ctx.bufferAllocator()));
                    resetNow();
                    return;
                }

                assert nextState == State.READ_FIXED_LENGTH_CONTENT ||
                        nextState == State.READ_VARIABLE_LENGTH_CONTENT;

                addCurrentMessage(ctx);

                if (nextState == State.READ_FIXED_LENGTH_CONTENT) {
                    // chunkSize will be decreased as the READ_FIXED_LENGTH_CONTENT state reads data chunk by chunk.
                    chunkSize = contentLength;
                }

                // We return here, this forces decode to be called again where we will decode the content
                return;
            }
        } catch (Exception e) {
            ctx.fireChannelRead(invalidMessage(ctx, message, buffer, e));
            return;
        }
        case READ_VARIABLE_LENGTH_CONTENT: {
            // Keep reading data as a chunk until the end of connection is reached.
            int toRead = buffer.readableBytes();
            if (toRead > 0) {
                Buffer content = buffer.split();
                ctx.fireChannelRead(new DefaultHttpContent(content));
            }
            return;
        }
        case READ_FIXED_LENGTH_CONTENT: {
            int toRead = buffer.readableBytes();

            // Check if the buffer is readable first as we use the readable byte count
            // to create the HttpChunk. This is needed as otherwise we may end up with
            // create an HttpChunk instance that contains an empty buffer and so is
            // handled like it is the last HttpChunk.
            //
            // See https://github.com/netty/netty/issues/433
            if (toRead == 0) {
                return;
            }

            if (toRead > chunkSize) {
                toRead = (int) chunkSize;
            }

            Buffer content = buffer.readSplit(toRead);
            chunkSize -= toRead;

            if (chunkSize == 0) {
                // Read all content.
                ctx.fireChannelRead(new DefaultLastHttpContent(content, trailersFactory));
                resetNow();
            } else {
                ctx.fireChannelRead(new DefaultHttpContent(content));
            }
            return;
        }
        /*
          everything else after this point takes care of reading chunked content. basically, read chunk size,
          read chunk, read and ignore the CRLF and repeat until 0
         */
        case READ_CHUNK_SIZE: try {
            AppendableCharSequence line = lineParser.parse(buffer);
            if (line == null) {
                return;
            }
            int chunkSize = getChunkSize(line.toString());
            this.chunkSize = chunkSize;
            if (chunkSize == 0) {
                currentState = State.READ_CHUNK_FOOTER;
                return;
            }
            currentState = State.READ_CHUNKED_CONTENT;
            // fall-through
        } catch (Exception e) {
            ctx.fireChannelRead(invalidChunk(ctx.bufferAllocator(), buffer, e));
            return;
        }
        case READ_CHUNKED_CONTENT: {
            assert chunkSize <= Integer.MAX_VALUE;
            int toRead = (int) chunkSize;
            toRead = Math.min(toRead, buffer.readableBytes());
            if (toRead == 0) {
                return;
            }
            HttpContent<?> chunk = new DefaultHttpContent(buffer.readSplit(toRead));
            chunkSize -= toRead;

            ctx.fireChannelRead(chunk);

            if (chunkSize != 0) {
                return;
            }
            currentState = State.READ_CHUNK_DELIMITER;
            // fall-through
        }
        case READ_CHUNK_DELIMITER: {
            // include LF in the bytes to skip
            int bytesToSkip = buffer.bytesBefore(HttpConstants.LF) + 1;
            if (bytesToSkip > 0) {
                currentState = State.READ_CHUNK_SIZE;
                buffer.skipReadableBytes(bytesToSkip);
            } else {
                buffer.skipReadableBytes(buffer.readableBytes());
            }
            return;
        }
        case READ_CHUNK_FOOTER: try {
            LastHttpContent<?> trailer = readTrailingHeaders(ctx.bufferAllocator(), buffer);
            if (trailer == null) {
                return;
            }
            ctx.fireChannelRead(trailer);
            resetNow();
            return;
        } catch (Exception e) {
            ctx.fireChannelRead(invalidChunk(ctx.bufferAllocator(), buffer, e));
            return;
        }
        case BAD_MESSAGE: {
            // Keep discarding until disconnection.
            buffer.skipReadableBytes(buffer.readableBytes());
            break;
        }
        case UPGRADED: {
            int readableBytes = buffer.readableBytes();
            if (readableBytes > 0) {
                // Keep on consuming as otherwise we may trigger an DecoderException,
                // other handler will replace this codec with the upgraded protocol codec to
                // take the traffic over at some point then.
                // See https://github.com/netty/netty/issues/2173
                ctx.fireChannelRead(buffer.split());
            }
            break;
        }
        default:
            break;
        }
    }

    @Override
    protected void decodeLast(ChannelHandlerContext ctx, Buffer in) throws Exception {
        super.decodeLast(ctx, in);

        if (resetRequested.get()) {
            // If a reset was requested by decodeLast() we need to do it now otherwise we may produce a
            // LastHttpContent while there was already one.
            resetNow();
        }
        // Handle the last unfinished message.
        switch (currentState) {
            case READ_VARIABLE_LENGTH_CONTENT:
                if (!chunked && in.readableBytes() == 0) {
                    // End of connection.
                    ctx.fireChannelRead(new EmptyLastHttpContent(ctx.bufferAllocator()));
                    resetNow();
                }
                return;
            case READ_HEADER:
                // If we are still in the state of reading headers we need to create a new invalid message that
                // signals that the connection was closed before we received the headers.
                ctx.fireChannelRead(invalidMessage(ctx, message,
                        new PrematureChannelClosureException("Connection closed before received headers")));
                resetNow();
                return;
            case READ_CHUNK_DELIMITER: // fall-trough
            case READ_CHUNK_FOOTER: // fall-trough
            case READ_CHUNKED_CONTENT: // fall-trough
            case READ_CHUNK_SIZE: // fall-trough
            case READ_FIXED_LENGTH_CONTENT:
                // Check if the closure of the connection signifies the end of the content.
                boolean prematureClosure;
                if (isDecodingRequest() || chunked) {
                    // The last request did not wait for a response.
                    prematureClosure = true;
                } else {
                    // Compare the length of the received content and the 'Content-Length' header.
                    // If the 'Content-Length' header is absent, the length of the content is determined by the end of
                    // the connection, so it is perfectly fine.
                    prematureClosure = contentLength > 0;
                }
                if (!prematureClosure) {
                    ctx.fireChannelRead(new EmptyLastHttpContent(ctx.bufferAllocator()));
                }
                resetNow();
                return;
            case SKIP_CONTROL_CHARS: // fall-trough
            case READ_INITIAL:// fall-trough
            case BAD_MESSAGE: // fall-trough
            case UPGRADED: // fall-trough
                // Do nothing
                break;
            default:
                throw new IllegalStateException("Unhandled state " + currentState);
        }
    }

    @Override
    public void channelInboundEvent(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof HttpExpectationFailedEvent) {
            switch (currentState) {
            case READ_FIXED_LENGTH_CONTENT:
            case READ_VARIABLE_LENGTH_CONTENT:
            case READ_CHUNK_SIZE:
                reset();
                break;
            default:
                break;
            }
        }
        super.channelInboundEvent(ctx, evt);
    }

    private void addCurrentMessage(ChannelHandlerContext ctx) {
        HttpMessage message = this.message;
        assert message != null;
        this.message = null;
        ctx.fireChannelRead(message);
    }

    protected boolean isContentAlwaysEmpty(HttpMessage msg) {
        if (msg instanceof HttpResponse) {
            HttpResponse res = (HttpResponse) msg;
            final HttpResponseStatus status = res.status();
            final int code = status.code();
            final HttpStatusClass statusClass = status.codeClass();

            // All 1xx (Informational), 204 (No Content), and 304 (Not Modified) responses do not include
            // a message body. All other responses do include a message body,
            // although the body might be of zero length.
            // https://httpwg.org/specs/rfc7230.html#message.body
            if (statusClass == HttpStatusClass.INFORMATIONAL) {
                return true;
            }

            return code == 204 || code == 304;
        }

        return false;
    }

    /**
     * Returns true if the server switched to a different protocol than HTTP/1.0 or HTTP/1.1, e.g. HTTP/2 or Websocket.
     * Returns false if the upgrade happened in a different layer, e.g. upgrade from HTTP/1.1 to HTTP/1.1 over TLS.
     */
    protected boolean isSwitchingToNonHttp1Protocol(HttpResponse msg) {
        if (msg.status().code() != HttpResponseStatus.SWITCHING_PROTOCOLS.code()) {
            return false;
        }
        CharSequence newProtocol = msg.headers().get(HttpHeaderNames.UPGRADE);
        return newProtocol == null ||
               !AsciiString.contains(newProtocol, HttpVersion.HTTP_1_0.text()) &&
               !AsciiString.contains(newProtocol, HttpVersion.HTTP_1_1.text());
    }

    /**
     * Resets the state of the decoder so that it is ready to decode a new message.
     * This method is useful for handling a rejected request with {@code Expect: 100-continue} header.
     */
    public void reset() {
        resetRequested.lazySet(true);
    }

    private void resetNow() {
        message = null;
        name = null;
        value = null;
        contentLength = Long.MIN_VALUE;
        chunked = false;
        lineParser.reset();
        headerParser.reset();
        trailer = null;
        if (isSwitchingToNonHttp1Protocol) {
            isSwitchingToNonHttp1Protocol = false;
            currentState = State.UPGRADED;
            return;
        }

        resetRequested.lazySet(false);
        currentState = State.SKIP_CONTROL_CHARS;
    }

    private HttpMessage invalidMessage(ChannelHandlerContext ctx, HttpMessage current, Buffer in, Exception cause) {
        // Advance the readerIndex so that ByteToMessageDecoder does not complain
        // when we produced an invalid message without consuming anything.
        in.skipReadableBytes(in.readableBytes());
        return invalidMessage(ctx, current, cause);
    }

    private HttpMessage invalidMessage(ChannelHandlerContext ctx, HttpMessage current, Exception cause) {
        currentState = State.BAD_MESSAGE;
        message = null;
        trailer = null;

        if (current == null) {
            current = createInvalidMessage(ctx);
        }
        current.setDecoderResult(DecoderResult.failure(cause));
        return current;
    }

    private HttpContent<?> invalidChunk(BufferAllocator allocator, Buffer in, Exception cause) {
        currentState = State.BAD_MESSAGE;

        // Advance the readerIndex so that ByteToMessageDecoder does not complain
        // when we produced an invalid message without consuming anything.
        in.skipReadableBytes(in.readableBytes());

        HttpContent<?> chunk = new DefaultLastHttpContent(allocator.allocate(0));
        chunk.setDecoderResult(DecoderResult.failure(cause));
        message = null;
        trailer = null;
        return chunk;
    }

    private State readHeaders(Buffer buffer) {
        final HttpMessage message = this.message;
        final HttpHeaders headers = message.headers();

        AppendableCharSequence line = headerParser.parse(buffer);
        if (line == null) {
            return null;
        }
        if (line.length() > 0) {
            do {
                char firstChar = line.charAtUnsafe(0);
                if (name != null && (firstChar == ' ' || firstChar == '\t')) {
                    String trimmedLine = line.toString().trim();
                    String valueStr = String.valueOf(value);
                    value = valueStr + ' ' + trimmedLine;
                } else {
                    if (name != null) {
                        headers.add(name, value);
                    }
                    splitHeader(line);
                }

                line = headerParser.parse(buffer);
                if (line == null) {
                    return null;
                }
            } while (line.length() > 0);
        }

        // Add the last header.
        if (name != null) {
            headers.add(name, value);
        }

        // reset name and value fields
        name = null;
        value = null;

        // Done parsing initial line and headers. Set decoder result.
        HttpMessageDecoderResult decoderResult = new HttpMessageDecoderResult(lineParser.size, headerParser.size);
        message.setDecoderResult(decoderResult);

        Iterator<CharSequence> contentLengthFields = headers.valuesIterator(HttpHeaderNames.CONTENT_LENGTH);
        boolean hasContentLength = contentLengthFields.hasNext();
        if (hasContentLength) {
            HttpVersion version = message.protocolVersion();
            boolean isHttp10OrEarlier = version.majorVersion() < 1 ||
                                        (version.majorVersion() == 1 && version.minorVersion() == 0);
            // Guard against multiple Content-Length headers as stated in
            // https://tools.ietf.org/html/rfc7230#section-3.3.2:
            contentLength = HttpUtil.normalizeAndGetContentLength(contentLengthFields,
                    isHttp10OrEarlier, allowDuplicateContentLengths);
            if (contentLength != -1) {
                headers.set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(contentLength));
            }
        } else {
            contentLength = -1;
        }

        if (!isDecodingRequest() && message instanceof HttpResponse) {
            HttpResponse res = (HttpResponse) message;
            this.isSwitchingToNonHttp1Protocol = isSwitchingToNonHttp1Protocol(res);
        }

        if (isContentAlwaysEmpty(message)) {
            HttpUtil.setTransferEncodingChunked(message, false);
            return State.SKIP_CONTROL_CHARS;
        }
        if (HttpUtil.isTransferEncodingChunked(message)) {
            this.chunked = true;
            if (hasContentLength && message.protocolVersion() == HttpVersion.HTTP_1_1) {
                handleTransferEncodingChunkedWithContentLength(message);
            }
            return State.READ_CHUNK_SIZE;
        }
        if (contentLength >= 0) {
            return State.READ_FIXED_LENGTH_CONTENT;
        }
        return State.READ_VARIABLE_LENGTH_CONTENT;
    }

    /**
     * Invoked when a message with both a "Transfer-Encoding: chunked" and a "Content-Length" header field is detected.
     * The default behavior is to <i>remove</i> the Content-Length field, but this method could be overridden
     * to change the behavior (to, e.g., throw an exception and produce an invalid message).
     * <p>
     * See: <a href="https://tools.ietf.org/html/rfc7230#section-3.3.3">RFC 7230 section-3.3.3</a>:
     * <pre>
     *     If a message is received with both a Transfer-Encoding and a
     *     Content-Length header field, the Transfer-Encoding overrides the
     *     Content-Length.  Such a message might indicate an attempt to
     *     perform request smuggling (Section 9.5) or response splitting
     *     (Section 9.4) and ought to be handled as an error.  A sender MUST
     *     remove the received Content-Length field prior to forwarding such
     *     a message downstream.
     * </pre>
     * Also see:
     * https://github.com/apache/tomcat/blob/b693d7c1981/java/org/apache/coyote/http11/Http11Processor.java#L747-L755
     * https://github.com/nginx/nginx/blob/0ad4393e30c11/src/http/ngx_http_request.c#L1946-L1953
     */
    protected void handleTransferEncodingChunkedWithContentLength(HttpMessage message) {
        message.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
        contentLength = Long.MIN_VALUE;
    }

    private LastHttpContent<?> readTrailingHeaders(BufferAllocator allocator, Buffer buffer) {
        AppendableCharSequence line = headerParser.parse(buffer);
        if (line == null) {
            return null;
        }
        LastHttpContent<?> trailer = this.trailer;
        if (line.length() == 0 && trailer == null) {
            // We have received the empty line which signals the trailer is complete and did not parse any trailers
            // before. Just return an empty last content to reduce allocations.
            return new EmptyLastHttpContent(allocator);
        }

        CharSequence lastHeader = null;
        if (trailer == null) {
            trailer = this.trailer = new DefaultLastHttpContent(allocator.allocate(0), trailersFactory);
        }
        while (line.length() > 0) {
            char firstChar = line.charAtUnsafe(0);
            if (lastHeader != null && (firstChar == ' ' || firstChar == '\t')) {
                Iterator<CharSequence> itr = trailer.trailingHeaders().valuesIterator(lastHeader);
                CharSequence last = null;
                while (itr.hasNext()) {
                    last = itr.next();
                }
                if (last != null) {
                    itr.remove();
                    //please do not make one line from below code
                    //as it breaks +XX:OptimizeStringConcat optimization
                    String lineTrimmed = line.toString().trim();
                    trailer.trailingHeaders().add(lastHeader, last + lineTrimmed);
                }
            } else {
                splitHeader(line);
                CharSequence headerName = name;
                if (!HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(headerName) &&
                        !HttpHeaderNames.TRANSFER_ENCODING.contentEqualsIgnoreCase(headerName) &&
                        !HttpHeaderNames.TRAILER.contentEqualsIgnoreCase(headerName)) {
                    trailer.trailingHeaders().add(headerName, value);
                }
                lastHeader = name;
                // reset name and value fields
                name = null;
                value = null;
            }
            line = headerParser.parse(buffer);
            if (line == null) {
                return null;
            }
        }

        this.trailer = null;
        return trailer;
    }

    protected abstract boolean isDecodingRequest();
    protected abstract HttpMessage createMessage(String[] initialLine) throws Exception;
    protected abstract HttpMessage createInvalidMessage(ChannelHandlerContext ctx);

    private static int getChunkSize(String hex) {
        hex = hex.trim();
        for (int i = 0; i < hex.length(); i ++) {
            char c = hex.charAt(i);
            if (c == ';' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                hex = hex.substring(0, i);
                break;
            }
        }

        return Integer.parseInt(hex, 16);
    }

    private static String[] splitInitialLine(AppendableCharSequence sb) {
        int aStart;
        int aEnd;
        int bStart;
        int bEnd;
        int cStart;
        int cEnd;
        byte lastByte = (byte) sb.charAt(sb.length() - 1);
        if (isControlOrWhitespaceAsciiChar(lastByte)) {
            // There should no extra control or whitespace char.
            // See https://datatracker.ietf.org/doc/html/rfc2616#section-5.1
            throw new IllegalArgumentException("Illegal character in request line: 0x" + Integer.toHexString(lastByte));
        }

        aStart = findNonSPLenient(sb, 0);
        aEnd = findSPLenient(sb, aStart);

        bStart = findNonSPLenient(sb, aEnd);
        bEnd = findSPLenient(sb, bStart);

        cStart = findNonSPLenient(sb, bEnd);
        cEnd = findEndOfString(sb);

        return new String[] {
                sb.subStringUnsafe(aStart, aEnd),
                sb.subStringUnsafe(bStart, bEnd),
                cStart < cEnd? sb.subStringUnsafe(cStart, cEnd) : "" };
    }

    private void splitHeader(AppendableCharSequence sb) {
        final int length = sb.length();
        int nameStart = 0;
        int nameEnd;
        int colonEnd;
        int valueStart;
        int valueEnd;

        for (nameEnd = nameStart; nameEnd < length; nameEnd ++) {
            char ch = sb.charAtUnsafe(nameEnd);
            // https://tools.ietf.org/html/rfc7230#section-3.2.4
            //
            // No whitespace is allowed between the header field-name and colon. In
            // the past, differences in the handling of such whitespace have led to
            // security vulnerabilities in request routing and response handling. A
            // server MUST reject any received request message that contains
            // whitespace between a header field-name and colon with a response code
            // of 400 (Bad Request). A proxy MUST remove any such whitespace from a
            // response message before forwarding the message downstream.
            if (ch == ':' ||
                    // In case of decoding a request we will just continue processing and header validation
                    // is done in the DefaultHttpHeaders implementation.
                    //
                    // In the case of decoding a response we will "skip" the whitespace.
                !isDecodingRequest() && isOWS(ch)) {
                break;
            }
        }

        if (nameEnd == length) {
            // There was no colon present at all.
            throw new IllegalArgumentException("No colon found");
        }

        for (colonEnd = nameEnd; colonEnd < length; colonEnd ++) {
            if (sb.charAtUnsafe(colonEnd) == ':') {
                colonEnd ++;
                break;
            }
        }

        name = sb.subStringUnsafe(nameStart, nameEnd);
        valueStart = findNonWhitespace(sb, colonEnd);
        if (valueStart == length) {
            value = EMPTY_VALUE;
        } else {
            valueEnd = findEndOfString(sb);
            value = sb.subStringUnsafe(valueStart, valueEnd);
        }
    }

    private static int findNonSPLenient(AppendableCharSequence sb, int offset) {
        for (int result = offset; result < sb.length(); ++result) {
            char c = sb.charAtUnsafe(result);
            // See https://tools.ietf.org/html/rfc7230#section-3.5
            if (isSPLenient(c)) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                // Any other whitespace delimiter is invalid
                throw new IllegalArgumentException("Invalid separator");
            }
            return result;
        }
        return sb.length();
    }

    private static int findSPLenient(AppendableCharSequence sb, int offset) {
        for (int result = offset; result < sb.length(); ++result) {
            if (isSPLenient(sb.charAtUnsafe(result))) {
                return result;
            }
        }
        return sb.length();
    }

    private static boolean isSPLenient(char c) {
        // See https://tools.ietf.org/html/rfc7230#section-3.5
        return c == ' ' || c == (char) 0x09 || c == (char) 0x0B || c == (char) 0x0C || c == (char) 0x0D;
    }

    private static int findNonWhitespace(AppendableCharSequence sb, int offset) {
        for (int result = offset; result < sb.length(); ++result) {
            char c = sb.charAtUnsafe(result);
            if (!Character.isWhitespace(c)) {
                return result;
            } else if (!isOWS(c)) {
                // Only OWS is supported for whitespace
                throw new IllegalArgumentException("Invalid separator, only a single space or horizontal tab allowed," +
                                                   " but received a '" + c + "' (0x" + Integer.toHexString(c) + ')');
            }
        }
        return sb.length();
    }

    private static int findEndOfString(AppendableCharSequence sb) {
        for (int result = sb.length() - 1; result > 0; --result) {
            if (!isOWS(sb.charAtUnsafe(result))) {
                return result + 1;
            }
        }
        return 0;
    }

    private static boolean isOWS(char ch) {
        return ch == ' ' || ch == (char) 0x09;
    }

    private static class HeaderParser implements ByteProcessor {
        private final AppendableCharSequence seq;
        private final int maxLength;
        int size;

        HeaderParser(AppendableCharSequence seq, int maxLength) {
            this.seq = seq;
            this.maxLength = maxLength;
        }

        public AppendableCharSequence parse(Buffer buffer) {
            final int oldSize = size;
            seq.reset();
            int i = buffer.openCursor().process(this);
            if (i == -1) {
                size = oldSize;
                return null;
            }
            buffer.skipReadableBytes(i + 1);
            return seq;
        }

        public void reset() {
            size = 0;
        }

        @Override
        public boolean process(byte value) {
            char nextByte = (char) (value & 0xFF);
            if (nextByte == HttpConstants.LF) {
                int len = seq.length();
                // Drop CR if we had a CRLF pair
                if (len >= 1 && seq.charAtUnsafe(len - 1) == HttpConstants.CR) {
                    -- size;
                    seq.setLength(len - 1);
                }
                return false;
            }

            increaseCount();

            seq.append(nextByte);
            return true;
        }

        protected final void increaseCount() {
            if (++ size > maxLength) {
                // TODO: Respond with Bad Request and discard the traffic
                //    or close the connection.
                //       No need to notify the upstream handlers - just log.
                //       If decoding a response, just throw an exception.
                throw newException(maxLength);
            }
        }

        protected TooLongFrameException newException(int maxLength) {
            return new TooLongHttpHeaderException("HTTP header is larger than " + maxLength + " bytes.");
        }
    }

    private final class LineParser extends HeaderParser {

        LineParser(AppendableCharSequence seq, int maxLength) {
            super(seq, maxLength);
        }

        @Override
        public AppendableCharSequence parse(Buffer buffer) {
            reset();
            return super.parse(buffer);
        }

        @Override
        public boolean process(byte value) {
            if (currentState == State.SKIP_CONTROL_CHARS) {
                char c = (char) (value & 0xFF);
                if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                    increaseCount();
                    return true;
                }
                currentState = State.READ_INITIAL;
            }
            return super.process(value);
        }

        @Override
        protected TooLongFrameException newException(int maxLength) {
            return new TooLongHttpLineException("An HTTP line is larger than " + maxLength + " bytes.");
        }
    }

    private static final boolean[] ISO_CONTROL_OR_WHITESPACE;

    static {
        ISO_CONTROL_OR_WHITESPACE = new boolean[256];
        for (byte b = Byte.MIN_VALUE; b < Byte.MAX_VALUE; b++) {
            ISO_CONTROL_OR_WHITESPACE[128 + b] = Character.isISOControl(b) || Character.isWhitespace(b);
        }
    }

    private static final ByteProcessor SKIP_CONTROL_CHARS_BYTES = new ByteProcessor() {

        @Override
        public boolean process(byte value) {
            return ISO_CONTROL_OR_WHITESPACE[128 + value];
        }
    };

    private static boolean isControlOrWhitespaceAsciiChar(byte b) {
        return ISO_CONTROL_OR_WHITESPACE[128 + b];
    }
}
