import 'dart:async';
import 'package:flutter/services.dart';
import 'olcrtc_client.dart';

class TunnelInterface {
  static const _vpnServiceChannel = MethodChannel('vpn_service');
  final OlcrtcClient olcrtc;
  bool _running = false;

  TunnelInterface(this.olcrtc);

  Future<bool> start() async {
    try {
      await olcrtc.start();
      await _vpnServiceChannel.invokeMethod('start');

      _running = true;
      return true;
    } catch (e) {
      print('TunnelInterface start error: $e');
      await stop();
      return false;
    }
  }

  Future<void> stop() async {
    _running = false;
    await _vpnServiceChannel.invokeMethod('stop');
    await olcrtc.stop();
  }

  bool get isRunning => _running;

  void dispose() {
    olcrtc.dispose();
  }
}