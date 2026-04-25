package com.valui.bot.webhook;

/**
 * Minimal IPv4 CIDR range matcher. Supports prefixes 0–32.
 * IPv6 addresses are treated as not matching any range.
 */
record CidrRange(int network, int mask) {

    static CidrRange parse(String cidr) {
        String[] parts = cidr.trim().split("/");
        int prefix  = Integer.parseInt(parts[1].trim());
        int network = ipToInt(parts[0].trim());
        int mask    = prefix == 0 ? 0 : (-1 << (32 - prefix));
        return new CidrRange(network & mask, mask);
    }

    boolean contains(String ip) {
        try {
            int addr = ipToInt(ip);
            return (addr & mask) == (network & mask);
        } catch (Exception e) {
            return false; // IPv6 or malformed
        }
    }

    private static int ipToInt(String ip) {
        String[] octets = ip.split("\\.");
        if (octets.length != 4) throw new IllegalArgumentException("Not an IPv4: " + ip);
        int result = 0;
        for (String o : octets) result = (result << 8) | Integer.parseInt(o);
        return result;
    }
}
