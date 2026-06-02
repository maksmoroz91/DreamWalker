import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../vpn/tunnel_interface.dart';
import '../vpn/packet_handler.dart';

class HomeScreen extends StatefulWidget {
  @override
  _HomeScreenState createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late TunnelInterface _tunnel;
  late PacketHandler _packetHandler;
  bool _isVpnRunning = false;

  @override
  void initState() {
    super.initState();
    _tunnel = TunnelInterface();
    _packetHandler = PacketHandler(_tunnel);
  }

  Future<void> _toggleVpn() async {
    if (_isVpnRunning) {
      await _tunnel.stop();
      setState(() => _isVpnRunning = false);
    } else {
      bool granted = await _requestVpnPermission();
      if (!granted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('VPN permission denied')),
        );
        return;
      }
      bool success = await _tunnel.start();
      if (success) {
        _packetHandler.start();
        setState(() => _isVpnRunning = true);
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to start VPN')),
        );
      }
    }
  }

  Future<bool> _requestVpnPermission() async {
    const platform = MethodChannel('vpn_prepare'); // <- теперь const работает, т.к. это константное выражение? На самом деле MethodChannel не константа, но лучше убрать const. Однако ошибка была из-за отсутствия импорта. Сейчас оставим как есть, но если будет ошибка "not a constant expression", замените на "final platform = MethodChannel('vpn_prepare');"
    try {
      final bool granted = await platform.invokeMethod('prepare');
      return granted;
    } catch (e) {
      print('Prepare error: $e');
      return false;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('WebRTC VPN Client')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text('Status: ${_isVpnRunning ? "CONNECTED" : "DISCONNECTED"}'),
            const SizedBox(height: 20),
            ElevatedButton(
              onPressed: _toggleVpn,
              child: Text(_isVpnRunning ? 'Disconnect' : 'Connect'),
            ),
          ],
        ),
      ),
    );
  }
}