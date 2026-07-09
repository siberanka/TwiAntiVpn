package com.siberanka.twiantivpn.core.cache;

import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.vpn.VpnResult;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface CacheProvider {
    CompletableFuture<Boolean> setup();
    CompletableFuture<Boolean> disband();
    CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress);
    CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress);
    CompletableFuture<Void> addVpnResult(VpnResult vpnResult);
    CompletableFuture<Void> addGeoResult(GeoResult geoResult);
    CompletableFuture<Boolean> removeVpnResult(String ipAddress);
    CompletableFuture<Boolean> removeGeoResult(String ipAddress);
    CompletableFuture<Boolean> removeAllVpnResults();
    CompletableFuture<Boolean> removeAllGeoResults();
}
