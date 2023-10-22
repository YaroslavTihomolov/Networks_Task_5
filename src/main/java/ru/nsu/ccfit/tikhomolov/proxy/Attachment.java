package ru.nsu.ccfit.tikhomolov.proxy;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Attachment {
    public Attachment(SelectionKey key, InetAddress inetAddress) {
        this.peerKey = key;
        this.inetAddress = inetAddress;
    }

    private SelectionKey peerKey;
    private InetAddress inetAddress;
    private ByteBuffer buffer = ByteBuffer.allocate(8192);
    private int step = 0;
}
