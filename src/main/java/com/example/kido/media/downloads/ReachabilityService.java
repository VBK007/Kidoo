package com.example.kido.media.downloads;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.kido.media.catalog.MediaInfo;
import com.example.kido.media.catalog.MediaItem;
import com.example.kido.media.dto.DownloadDtos.PlaybackCostDto;
import com.example.kido.media.dto.DownloadDtos.ReachabilityDto;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Answers "am I at home?" and "what would this cost me?".
 *
 * <p>The client cannot answer the first question reliably on its own. Android will say
 * it is on Wi-Fi without knowing whose Wi-Fi, and an SSID check breaks the moment
 * someone renames their router or uses a guest network. The server, on the other hand,
 * can simply look at where the request arrived from: a private address on one of its
 * own interfaces means the phone is on the same LAN. That is the fact the away-from-home
 * banner and the stream-or-saved sheet both hang on.
 */
@Slf4j
@Service
public class ReachabilityService {

    /**
     * Upload ceiling to assume for a home connection, used for the cost estimate the
     * sheet shows. Deliberately pessimistic: domestic upload is asymmetric and far
     * slower than download, and it is the upload that limits streaming out of the house.
     */
    private static final long ASSUMED_UPLOAD_BITS_PER_SECOND = 3_000_000L;

    /** Height to suggest when streaming away from home, chosen to fit that upload. */
    private static final int AWAY_SUGGESTED_HEIGHT = 720;

    /**
     * Decides whether a request came from the home network.
     *
     * @param request the live request, whose remote address is the evidence
     */
    public ReachabilityDto assess(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        boolean loopback = isLoopback(remote);
        boolean privateAddress = isPrivateAddress(remote);
        boolean sameSubnet = privateAddress && isOnALocalInterface(remote);

        // Loopback is the server talking to itself, which is as "at home" as it gets.
        boolean atHome = loopback || sameSubnet || privateAddress;

        String explanation;
        if (loopback) {
            explanation = "Connected on the server itself.";
        } else if (sameSubnet) {
            explanation = "On the same local network as the server.";
        } else if (privateAddress) {
            explanation = "On a private network, most likely the same house.";
        } else {
            explanation = "Connecting from outside the house, so streaming uses your "
                    + "data plan and the home upload speed.";
        }

        return new ReachabilityDto(
                atHome,
                atHome ? "HOME" : "AWAY",
                remote,
                explanation,
                atHome ? null : ASSUMED_UPLOAD_BITS_PER_SECOND,
                atHome ? null : AWAY_SUGGESTED_HEIGHT);
    }

    /**
     * What playing a title would cost right now.
     *
     * <p>Two numbers, because they differ by a lot: the original file as-is, and a
     * transcode sized for the connection. Away from home that difference is the whole
     * decision the sheet is asking the user to make.
     */
    public PlaybackCostDto costOf(MediaItem item, boolean atHome, Integer awayHeight) {
        MediaInfo info = item.getMediaInfo();
        Double duration = info == null ? null : info.getDurationSeconds();
        int height = awayHeight != null && awayHeight > 0 ? awayHeight : AWAY_SUGGESTED_HEIGHT;

        Long transcodedBytes = null;
        if (duration != null && duration > 0) {
            long bitsPerSecond = switch (height) {
                case 1080 -> 4_500_000L;
                case 720 -> 2_500_000L;
                case 480 -> 1_200_000L;
                default -> 2_500_000L;
            };
            transcodedBytes = Math.round(duration * (bitsPerSecond + 160_000L) / 8.0);
        }

        // Whether the home upload can even sustain the original bitrate.
        boolean originalFitsUpload = info == null || info.getBitrate() == null
                || info.getBitrate() <= ASSUMED_UPLOAD_BITS_PER_SECOND;

        return new PlaybackCostDto(
                item.getId(),
                atHome,
                item.getFileSize(),
                transcodedBytes,
                height,
                atHome ? null : ASSUMED_UPLOAD_BITS_PER_SECOND,
                atHome || originalFitsUpload,
                atHome
                        ? "On the home network, so this plays at full quality with no data cost."
                        : "Away from home this would come over your data plan, and the house "
                                + "upload is capped, so it would be reduced to " + height + "p.");
    }

    private static boolean isLoopback(String address) {
        return address != null
                && (address.equals("127.0.0.1") || address.equals("::1")
                || address.startsWith("0:0:0:0:0:0:0:1"));
    }

    /**
     * RFC 1918 and friends, via {@link InetAddress} rather than string matching.
     *
     * <p>{@code isSiteLocalAddress} covers 10/8, 172.16/12 and 192.168/16, and
     * {@code isLinkLocalAddress} covers 169.254/16 and IPv6 fe80::/10 — between them
     * that is every address a home router hands out.
     */
    private static boolean isPrivateAddress(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            InetAddress parsed = InetAddress.getByName(address);
            return parsed.isSiteLocalAddress()
                    || parsed.isLinkLocalAddress()
                    || parsed.isLoopbackAddress()
                    // Unique local IPv6, which isSiteLocalAddress does not report.
                    || (parsed.getAddress().length == 16 && (parsed.getAddress()[0] & 0xFE) == 0xFC);
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    /**
     * Stronger evidence than "private": the address shares a subnet with one of the
     * server's own interfaces, so it really is this network rather than some other
     * house that also uses 192.168.
     */
    private boolean isOnALocalInterface(String address) {
        try {
            InetAddress remote = InetAddress.getByName(address);
            byte[] remoteBytes = remote.getAddress();

            List<NetworkInterface> interfaces =
                    Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface candidate : interfaces) {
                if (!candidate.isUp() || candidate.isLoopback()) {
                    continue;
                }
                for (var binding : candidate.getInterfaceAddresses()) {
                    InetAddress local = binding.getAddress();
                    if (local == null || local.getAddress().length != remoteBytes.length) {
                        continue;
                    }
                    if (sharesPrefix(local.getAddress(), remoteBytes,
                            binding.getNetworkPrefixLength())) {
                        return true;
                    }
                }
            }
        } catch (UnknownHostException | SocketException ex) {
            log.debug("Could not compare {} against local interfaces: {}",
                    address, ex.getMessage());
        }
        return false;
    }

    /** Compares the first {@code prefixLength} bits of two addresses. */
    private static boolean sharesPrefix(byte[] left, byte[] right, int prefixLength) {
        if (prefixLength <= 0 || prefixLength > left.length * 8) {
            return false;
        }
        int wholeBytes = prefixLength / 8;
        for (int i = 0; i < wholeBytes; i++) {
            if (left[i] != right[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return (left[wholeBytes] & mask) == (right[wholeBytes] & mask);
    }
}
