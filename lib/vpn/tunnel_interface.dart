import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'olcrtc_client.dart';

class TunnelInterface {
  static const _vpnServiceChannel = MethodChannel('vpn_service');
  static const _statusChannel = EventChannel('vpn_status');

  final OlcrtcClient olcrtc;
  bool _running = false;
  StreamSubscription? _statusSub;
  final _statusController = StreamController<bool>.broadcast();

  Stream<bool> get statusStream => _statusController.stream;

  TunnelInterface(this.olcrtc) {
    _statusSub = _statusChannel.receiveBroadcastStream().listen(
          (event) {
        if (event is bool) {
          _running = event;
          _statusController.add(event);
        }
      },
    );
  }

  Future<bool> start() async {
    try {
      await olcrtc.start();
      await _vpnServiceChannel.invokeMethod('start');
      _running = true;
      return true;
    } catch (e) {
      debugPrint('TunnelInterface start error: $e');
      await stop();
      rethrow;
    }
  }

  Future<void> stop() async {
    _running = false;
    await _vpnServiceChannel.invokeMethod('stop');
    await olcrtc.stop();
  }

  bool get isRunning => _running;

  void dispose() {
    _statusSub?.cancel();
    _statusController.close();
    olcrtc.dispose();
  }
}