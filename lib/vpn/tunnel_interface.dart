import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/services.dart';
import 'olcrtc_client.dart';

/// Управляет TUN-интерфейсом и маршрутизацией пакетов через SOCKS5 olcrtc.
///
/// Схема:
///   Android TUN (10.0.0.2)
///     → пакеты читаются VpnService и приходят сюда через EventChannel
///     → TunSocksRouter разбирает IP-пакеты и открывает SOCKS5 соединения
///     → olcrtc SOCKS5 :8808 → Jitsi WebRTC → сервер → интернет
class TunnelInterface {
  static const _vpnServiceChannel = MethodChannel('vpn_service');
  static const _packetEventChannel = EventChannel('vpn_events');

  final OlcrtcClient olcrtc;

  final _packetController = StreamController<Uint8List>.broadcast();
  Stream<Uint8List> get onPacket => _packetController.stream;

  StreamSubscription? _packetSub;
  TunSocksRouter? _router;
  bool _running = false;

  TunnelInterface(this.olcrtc);

  Future<bool> start() async {
    try {
      // 1. Запускаем olcrtc туннель (WebRTC → Jitsi)
      await olcrtc.start();


      // 3. Запускаем Android TUN интерфейс
      await _vpnServiceChannel.invokeMethod('start');

      // 4. Подписываемся на пакеты из TUN
      _packetSub = _packetEventChannel.receiveBroadcastStream().listen(
        (event) {
          final bytes = event is Uint8List
              ? event
              : Uint8List.fromList(List<int>.from(event as List));
          _packetController.add(bytes);
        },
        onError: (e) => print('TUN packet error: $e'),
      );

      // 5. Запускаем роутер TUN → SOCKS5
      _router = TunSocksRouter(
        socksHost: OlcrtcClient.socksHost,
        socksPort: OlcrtcClient.socksPort,
        onWriteBack: writePacket,
      );
      _router!.start(onPacket);

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
    _router?.stop();
    _router = null;
    await _packetSub?.cancel();
    _packetSub = null;
    await _vpnServiceChannel.invokeMethod('stop');
    await olcrtc.stop();
    _packetController.done;
  }

  Future<void> writePacket(Uint8List packet) async {
    // Пишем пакет обратно в TUN (ответ от сервера → приложению)
    // Реализовано в VpnPlugin через MethodChannel
    await const MethodChannel('vpn_channel')
        .invokeMethod('write', {'packet': packet});
  }

  bool get isRunning => _running;

  void dispose() {
    _router?.stop();
    _packetController.close();
    olcrtc.dispose();
  }
}

/// Роутер IP-пакетов из TUN в SOCKS5 соединения.
///
/// Для каждого уникального TCP потока (src:port → dst:port)
/// открывает SOCKS5 соединение к olcrtc и проксирует данные.
class TunSocksRouter {
  final String socksHost;
  final int socksPort;
  final Future<void> Function(Uint8List) onWriteBack;

  // Таблица активных TCP потоков: "srcIP:srcPort:dstIP:dstPort" → _TcpFlow
  final Map<String, _TcpFlow> _flows = {};
  StreamSubscription? _sub;
  bool _running = false;

  TunSocksRouter({
    required this.socksHost,
    required this.socksPort,
    required this.onWriteBack,
  });

  void start(Stream<Uint8List> packets) {
    _running = true;
    _sub = packets.listen(_handlePacket);
  }

  void stop() {
    _running = false;
    _sub?.cancel();
    for (final flow in _flows.values) {
      flow.close();
    }
    _flows.clear();
  }

  void _handlePacket(Uint8List packet) {
    if (!_running || packet.length < 20) return;

    final version = (packet[0] >> 4) & 0xF;
    if (version == 4) {
      _handleIPv4(packet);
    }
    // IPv6 — пропускаем пока (olcrtc SOCKS5 поддерживает, добавить позже)
  }

  void _handleIPv4(Uint8List packet) {
    if (packet.length < 20) return;

    final protocol = packet[9];
    final srcIP = _parseIPv4(packet, 12);
    final dstIP = _parseIPv4(packet, 16);
    final ihl = (packet[0] & 0xF) * 4;

    if (protocol == 6) {
      // TCP
      _handleTCP(packet, ihl, srcIP, dstIP);
    } else if (protocol == 17) {
      // UDP — DNS проксируем через SOCKS5 UDP ASSOCIATE
      // Упрощённо: перенаправляем DNS на 8.8.8.8 напрямую через SOCKS5
      _handleUDP(packet, ihl, srcIP, dstIP);
    }
  }

  void _handleTCP(
      Uint8List packet, int ihl, String srcIP, String dstIP) {
    if (packet.length < ihl + 20) return;

    final srcPort = _readU16(packet, ihl);
    final dstPort = _readU16(packet, ihl + 2);
    final flags   = packet[ihl + 13] & 0x3F;
    final isSYN   = (flags & 0x02) != 0;
    final isFIN   = (flags & 0x01) != 0;
    final isRST   = (flags & 0x04) != 0;
    final isACK   = (flags & 0x10) != 0;

    final key = '$srcIP:$srcPort:$dstIP:$dstPort';

    if (isSYN && !isACK) {
      // Новое соединение — открываем SOCKS5 поток
      if (_flows.containsKey(key)) {
        _flows[key]!.close();
        _flows.remove(key);
      }

      final flow = _TcpFlow(
        srcIP: srcIP, srcPort: srcPort,
        dstIP: dstIP, dstPort: dstPort,
        socksHost: socksHost, socksPort: socksPort,
        onWriteBack: onWriteBack,
      );
      _flows[key] = flow;
      flow.connect();
      return;
    }

    final flow = _flows[key];
    if (flow == null) return;

    if (isFIN || isRST) {
      flow.close();
      _flows.remove(key);
      return;
    }

    // Данные — передаём в SOCKS5
    final dataOffset = ihl + ((packet[ihl + 12] >> 4) * 4);
    if (dataOffset < packet.length) {
      final payload = packet.sublist(dataOffset);
      if (payload.isNotEmpty) {
        flow.send(payload);
      }
    }
  }

  void _handleUDP(
      Uint8List packet, int ihl, String srcIP, String dstIP) {
    if (packet.length < ihl + 8) return;
    final dstPort = _readU16(packet, ihl + 2);
    // Только DNS (53) обрабатываем
    if (dstPort != 53) return;
    final payload = packet.sublist(ihl + 8);
    _forwardDnsViaSocks(dstIP, payload);
  }

  Future<void> _forwardDnsViaSocks(
      String dstIP, Uint8List dnsPayload) async {
    // DNS через SOCKS5 TCP — отправляем DNS-over-TCP запрос
    try {
      final sock = await _connectSocks5(socksHost, socksPort, dstIP, 53);
      // DNS over TCP: 2 байта длины + запрос
      final lenBuf = Uint8List(2);
      lenBuf[0] = (dnsPayload.length >> 8) & 0xFF;
      lenBuf[1] = dnsPayload.length & 0xFF;
      sock.add(lenBuf);
      sock.add(dnsPayload);
      await sock.flush();
      await sock.close();
    } catch (e) {
      // DNS ошибка — не критично
    }
  }

  // --- Вспомогательные методы ---

  String _parseIPv4(Uint8List p, int offset) =>
      '${p[offset]}.${p[offset+1]}.${p[offset+2]}.${p[offset+3]}';

  int _readU16(Uint8List p, int offset) =>
      (p[offset] << 8) | p[offset + 1];
}

/// Один TCP поток через SOCKS5
class _TcpFlow {
  final String srcIP;
  final int srcPort;
  final String dstIP;
  final int dstPort;
  final String socksHost;
  final int socksPort;
  final Future<void> Function(Uint8List) onWriteBack;

  Socket? _socket;
  bool _closed = false;
  final _sendQueue = <Uint8List>[];
  bool _connected = false;

  _TcpFlow({
    required this.srcIP, required this.srcPort,
    required this.dstIP, required this.dstPort,
    required this.socksHost, required this.socksPort,
    required this.onWriteBack,
  });

  Future<void> connect() async {
    try {
      _socket = await _connectSocks5(socksHost, socksPort, dstIP, dstPort);
      _connected = true;

      // Слушаем ответы от сервера и пишем обратно в TUN
      _socket!.listen(
        (data) {
          if (!_closed) {
            onWriteBack(Uint8List.fromList(data));
          }
        },
        onDone: () => close(),
        onError: (_) => close(),
        cancelOnError: true,
      );

      // Отправляем накопившиеся пакеты
      for (final queued in _sendQueue) {
        _socket!.add(queued);
      }
      _sendQueue.clear();
      await _socket!.flush();
    } catch (e) {
      close();
    }
  }

  void send(Uint8List data) {
    if (_closed) return;
    if (_connected && _socket != null) {
      _socket!.add(data);
    } else {
      _sendQueue.add(data);
    }
  }

  void close() {
    _closed = true;
    _connected = false;
    _socket?.destroy();
    _socket = null;
    _sendQueue.clear();
  }
}

/// Устанавливает SOCKS5 соединение и возвращает Socket после handshake.
Future<Socket> _connectSocks5(
    String socksHost, int socksPort, String dstHost, int dstPort) async {
  final socket = await Socket.connect(
    socksHost, socksPort,
    timeout: const Duration(seconds: 10),
  );

  // SOCKS5 handshake: без аутентификации
  // Шаг 1: приветствие
  socket.add(Uint8List.fromList([0x05, 0x01, 0x00]));
  await socket.flush();

  final greeting = await socket.first.timeout(const Duration(seconds: 5));
  if (greeting[1] != 0x00) {
    socket.destroy();
    throw Exception('SOCKS5: unsupported auth method');
  }

  // Шаг 2: запрос CONNECT к dstHost:dstPort
  final hostBytes = dstHost.codeUnits;
  final req = Uint8List(7 + hostBytes.length);
  req[0] = 0x05; // версия
  req[1] = 0x01; // CONNECT
  req[2] = 0x00; // reserved
  req[3] = 0x03; // DOMAINNAME
  req[4] = hostBytes.length;
  for (var i = 0; i < hostBytes.length; i++) {
    req[5 + i] = hostBytes[i];
  }
  req[5 + hostBytes.length] = (dstPort >> 8) & 0xFF;
  req[6 + hostBytes.length] = dstPort & 0xFF;

  socket.add(req);
  await socket.flush();

  final resp = await socket.first.timeout(const Duration(seconds: 10));
  if (resp[1] != 0x00) {
    socket.destroy();
    throw Exception('SOCKS5: CONNECT failed, code=${resp[1]}');
  }

  return socket;
}
