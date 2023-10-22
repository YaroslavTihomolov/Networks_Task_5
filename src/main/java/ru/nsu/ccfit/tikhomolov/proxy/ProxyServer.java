package ru.nsu.ccfit.tikhomolov.proxy;

import lombok.extern.slf4j.Slf4j;
import ru.nsu.ccfit.tikhomolov.exceptions.RegisterException;
import ru.nsu.ccfit.tikhomolov.exceptions.SelectException;

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
    private static final int BUFFER_SIZE = 8192;
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

        try {
            while (selector.select() > -1) {

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
            }
        } catch (IOException e) {
            throw new SelectException(e.getMessage());
        }
    }

    private void read(SelectionKey key) {
        SocketChannel channel = ((SocketChannel) key.channel());
        try {
            Attachment attachment = (Attachment) key.attachment();
            if (attachment == null) {
                attachment = new Attachment();
            }
            int count = channel.read(attachment.getBuffer());
            log.info("read: " + Arrays.toString(attachment.getBuffer().array()));
            if (count == -1 || count == 0) return;
            if (attachment.getBuffer().get(0) == 0x05 && attachment.getStep() == 0) {
                handleConnectionMessage(count, attachment, channel, key);
                attachment.getBuffer().flip();
                attachment.getBuffer().clear();
            } else if (attachment.getBuffer().get(0) == 0x05) {
                handleRequestMessage(attachment.getBuffer(), count, key);
                attachment.getBuffer().flip();
                attachment.getBuffer().clear();
            } else {
                saveData(attachment, key);
                attachment.getBuffer().flip();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private void saveData(Attachment attachment, SelectionKey key) {
        log.info("Save data");
        key.interestOps(0);
        attachment.getPeerKey().interestOps(SelectionKey.OP_WRITE);
    }

    private void handleConnectionMessage(int readBytes, Attachment header, SocketChannel channel, SelectionKey key) throws IOException {
        ByteBuffer answer = ByteBuffer.allocate(2);

        if (header.getBuffer().get(0) != 0)
            log.info("read" + " " + readBytes + " " + Arrays.toString(header.getBuffer().array()));
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
        if (socksHeader.address().getHostName().equals("www.youtube.com")) {
            log.info("ip: " + socksHeader.address().getHostAddress() + " port: " + socksHeader.port());
        }
        SelectionKey peerKey = channel.register(selector, SelectionKey.OP_CONNECT);
        key.interestOps(0);
        Attachment attachment = (Attachment) key.attachment();
        attachment.setPeerKey(peerKey);
        peerKey.attach(new Attachment(key, socksHeader.address()));
    }

    private void sendRequest(boolean connectStatus, SelectionKey key) throws IOException {
        ByteBuffer request = ByteBuffer.allocate(6 + InetAddress.getLoopbackAddress().getAddress().length);
        request.put((byte) 0x05);
        request.put((byte) (connectStatus ? 0x00 : 0x05));
        request.put((byte) 0x00);
        request.put((byte) 0x1);
        request.put(InetAddress.getLoopbackAddress().getAddress());
        request.putShort(port);
        SocketChannel channel = (SocketChannel) key.channel();
        request.flip();
        channel.write(request);
    }

    private SocksRequestHeader parseHeader(ByteBuffer buffer, int messageLength) throws UnknownHostException {
        byte[] peerPort = Arrays.copyOfRange(buffer.array(), messageLength - 2, messageLength);
        byte[] ip = Arrays.copyOfRange(buffer.array(), 5, messageLength - 2);
        short portShort = (short) (((buffer.get(messageLength - 2) & 0xFF) << 8) | (buffer.get(messageLength - 1) & 0xFF));
        //int portValue = Short.toUnsignedInt(portShort);
        InetAddress inetAddress = InetAddress.getByName(new String(ip));
        return new SocksRequestHeader(buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3), inetAddress, portShort);
    }

    private void write(SelectionKey key) throws IOException {
        Attachment attachment = (Attachment) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();
        Attachment peerAttachment = (Attachment) attachment.getPeerKey().attachment();
        log.info("write: " + Arrays.toString(peerAttachment.getBuffer().array()));
        if (channel.write(peerAttachment.getBuffer()) == -1) {
            closeKey(key);
        } else if (attachment.getBuffer().remaining() == 0) {
            if (attachment.getPeerKey() == null) {
                closeKey(key);
            } else {
                peerAttachment.getBuffer().clear();
                key.interestOps(SelectionKey.OP_READ);
                attachment.getPeerKey().interestOps(0);
            }
        }
        /*SocketChannel channel = ((SocketChannel) key.channel());
        ByteBuffer header = ByteBuffer.allocate(BUFFER_SIZE);
        try {
            int count = channel.read(header);
            log.info("count: " + count);
            if (header.get(0) != 0) log.info("write" + " " + count + " " + Arrays.toString(header.array()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }*/
        //int methodsCount = inputStream.read();

    }

    private void connect(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        channel.finishConnect();
        Attachment attachment = (Attachment) key.attachment();
        log.info("Connect " + attachment.getInetAddress().getHostName());
        sendRequest(true, attachment.getPeerKey());
        attachment.getPeerKey().interestOps(SelectionKey.OP_READ);
        key.interestOps(0);
        /*SocketChannel channel = ((SocketChannel)key.channel());
        key.attachment();*/
    }

    private void register(SelectionKey key) {
        try {
            log.info("Register");
            SocketChannel client = ((ServerSocketChannel) key.channel()).accept();
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ);
        } catch (IOException e) {
            throw new RegisterException(e.getMessage());
        }
    }

    private void closeKey(SelectionKey key) throws IOException {
        key.cancel();
        key.channel().close();
        SelectionKey peerKey = ((Attachment) key.attachment()).getPeerKey();
        if (peerKey != null) {
            ((Attachment) peerKey.attachment()).setPeerKey(null);
            if ((peerKey.interestOps() & SelectionKey.OP_WRITE) == 0) {
                ((Attachment) peerKey.attachment()).getBuffer().flip();
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
