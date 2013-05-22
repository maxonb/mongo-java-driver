/*
 * Copyright (c) 2008 - 2013 10gen, Inc. <http://10gen.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.operation;

import org.mongodb.Decoder;
import org.mongodb.Document;
import org.mongodb.Encoder;
import org.mongodb.MongoNamespace;
import org.mongodb.codecs.DocumentCodec;
import org.mongodb.connection.BufferPool;
import org.mongodb.connection.Connection;
import org.mongodb.connection.PooledByteBufferOutputBuffer;
import org.mongodb.connection.ResponseBuffers;
import org.mongodb.connection.Session;
import org.mongodb.operation.protocol.MongoQueryMessage;
import org.mongodb.operation.protocol.MongoReplyMessage;

import java.nio.ByteBuffer;

public class QueryOperation<T> extends Operation {
    private final MongoFind find;
    private final Encoder<Document> queryEncoder;
    private final Decoder<T> resultDecoder;

    public QueryOperation(final MongoNamespace namespace, final MongoFind find, final Encoder<Document> queryEncoder,
                          final Decoder<T> resultDecoder, final BufferPool<ByteBuffer> bufferPool) {
        super(namespace, bufferPool);
        this.find = find;
        this.queryEncoder = queryEncoder;
        this.resultDecoder = resultDecoder;
    }

    public QueryResult<T> execute(final Session session) {
        Connection connection = session.getConnection(new ReadPreferenceServerSelector(find.getReadPreference()));
        try {
            return execute(connection);
        } finally {
            connection.close();
        }
    }

    public QueryResult<T> execute(final Connection connection) {
        final PooledByteBufferOutputBuffer buffer = new PooledByteBufferOutputBuffer(getBufferPool());
        try {
            final MongoQueryMessage message = new MongoQueryMessage(getNamespace().getFullName(), find, queryEncoder);
            message.encode(buffer);

            final ResponseBuffers responseBuffers = connection.sendAndReceiveMessage(buffer);
            try {
                if (responseBuffers.getReplyHeader().isQueryFailure()) {
                    final Document errorDocument =
                            new MongoReplyMessage<Document>(responseBuffers, new DocumentCodec(), message.getId()).getDocuments().get(0);
                    throw new MongoQueryFailureException(connection.getServerAddress(), errorDocument);
                }
                final MongoReplyMessage<T> replyMessage = new MongoReplyMessage<T>(responseBuffers, resultDecoder, message.getId());

                return new QueryResult<T>(replyMessage, connection.getServerAddress());
            } finally {
                responseBuffers.close();
            }
        } finally {
            buffer.close();
        }

    }
}