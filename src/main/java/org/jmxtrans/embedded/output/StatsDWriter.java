package org.jmxtrans.embedded.output;

import org.jmxtrans.embedded.QueryResult;
import org.jmxtrans.embedded.util.CachingReference;
import org.jmxtrans.embedded.util.StringUtils2;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * @author Fabiano Vicente
 * @author <a href="mailto:cleclerc@cloudbees.com">Cyrille Le Clerc</a>
 */
public class StatsDWriter extends AbstractOutputWriter implements OutputWriter {

    public final static String SETTING_BUFFER_SIZE = "bufferSize";
    private final static int SETTING_DEFAULT_BUFFER_SIZE = 1024;
    public static final String DEFAULT_NAME_PREFIX = "servers.#escaped_hostname#.";

    private ByteBuffer sendBuffer;
    /**
     * Using a {@link CachingReference} instead of a raw {@link InetSocketAddress} allows to handle a change
     */
    private CachingReference<InetSocketAddress> addressReference;
    private DatagramChannel channel;

    /**
     * Metric path prefix. Ends with "." if not empty;
     */
    private String metricPathPrefix;

    @Override
    public void start() {
        final String host = getStringSetting(SETTING_HOST);
        final Integer port = getIntSetting(SETTING_PORT);
        metricPathPrefix = getStringSetting(SETTING_NAME_PREFIX, DEFAULT_NAME_PREFIX);
        metricPathPrefix = getStrategy().resolveExpression(metricPathPrefix);
        if (!metricPathPrefix.isEmpty() && !metricPathPrefix.endsWith(".")) {
            metricPathPrefix = metricPathPrefix + ".";
        }

        int bufferSize = getIntSetting(SETTING_BUFFER_SIZE, SETTING_DEFAULT_BUFFER_SIZE);
        sendBuffer = ByteBuffer.allocate(bufferSize);

        addressReference = new CachingReference<InetSocketAddress>(30, TimeUnit.SECONDS) {
            @Nonnull
            @Override
            protected InetSocketAddress newObject() {
                return new InetSocketAddress(host, port);
            }
        };
        try {
            channel = DatagramChannel.open();
        } catch (IOException e) {
            throw new RuntimeException("Exception opening datagram channel", e);
        }

        logger.info("Started {}", this);
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        channel.close();
    }

    @Override
    public synchronized void write(Iterable<QueryResult> results) {
        logger.debug("Export to {} results {}", addressReference.get(), results);
        for (QueryResult result : results) {
            String stat = metricPathPrefix + result.getName() + ":" + result.getValue() + "|c\n";

            logger.debug("Export '{}'", stat);
            final byte[] data = stat.getBytes(StandardCharsets.UTF_8);

            // If we're going to go past the threshold of the buffer then flush.
            // the +1 is for the potential '\n' in multi_metrics below
            if (sendBuffer.remaining() < (data.length + 1)) {
                flush();
            }

            if (sendBuffer.remaining() < (data.length + 1)) {
                logger.warn("Given data too big (" + data.length + "bytes) for the buffer size (" + sendBuffer.remaining() + "bytes), skip it: "
                        + StringUtils2.abbreviate(stat, 20));
                continue;
            }

            sendBuffer.put(data); // append the data

        }
        flush();
    }

    public synchronized void flush() {
        InetSocketAddress address = addressReference.get();
        try {
            if (sendBuffer.position() == 0) {
                // empty buffer
                return;
            }

            final int sizeOfBuffer = sendBuffer.position();

            // send and reset the buffer
            sendBuffer.flip();
            final int nbSentBytes = channel.send(sendBuffer, address);
            // why do we need redefine the limit?
            sendBuffer.limit(sendBuffer.capacity());
            sendBuffer.rewind();

            if (sizeOfBuffer != nbSentBytes) {
                logger.warn("Could not send entirely stat {} to host {}:{}. Only sent {} bytes out of {} bytes",
                        sendBuffer, address.getHostName(), address.getPort(), nbSentBytes, sizeOfBuffer);
            }
        } catch (IOException e) {
            addressReference.purge();
            logger.warn("Could not send stat {} to host {}:{}", sendBuffer, address.getHostName(), address.getPort(), e);
        }
    }

    @Override
    public String toString() {
        return "StatsDOutputWriter{" +
                "addressReference=" + addressReference +
                ", metricPathPrefix='" + metricPathPrefix + '\'' +
                ", sendBuffer=" + sendBuffer +
                '}';
    }
}
