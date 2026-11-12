package io.quartetqe.generator;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.function.Predicate;

/**
 * Keeps a download from reaching into the user's own machine or network. Without it an address supplied through a chat
 * (and so possibly planted by a web page the AI had read) could fetch cloud metadata or an internal service and hand the
 * answer back.
 */
final class UrlGuard {
    private UrlGuard() {}

    /** Throws unless the address is plain http or https to a public host, or to a host the user has listed in guard.allowHosts. */
    static void requirePublic(URI uri, Predicate<String> allowedHost) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https")) || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Give a plain http or https address with no username or password in it.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("That address has no host.");
        if (allowedHost.test(host)) return;
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Could not find the host " + host + ".");
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw new IllegalArgumentException(host + " is a private or local address (" + address.getHostAddress() + "), so it was not downloaded from. "
                        + "Quartet only downloads from public addresses. For a host on your own network, list it in guard.allowHosts first.");
            }
        }
    }

    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) return (bytes[0] & 0xfe) == 0xfc; // unique local fc00::/7
        return (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64; // shared carrier-grade range 100.64.0.0/10
    }
}
