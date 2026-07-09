package com.siberanka.twiantivpn.core.net;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;

public final class IpAddressUtil {
    private IpAddressUtil() {
    }

    public static Optional<Address> parseLiteral(String input) {
        if (input == null) {
            return Optional.empty();
        }

        String value = input.trim();
        if (value.length() < 3 || value.length() > 64) {
            return Optional.empty();
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }

        Optional<Address> ipv4 = parseIpv4(value);
        if (ipv4.isPresent()) {
            return ipv4;
        }

        if (!value.contains(":") || !value.matches("[0-9A-Fa-f:.]+")) {
            return Optional.empty();
        }

        try {
            InetAddress address = InetAddress.getByName(value);
            if (!(address instanceof Inet6Address)) {
                return Optional.empty();
            }
            return Optional.of(Address.ipv6(address.getAddress()));
        } catch (UnknownHostException ignored) {
            return Optional.empty();
        }
    }

    public static Optional<String> normalizeLiteral(String input) {
        Optional<Address> address = parseLiteral(input);
        return address.map(Address::getNormalized);
    }

    public static Optional<String> toHostAddress(String input) {
        Optional<Address> address = parseLiteral(input);
        return address.map(Address::getHostAddress);
    }

    public static Optional<Cidr> parseCidr(String input) {
        if (input == null) {
            return Optional.empty();
        }

        String[] parts = input.trim().split("/", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }

        Optional<Address> address = parseLiteral(parts[0]);
        if (!address.isPresent()) {
            return Optional.empty();
        }

        int prefixLength;
        try {
            prefixLength = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }

        int maxPrefix = address.get().isIpv4() ? 32 : 128;
        if (prefixLength < 0 || prefixLength > maxPrefix) {
            return Optional.empty();
        }

        return Optional.of(new Cidr(address.get(), prefixLength));
    }

    private static Optional<Address> parseIpv4(String input) {
        if (!input.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return Optional.empty();
        }

        String[] parts = input.split("\\.");
        long value = 0;
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            int octet;
            try {
                octet = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
            if (octet < 0 || octet > 255) {
                return Optional.empty();
            }
            value = (value << 8) | octet;
            bytes[i] = (byte) octet;
        }
        return Optional.of(Address.ipv4(bytes, value));
    }

    public static final class Address {
        private final boolean ipv4;
        private final byte[] bytes;
        private final long ipv4Value;
        private final String normalized;
        private final String hostAddress;

        private Address(boolean ipv4, byte[] bytes, long ipv4Value, String normalized, String hostAddress) {
            this.ipv4 = ipv4;
            this.bytes = bytes;
            this.ipv4Value = ipv4Value;
            this.normalized = normalized;
            this.hostAddress = hostAddress;
        }

        private static Address ipv4(byte[] bytes, long value) {
            String normalized = "4:" + (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "."
                    + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF);
            String hostAddress = (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "."
                    + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF);
            return new Address(true, bytes.clone(), value, normalized, hostAddress);
        }

        private static Address ipv6(byte[] bytes) {
            StringBuilder builder = new StringBuilder("6:");
            for (byte current : bytes) {
                builder.append(String.format(Locale.ROOT, "%02x", current & 0xFF));
            }
            String hostAddress;
            try {
                hostAddress = InetAddress.getByAddress(bytes).getHostAddress();
            } catch (UnknownHostException ignored) {
                hostAddress = builder.substring(2);
            }
            return new Address(false, bytes.clone(), 0L, builder.toString(), hostAddress);
        }

        public boolean isIpv4() {
            return ipv4;
        }

        public byte[] getBytes() {
            return bytes.clone();
        }

        public long getIpv4Value() {
            return ipv4Value;
        }

        public String getNormalized() {
            return normalized;
        }

        public String getHostAddress() {
            return hostAddress;
        }
    }

    public static final class Cidr {
        private final Address address;
        private final int prefixLength;

        private Cidr(Address address, int prefixLength) {
            this.address = address;
            this.prefixLength = prefixLength;
        }

        public Address getAddress() {
            return address;
        }

        public int getPrefixLength() {
            return prefixLength;
        }
    }
}
