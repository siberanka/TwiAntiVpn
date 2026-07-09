package com.siberanka.twiantivpn.core.integration;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum CheckModule {
    USERNAME_FILTER("username-filter"),
    PROXY_BLOCKLIST("proxy-blocklist"),
    VPN_PROXYCHECK("proxycheck"),
    VPN_IP_API("ip-api"),
    VPN_IPHUB("iphub"),
    VPN_VPNAPI("vpnapi"),
    VPN_CUSTOM("custom"),
    GEO_BLOCK("geo-block"),
    ISP_BLOCK("isp-block");

    private final String configName;

    CheckModule(String configName) {
        this.configName = configName;
    }

    public String getConfigName() {
        return configName;
    }

    public boolean isVpnProvider() {
        return name().startsWith("VPN_");
    }

    public String getProviderName() {
        return isVpnProvider() ? configName : "";
    }

    public static Set<CheckModule> immutableCopy(Set<CheckModule> modules) {
        if (modules == null || modules.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(modules));
    }

    public static Set<String> providerNames(Set<CheckModule> modules) {
        if (modules == null || modules.isEmpty()) {
            return Collections.emptySet();
        }
        java.util.HashSet<String> names = new java.util.HashSet<>();
        for (CheckModule module : modules) {
            if (module.isVpnProvider()) {
                names.add(module.getProviderName().toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(names);
    }
}
