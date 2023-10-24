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
import java.util.Arrays;
import java.util.Iterator;

@Slf4j
public class ProxyServer implements AutoCloseable {
    public static final byte SOCKS_VERSION = 0x05;
    public static final byte CONNECT_SUCCESS = 0x00;
    public static final byte RSV = 0x00;
    public static final byte ATYP_IPV4 = 0x1;
    private final Selector selector;
    private final ServerSocketChannel serverSocket;
    private final short port;

    public ProxyServer(short port) throws IOException {
        this.port = port;
        this.selector = SelectorProvider.provider().openSelector();
        this.serverSocket = ServerSocketChannel.open();
        this.serverSocket.configureBlocking(false);
        this.serverSocket.socket().bind(new InetSocketAddress(InetAddress.getByName("localhost"), port));
        this.serverSocket.register(selector, serverSocket.validOps());
        log.info("Proxy server ready");
    }

    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (selector.select() <= -1) {
                    break;
                }

                Iterator<SelectionKey> iter = selector.selectedKeys().iterator();

                while (iter.hasNext()) {

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
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void read(SelectionKey key) {
        SocketChannel channel = ((SocketChannel) key.channel());
        try {
            Attachment attachment = (Attachment) key.attachment();
            if (attachment == null) {
                attachment = new Attachment();
                attachment.setInetAddress(InetAddress.getLoopbackAddress());
            }
            int count = channel.read(attachment.getBuffer());

            if (count == -1 || count == 0) return;
            if (attachment.getBuffer().get(0) == SOCKS_VERSION && attachment.getStep() == 0) {
                handleConnectionMessage(attachment, channel, key);
                attachment.getBuffer().flip();
                attachment.getBuffer().clear();
            } else if (attachment.getBuffer().get(0) == SOCKS_VERSION) {
                handleRequestMessage(attachment.getBuffer(), count, key);
                attachment.getBuffer().flip();
                attachment.getBuffer().clear();
            } else {
                saveData(attachment, key);
                attachment.getBuffer().flip();
            }
        } catch (IOException ignored) {
        }

    }

    private void saveData(Attachment attachment, SelectionKey key) {
        key.interestOps(key.interestOps() ^ SelectionKey.OP_READ);
        SelectionKey peerKey = attachment.getPeerKey();
        peerKey.interestOps(peerKey.interestOps() | SelectionKey.OP_WRITE);
    }

    private void handleConnectionMessage(Attachment header, SocketChannel channel, SelectionKey key) throws IOException {
        ByteBuffer answer = ByteBuffer.allocate(2);

        answer.put(header.getBuffer().get(0));
        answer.put(header.getBuffer().get(2));

        answer.flip();
        channel.write(answer);

        header.setStep(1);
        key.attach(header);
    }

    private void handleRequestMessage(ByteBuffer header, int readBytes, SelectionKey key) throws IOException {
        SocksRequestHeader socksHeader = parseHeader(header, readBytes);
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(false);
        channel.connect(new InetSocketAddress(socksHeader.address(), socksHeader.port()));

        SelectionKey peerKey = channel.register(selector, SelectionKey.OP_CONNECT);
        key.interestOps(0);

        Attachment attachment = (Attachment) key.attachment();
        attachment.setPeerKey(peerKey);
        peerKey.attach(new Attachment(key, socksHeader.address()));
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
        channel.write(request);
    }

    private SocksRequestHeader parseHeader(ByteBuffer buffer, int messageLength) throws UnknownHostException {
        byte[] ip = Arrays.copyOfRange(buffer.array(), 5, messageLength - 2);
        short portShort = (short) (((buffer.get(messageLength - 2) & 0xFF) << 8) | (buffer.get(messageLength - 1) & 0xFF));
        InetAddress inetAddress = InetAddress.getByName(new String(ip));
        return new SocksRequestHeader(buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3), inetAddress, portShort);
    }

    private void write(SelectionKey key) throws IOException {
        Attachment attachment = (Attachment) key.attachment();
        if (!attachment.getBuffer().hasRemaining()) {
            return;
        }

        SocketChannel channel = (SocketChannel) key.channel();
        Attachment peerAttachment = (Attachment) attachment.getPeerKey().attachment();
        channel.write(peerAttachment.getBuffer());

        peerAttachment.getBuffer().flip();
        peerAttachment.getBuffer().clear();

        key.interestOps(SelectionKey.OP_READ);
        attachment.getPeerKey().interestOps(SelectionKey.OP_READ);
    }

    private void connect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        channel.finishConnect();

        Attachment attachment = (Attachment) key.attachment();
        sendRequest(attachment.getPeerKey());

        SelectionKey peerKey = attachment.getPeerKey();
        peerKey.interestOps(SelectionKey.OP_READ);
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

    @Override
    public void close() throws Exception {
        selector.close();
        serverSocket.close();
    }
}
