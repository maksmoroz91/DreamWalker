import 'dart:async';
import 'dart:typed_data';
import 'package:flutter/services.dart';

class TunnelInterface {
  static const MethodChannel _methodChannel = MethodChannel('vpn_channel');
  static const EventChannel _eventChannel = EventChannel('vpn_events');

  final StreamController<Uint8List> _packetController = StreamController.broadcast();
  Stream<Uint8List> get onPacket => _packetController.stream;
  StreamSubscription? _eventSubscription;

  Future<bool> start() async {
    try {
      await _methodChannel.invokeMethod('start');
      _eventSubscription = _eventChannel.receiveBroadcastStream().listen((event) {
        if (event is Uint8List) {
          _packetController.add(event);
        } else if (event is List<int>) {
          _packetController.add(Uint8List.fromList(event));
        }
      });
      return true;
    } catch (e) {
      print('Tunnel start error: $e');
      return false;
    }
  }

  Future<void> stop() async {
    await _eventSubscription?.cancel();
    await _methodChannel.invokeMethod('stop');
  }

  Future<void> writePacket(Uint8List packet) async {
    await _methodChannel.invokeMethod('write', {'packet': packet});
  }

  void dispose() {
    _eventSubscription?.cancel();
    _packetController.close();
  }
}