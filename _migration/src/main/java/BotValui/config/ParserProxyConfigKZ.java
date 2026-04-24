package BotValui.config;

import org.springframework.stereotype.Component;

@Component
public class ParserProxyConfigKZ implements ParserProxyConfig {
    private static String proxyHost = "91.147.122.69";
    private static int proxyPort = 4232;
    private static String proxyUser = "user283146";
    private static String proxyPass = "0w3qzb";

    public String getProxyHost() {
        return proxyHost;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public String getProxyUser() {
        return proxyUser;
    }

    public String getProxyPass() {
        return proxyPass;
    }

    public boolean isProxyEnabled() {
        return proxyHost != null
                && !proxyHost.isEmpty()
                && proxyPort > 0;
    }
}
