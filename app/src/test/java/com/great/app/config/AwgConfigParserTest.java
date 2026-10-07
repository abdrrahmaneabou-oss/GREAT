package com.great.app.config;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

public final class AwgConfigParserTest {
    @Test public void preservesAmneziaExtensionFields() throws Exception {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        String text = "[Interface]\nPrivateKey="+key+"\nAddress=10.0.0.2/32\nJc=4\nS1=10\n\n[Peer]\nPublicKey="+key+"\nEndpoint=127.0.0.1:51820\nAllowedIPs=0.0.0.0/0\n";
        AwgConfig config = new AwgConfigParser().parse(text.getBytes(StandardCharsets.UTF_8));
        assertEquals("4", config.interfaceValue("Jc"));
        assertEquals("10", config.interfaceValue("S1"));
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsDuplicateSettings() throws Exception {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        String text = "[Interface]\nPrivateKey="+key+"\nPrivateKey="+key+"\nAddress=10.0.0.2/32\n[Peer]\nPublicKey="+key+"\nEndpoint=x:1\nAllowedIPs=0.0.0.0/0\n";
        new AwgConfigParser().parse(text.getBytes(StandardCharsets.UTF_8));
    }
}
