package com.siberanka.twiantivpn.core.isp;

import com.siberanka.twiantivpn.core.geo.GeoResult;

public class IspBlockResult {
    private final GeoResult geoResult;
    private final String matchedType;
    private final String matchedValue;

    public IspBlockResult(GeoResult geoResult, String matchedType, String matchedValue) {
        this.geoResult = geoResult;
        this.matchedType = matchedType;
        this.matchedValue = matchedValue;
    }

    public GeoResult getGeoResult() {
        return geoResult;
    }

    public String getMatchedType() {
        return matchedType;
    }

    public String getMatchedValue() {
        return matchedValue;
    }
}
