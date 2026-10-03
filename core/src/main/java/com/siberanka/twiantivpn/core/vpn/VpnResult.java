package com.siberanka.twiantivpn.core.vpn;

import java.util.Optional;

public class VpnResult {
    private final String ipAddress;
    private Optional<String> vpnProviderName;
    private boolean isVpn;
    private boolean anonymizer;
    private long cachedOn;

    public VpnResult(String ipAddress, boolean isVpn) {
        this.ipAddress = ipAddress;
        this.isVpn = isVpn;
        this.vpnProviderName = Optional.empty();
    }

    public VpnResult(String ipAddress, boolean isVpn, Optional<String> vpnProviderName) {
        this.ipAddress = ipAddress;
        this.isVpn = isVpn;
        this.vpnProviderName = vpnProviderName;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public boolean isVpn() {
        return isVpn;
    }

    public Optional<String> getVpnProviderName() {
        return vpnProviderName;
    }

    /**
     * True when the verdict is backed by hard anonymizer evidence such as a Tor exit, a named VPN
     * operator, or an anonymizer/proxy/datacenter blocklist source. Trusted ISP exemptions never
     * override such verdicts.
     */
    public boolean isAnonymizer() {
        return anonymizer;
    }

    public VpnResult setAnonymizer(boolean anonymizer) {
        this.anonymizer = anonymizer;
        return this;
    }

    public long getCachedOn() {
        return cachedOn;
    }

    public void setCachedOn(long cachedOn) {
        this.cachedOn = cachedOn;
    }

    public void setVpn(boolean vpn) {
        isVpn = vpn;
    }
}
