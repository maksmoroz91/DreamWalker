import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';

class OlcrtcClient {
  static const _channel = MethodChannel('olcrtc_channel');
  static const _logChannel = EventChannel('olcrtc_logs');

  StreamSubscription? _logSub;
  final _logController = StreamController<String>.broadcast();

  Stream<String> get logs => _logController.stream;

  static const socksHost = '127.0.0.1';
  static const socksPort = 10808;

  Future<String> getDeviceId() async {
    return await _channel.invokeMethod<String>('getDeviceId') ?? 'device-unknown';
  }

  Future<void> start() async {
    try {
      final roomId  = dotenv.env['JITSI_ROOM_ID'] ?? '';
      final key     = dotenv.env['OLCRTC_KEY']    ?? '';
      final carrier = dotenv.env['OLCRTC_CARRIER'] ?? 'jitsi';

      if (roomId.isEmpty) throw Exception('JITSI_ROOM_ID not set in .env');
      if (key.isEmpty)    throw Exception('OLCRTC_KEY not set in .env');
      if (key.length != 64) throw Exception('OLCRTC_KEY must be 64 hex chars');

      final clientId = await getDeviceId();

      _logSub = _logChannel.receiveBroadcastStream().listen(
            (event) { if (event is String) _logController.add(event); },
        onError: (e) => _logController.addError(e),
      );

      await _channel.invokeMethod('start', {
        'carrier':         carrier,
        'roomId':          roomId,
        'clientId':        clientId,
        'key':             key,
      });

    } catch (e, stack) {
      print('OlcrtcClient.start error: $e');
      print(stack);
      rethrow;
    }
  }

  Future<void> stop() async {
    await _logSub?.cancel();
    _logSub = null;
    await _channel.invokeMethod('stop');
  }

  Future<bool> isRunning() async {
    return await _channel.invokeMethod<bool>('isRunning') ?? false;
  }

  void dispose() {
    _logSub?.cancel();
    _logController.close();
  }
}
