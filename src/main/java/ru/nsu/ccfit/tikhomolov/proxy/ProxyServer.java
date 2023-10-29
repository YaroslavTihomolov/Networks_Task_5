package ru.nsu.ccfit.tikhomolov.proxy;

import lombok.extern.slf4j.Slf4j;
import ru.nsu.ccfit.tikhomolov.exceptions.RegisterException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;

@Slf4j
public class ProxyServer implements AutoCloseable {
    private static final int BUFFER_SIZE = 8192;
    public static final byte SOCKS_VERSION = 0x05;
    public static final byte CONNECT_SUCCESS = 0x00;
    public static final byte RSV = 0x00;
    public static final byte ATYP_IPV4 = 0x1;
    private final Selector selector;
    private final ServerSocketChannel serverSocket;
    private final short port;
    private final ByteBuffer answer = ByteBuffer.allocate(2);
    public static final byte[] CONNECTION_OK_REPLY = new byte[]{
            SOCKS_VERSION,
            CONNECT_SUCCESS,
            0x00,
            ATYP_IPV4,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00
    };

    public ProxyServer(short port) throws IOException {
        this.port = port;
        this.selector = SelectorProvider.provider().openSelector();
        this.serverSocket = ServerSocketChannel.open();
        this.serverSocket.configureBlocking(false);
        this.serverSocket.socket().bind(new InetSocketAddress(InetAddress.getByName("localhost"), port));
        this.serverSocket.register(selector, serverSocket.validOps());
        log.info("Proxy server ready");
    }

    public void run() throws IOException {
        while (!Thread.currentThread().isInterrupted()) {
            if (selector.select() <= -1) {
                break;
            }

            Iterator<SelectionKey> iter = selector.selectedKeys().iterator();

            while (iter.hasNext()) {
                try {

                    SelectionKey key = iter.next();
                    iter.remove();

                    if (key.isAcceptable()) {
                        register(key);
                    } else if (key.isConnectable()) {
                        connect(key);
                    } else if (key.isReadable()) {
                        read(key);
                    } else if (key.isWritable()) {
                        write(key);
                    }

                } catch (IOException ignored) {
                    //log.error(ignored.getMessage());
                }
            }
        }
    }

    private void read(SelectionKey key) {
        SocketChannel channel = ((SocketChannel) key.channel());
        try {
            Attachment attachment = (Attachment) key.attachment();
            if (attachment == null) {
                attachment = new Attachment();
                attachment.setIn(ByteBuffer.allocate(BUFFER_SIZE));
                key.attach(attachment);
            }

            int count = channel.read(attachment.getIn());
            //log.info("Read from: " + channel.getRemoteAddress() + " to: " + channel.getLocalAddress() + " data: " + Arrays.toString(attachment.getIn().array()));            //log.info("Read data:");

            if (count < 1) {
                //closeKey(key);
                return;
            } else if (attachment.getIn().get(0) == SOCKS_VERSION && attachment.getStep() == 0) {
                handleConnectionMessage(attachment, channel, key);
            } else if (attachment.getIn().get(0) == SOCKS_VERSION && attachment.getStep() == 1) {
                handleRequestMessage(attachment.getIn(), count, key);
            } else {
                saveData(attachment, key);
            }
        } catch (IOException ignored) {
            //log.warn(new String(ignored.getMessage().getBytes(StandardCharsets.UTF_8)), ignored);
            //log.error(ignored.getMessage());
        }

    }

    private void saveData(Attachment attachment, SelectionKey key) {
        SelectionKey peerKey = attachment.getPeerKey();
        key.interestOps(key.interestOps() ^ SelectionKey.OP_READ);
        peerKey.interestOps(peerKey.interestOps() | SelectionKey.OP_WRITE);
        attachment.getIn().flip();
    }

    private void handleConnectionMessage(Attachment header, SocketChannel channel, SelectionKey key) throws IOException {

        answer.put(header.getIn().get(0));
        answer.put(header.getIn().get(2));

        answer.flip();

        //answer.compact();
        channel.write(answer);
        header.setStep(1);

        answer.clear();

        header.getIn().flip();
        header.getIn().clear();
    }

    private void handleRequestMessage(ByteBuffer header, int readBytes, SelectionKey key) throws IOException {
        SocksRequestHeader socksHeader = parseHeader(header, readBytes);
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(false);
        channel.connect(new InetSocketAddress(socksHeader.address(), socksHeader.port()));

        SelectionKey peerKey = channel.register(selector, SelectionKey.OP_CONNECT);
        key.interestOps(0);

        Attachment attachment = (Attachment) key.attachment();
        attachment.setStep(2);
        attachment.setPeerKey(peerKey);

        Attachment peerAttachment = new Attachment(key);
        peerAttachment.setPeerKey(key);

        peerAttachment.setIn(ByteBuffer.allocate(BUFFER_SIZE));

        attachment.setOut(peerAttachment.getIn());
        peerAttachment.setOut(attachment.getIn());

        peerKey.attach(peerAttachment);

        attachment.getIn().flip();
        attachment.getIn().clear();
    }

    private void sendRequest(SelectionKey key) throws IOException {
        ByteBuffer request = ByteBuffer.allocate(6 + InetAddress.getLoopbackAddress().getAddress().length);
        request.put(SOCKS_VERSION);
        request.put(CONNECT_SUCCESS);
        request.put(RSV);
        request.put(ATYP_IPV4);
        request.put(InetAddress.getLoopbackAddress().getAddress());
        request.putShort(port);
        SocketChannel channel = (SocketChannel) key.channel();
        request.flip();

        //request.compact();
        channel.write(request);
    }

    private SocksRequestHeader parseHeader(ByteBuffer buffer, int messageLength) throws UnknownHostException {
        byte atyp = buffer.get(3);
        byte[] ip;
        InetAddress inetAddress;
        if (atyp == 0x03) {
            ip = Arrays.copyOfRange(buffer.array(), 5, messageLength - 2);
            inetAddress = InetAddress.getByName(new String(ip));
        } else {
            ip = Arrays.copyOfRange(buffer.array(), 4, messageLength - 2);
            inetAddress = InetAddress.getByAddress(ip);
        }

        short portShort = (short) (((buffer.get(messageLength - 2) & 0xFF) << 8) | (buffer.get(messageLength - 1) & 0xFF));
        return new SocksRequestHeader(buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3), inetAddress, portShort);
    }

    private void write(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Attachment attachment = (Attachment) key.attachment();
        if (!attachment.getOut().hasRemaining()) {
            return;
        }

        //peerAttachment.getBuffer().compact();
        //peerAttachment.getBuffer().flip();
        //log.info("Write from: " + channel.getLocalAddress() + " to: " + channel.getRemoteAddress() + " data: " + Arrays.toString(attachment.getOut().array()));

        int writtenBytes = channel.write(attachment.getOut());

        if (writtenBytes == -1) {
            //closeKey(key);
            return;
        }

        attachment.getOut().flip();
        attachment.getOut().clear();

        attachment.getPeerKey().interestOps(attachment.getPeerKey().interestOps() | SelectionKey.OP_READ);
        key.interestOps(SelectionKey.OP_READ);
    }

    private void connect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        channel.finishConnect();

        Attachment attachment = (Attachment) key.attachment();

        //sendRequest(attachment.getPeerKey());
        attachment.getIn().put(CONNECTION_OK_REPLY).flip();

        SelectionKey peerKey = attachment.getPeerKey();
        peerKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        key.interestOps(0);
    }

    private void register(SelectionKey key) {
        try {
            SocketChannel client = ((ServerSocketChannel) key.channel()).accept();
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ);
        } catch (IOException e) {
            throw new RegisterException(e.getMessage());
        }
    }

    private void closeKey(SelectionKey key) throws IOException {
        log.info("Close key");
        key.cancel();
        key.channel().close();
        SelectionKey peerKey = ((Attachment) key.attachment()).getPeerKey();
        if (peerKey != null) {
            ((Attachment) peerKey.attachment()).setPeerKey(null);
            if ((peerKey.interestOps() & SelectionKey.OP_WRITE) == 0) {
                ((Attachment) peerKey.attachment()).getOut().flip();
            }
            peerKey.interestOps(SelectionKey.OP_WRITE);
        }
    }

    @Override
    public void close() throws Exception {
        selector.close();
        serverSocket.close();
    }
}
