package com.jarvis.agent;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Ищет адрес этого ПК, по которому до релея достучится ТЕЛЕФОН.
 * localhost для этого не годится — для телефона это он сам.
 *
 * Правила те же, что в jarvis-relay/scripts/start-jarvis.ps1 (только на Java):
 * выкидываем виртуальные адаптеры (Radmin, Hyper-V, vEthernet, WSL, VPN, TAP,
 * Bluetooth, Docker) и link-local 169.254.*, а Tailscale (100.64.0.0/10)
 * ставим первым — его адрес не меняется при смене сети.
 */
public class LanAddress {

    private static final Pattern VIRTUAL_ADAPTER = Pattern.compile(
            "radmin|hyper-v|vethernet|wsl|virtual|vpn|tap|wireguard|bluetooth|docker|loopback",
            Pattern.CASE_INSENSITIVE);

    /**
     * Один найденный адрес: сам IP, понятное имя адаптера, признак Tailscale и
     * MAC этого адаптера для Wake-on-LAN ("AA:BB:CC:DD:EE:FF"). mac может быть
     * null — у виртуальных адаптеров вроде Tailscale hardware-адреса нет.
     */
    public record Candidate(String address, String adapterName, boolean tailscale, String mac) {}

    /** Подходящие адреса: сначала Tailscale, потом обычные физические. Может быть пусто. */
    public static List<Candidate> discover() {
        List<Candidate> tailscale = new ArrayList<>();
        List<Candidate> physical = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!isUsable(ni)) continue;
                String mac = macOf(ni);
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    // link-local — это и есть 169.254.*, отдельной проверки не нужно
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    boolean isTailscale = name(ni).toLowerCase(Locale.ROOT).contains("tailscale")
                            || inTailscaleRange(ip);
                    Candidate candidate = new Candidate(ip, name(ni), isTailscale, mac);
                    (isTailscale ? tailscale : physical).add(candidate);
                }
            }
        } catch (SocketException e) {
            System.err.println("Не удалось перечислить сетевые интерфейсы: " + e.getMessage());
        }

        List<Candidate> all = new ArrayList<>(tailscale);
        all.addAll(physical);
        return all;
    }

    private static boolean isUsable(NetworkInterface ni) throws SocketException {
        return !ni.isLoopback() && !ni.isVirtual() && ni.isUp()
                && !VIRTUAL_ADAPTER.matcher(name(ni)).find();
    }

    /** MAC адаптера в виде "AA:BB:CC:DD:EE:FF", или null, если его нет (Tailscale, туннели). */
    private static String macOf(NetworkInterface ni) throws SocketException {
        byte[] hardware = ni.getHardwareAddress();
        if (hardware == null || hardware.length == 0) return null;
        StringBuilder mac = new StringBuilder();
        for (byte b : hardware) {
            if (mac.length() > 0) mac.append(':');
            mac.append(String.format("%02X", b));
        }
        return mac.toString();
    }

    private static String name(NetworkInterface ni) {
        String display = ni.getDisplayName();
        return (display != null && !display.isBlank()) ? display : ni.getName();
    }

    /** 100.64.0.0/10 — диапазон CGNAT, из которого Tailscale раздаёт адреса. */
    private static boolean inTailscaleRange(String ipv4) {
        String[] parts = ipv4.split("\\.");
        if (parts.length != 4) return false;
        try {
            return Integer.parseInt(parts[0]) == 100
                    && Integer.parseInt(parts[1]) >= 64
                    && Integer.parseInt(parts[1]) <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
