package ru.nsu.ccfit.tikhomolov;


import ru.nsu.ccfit.tikhomolov.proxy.ProxyServer;

public class Main {
    public static void main(String[] args) {
        short port = 1235;
        try (ProxyServer proxyServer = new ProxyServer(port)) {
            proxyServer.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}