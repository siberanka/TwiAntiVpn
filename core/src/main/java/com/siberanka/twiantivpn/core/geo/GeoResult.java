package com.siberanka.twiantivpn.core.geo;

public class GeoResult {
    private String ipAddress;
    private String countryName;
    private String cityName;
    private String ispName;
    private String asn;
    private String organization;
    private Boolean hosting;
    private long cachedOn;

    public GeoResult(String ipAddress, String countryName, String cityName, String ispName) {
        this(ipAddress, countryName, cityName, ispName, "", "");
    }

    public GeoResult(String ipAddress, String countryName, String cityName, String ispName, String asn, String organization) {
        this.ipAddress = ipAddress;
        this.countryName = countryName;
        this.cityName = cityName;
        this.ispName = ispName;
        this.asn = asn;
        this.organization = organization;
    }

    public GeoResult(String ipAddress,
                     String countryName,
                     String cityName,
                     String ispName,
                     String asn,
                     String organization,
                     Boolean hosting) {
        this(ipAddress, countryName, cityName, ispName, asn, organization);
        this.hosting = hosting;
    }

    public long getCachedOn() {
        return cachedOn;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getCountryName() {
        return countryName;
    }

    public String getCityName() {
        return cityName;
    }

    public String getIspName() {
        return ispName;
    }

    public String getAsn() {
        return asn == null ? "" : asn;
    }

    public String getOrganization() {
        return organization == null ? "" : organization;
    }

    /**
     * True only when a provider explicitly reported this address as hosting/datacenter space.
     */
    public boolean isHosting() {
        return Boolean.TRUE.equals(hosting);
    }

    /**
     * Raw hosting classification: TRUE, FALSE or null when the provider did not report it.
     */
    public Boolean getHosting() {
        return hosting;
    }

    public boolean hasNetworkIdentity() {
        return !getIspName().isEmpty() || !getAsn().isEmpty() || !getOrganization().isEmpty();
    }

    public void setCachedOn(long cachedOn) {
        this.cachedOn = cachedOn;
    }
}
