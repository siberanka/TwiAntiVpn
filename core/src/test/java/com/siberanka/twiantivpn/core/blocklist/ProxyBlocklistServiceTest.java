package com.siberanka.twiantivpn.core.blocklist;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
