package com.siberanka.twiantivpn.core.blocklist;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyBlocklistServiceTest {
    @Test
    void replacesAndRemovesRetiredDefaultSources() {
        ProxyBlocklistService service = new ProxyBlocklistService();
        service.configure(true, Arrays.asList(
                "https://raw.githubusercontent.com/rezmoss/cloud-provider-ip-addresses/main/aws/txt/all.txt",
                "https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/binarydefense.ipset"
        ), 60, 1000, 512, 15, 250);

        List<String> urls = service.configuredUrls();
        assertEquals(1, urls.size());
        assertEquals(
                "https://raw.githubusercontent.com/ausec-it/cloud-ip-ranges/main/data/providers/aws.csv",
                urls.get(0)
        );
    }

    @Test
    void classifiesEveryDefaultSource() throws Exception {
        List<String> anonymizerSources = new ArrayList<>();
        List<String> reputationSources = new ArrayList<>();
        for (String url : defaultSourceUrls()) {
            if (ProxyBlocklistService.classifySource(url) == ProxyBlocklistService.SourceCategory.ANONYMIZER) {
                anonymizerSources.add(url);
            } else {
                reputationSources.add(url);
            }
        }

        assertEquals(Arrays.asList(
                "https://cinsscore.com/list/ci-badguys.txt",
                "https://lists.blocklist.de/lists/all.txt",
                "https://blocklist.greensnow.co/greensnow.txt",
                "https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/stopforumspam_7d.ipset",
                "https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/firehol_level1.netset",
                "https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/firehol_level2.netset",
                "https://www.spamhaus.org/drop/drop.txt",
                "https://www.spamhaus.org/drop/edrop.txt",
                "https://raw.githubusercontent.com/stamparm/ipsum/master/levels/3.txt",
                "https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/blocklist_de.ipset",
                "https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/greensnow.ipset",
                "https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/ciarmy.ipset",
                "https://raw.githubusercontent.com/stamparm/ipsum/master/levels/4.txt",
                "https://raw.githubusercontent.com/stamparm/ipsum/master/levels/5.txt"
        ), reputationSources);
        assertTrue(anonymizerSources.contains("https://check.torproject.org/torbulkexitlist?ip=1.1.1.1"));
        assertTrue(anonymizerSources.contains("https://raw.githubusercontent.com/scriptzteam/ProtonVPN-VPN-IPs/main/exit_ips.txt"));
        assertTrue(anonymizerSources.contains("https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/firehol_anonymous.netset"));
        assertTrue(anonymizerSources.contains("https://raw.githubusercontent.com/ausec-it/cloud-ip-ranges/main/data/providers/aws.csv"));
        assertTrue(anonymizerSources.contains("https://github.com/FifzzSENZE/Master-Proxy/raw/refs/heads/master/proxies/all.txt"));
        assertEquals(30, anonymizerSources.size());
    }

    @Test
    void classificationUsesWholeTokensForTor() {
        assertEquals(ProxyBlocklistService.SourceCategory.REPUTATION,
                ProxyBlocklistService.classifySource("https://example.org/history/repository/monitor.txt"));
        assertEquals(ProxyBlocklistService.SourceCategory.ANONYMIZER,
                ProxyBlocklistService.classifySource("https://example.org/tor/list.txt"));
        assertEquals(ProxyBlocklistService.SourceCategory.REPUTATION,
                ProxyBlocklistService.classifySource(null));
    }

    @Test
    void reportsTheCategoryOfTheListingSource() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve(server, "/tor-exit-nodes.txt", "198.51.100.7\n203.0.113.9\n");
        serve(server, "/abuse/all.txt", "# abuse reputation\n203.0.113.0/24\n192.0.2.44\n");
        server.start();
        ProxyBlocklistService service = new ProxyBlocklistService();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            service.configure(true, Arrays.asList(base + "/tor-exit-nodes.txt", base + "/abuse/all.txt"),
                    60, 1000, 512, 5, 100);
            service.start();
            long deadline = System.currentTimeMillis() + 15000L;
            while (service.size() < 4 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }
            assertEquals(4, service.size());

            assertEquals(Optional.of(ProxyBlocklistService.SourceCategory.ANONYMIZER), service.match("198.51.100.7"));
            assertEquals(Optional.of(ProxyBlocklistService.SourceCategory.ANONYMIZER), service.match("203.0.113.9"),
                    "anonymizer evidence wins when both kinds of source list the address");
            assertEquals(Optional.of(ProxyBlocklistService.SourceCategory.REPUTATION), service.match("203.0.113.200"));
            assertEquals(Optional.of(ProxyBlocklistService.SourceCategory.REPUTATION), service.match("192.0.2.44"));
            assertFalse(service.match("192.0.2.45").isPresent());
            assertTrue(service.contains("192.0.2.44"));
            assertFalse(service.contains("not-an-ip"));
        } finally {
            service.stop();
            server.stop(0);
        }
    }

    static void serve(HttpServer server, String path, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
    }

    private static List<String> defaultSourceUrls() {
        InputStream stream = ProxyBlocklistServiceTest.class.getResourceAsStream("/config.yml");
        assertNotNull(stream);
        String config;
        try (Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            config = scanner.next();
        }
        String section = config.substring(config.indexOf("proxy-blocklist:"), config.indexOf("# There are a lot of different services"));
        List<String> urls = new ArrayList<>();
        for (String line : section.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- http")) {
                urls.add(trimmed.substring(2).trim());
            }
        }
        return urls;
    }
}
