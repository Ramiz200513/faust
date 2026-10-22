package com.jarvis.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN: будим ПК из сна широковещательным "magic packet" по локальной
 * сети. Работает только если ПК подключён кабелем Ethernet и в свойствах
 * сетевой карты включена галочка "Wake on Magic Packet" — это пользователь
 * настраивает в Windows сам, код тут ни за что из этого не отвечает.
 *
 * Отдельных разрешений в манифесте не нужно: INTERNET уже есть и его хватает
 * для ОТПРАВКИ UDP-датаграмм (в том числе широковещательных); отдельное
 * CHANGE_WIFI_MULTICAST_STATE нужно только для ПРИЁМА multicast-трафика.
 */
object WakeOnLan {

    /**
     * Собрать и отправить magic packet на [mac] (формат "AA:BB:CC:DD:EE:FF").
     * Вызывать на Dispatchers.IO — это сетевая операция.
     */
    fun sendMagicPacket(mac: String) {
        val macBytes = parseMac(mac)

        // Magic packet: 6 байт 0xFF, затем MAC-адрес повторён 16 раз подряд.
        val packetBytes = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packetBytes[i] = 0xFF.toByte()
        for (i in 0 until 16) {
            System.arraycopy(macBytes, 0, packetBytes, 6 + i * 6, 6)
        }

        val address = InetAddress.getByName("255.255.255.255")
        val packet = DatagramPacket(packetBytes, packetBytes.size, address, 9)
        DatagramSocket().apply { broadcast = true }.use { it.send(packet) }
    }

    /** "AA:BB:CC:DD:EE:FF" -> 6 байт. Разделитель — обычно ":", но принимаем и "-". */
    private fun parseMac(mac: String): ByteArray {
        val hex = mac.replace(":", "").replace("-", "")
        require(hex.length == 12) { "неверный MAC-адрес: $mac" }
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
