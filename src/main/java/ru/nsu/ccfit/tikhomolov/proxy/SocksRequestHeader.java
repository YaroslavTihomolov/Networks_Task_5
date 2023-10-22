package ru.nsu.ccfit.tikhomolov.proxy;

import java.net.InetAddress;

public record SocksRequestHeader(byte version, byte rep, byte rsv, byte atyp, InetAddress address, short port) {
}
