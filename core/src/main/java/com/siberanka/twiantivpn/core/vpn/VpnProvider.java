package com.siberanka.twiantivpn.core.vpn;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface VpnProvider {
    CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress);
}
