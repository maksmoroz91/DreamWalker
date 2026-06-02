import 'dart:typed_data';
import 'tunnel_interface.dart';

class PacketHandler {
  final TunnelInterface tunnel;

  PacketHandler(this.tunnel);

  void start() {
    tunnel.onPacket.listen((packet) {
      // Пока просто логируем
      print('📦 Packet from TUN: ${packet.length} bytes');
      if (packet.length >= 20) {
        print('   First 20 bytes: ${packet.sublist(0, 20)}');
      }
      // Здесь позже будем отправлять через WebRTC
    });
  }

  Future<void> sendToTun(Uint8List packet) async {
    await tunnel.writePacket(packet);
  }
}