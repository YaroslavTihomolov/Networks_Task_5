package ru.nsu.ccfit.tikhomolov.proxy;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Attachment {
    private static final int BUFFER_SIZE = 8192;

    public Attachment(SelectionKey key) {
        this.peerKey = key;
    }

    private SelectionKey peerKey;
    private ByteBuffer in;
    private ByteBuffer out;
    private int step = 0;
    private DatagramChannel datagramChannel;
    private SocksRequestHeader socksRequestHeader;
}
