import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../vpn/olcrtc_client.dart';
import '../vpn/tunnel_interface.dart';

class HomeScreen extends StatefulWidget {
  @override
  _HomeScreenState createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late OlcrtcClient _olcrtc;
  late TunnelInterface _tunnel;

  bool _isConnected = false;
  bool _isLoading = false;
  final List<String> _logs = [];

  @override
  void initState() {
    super.initState();
    _olcrtc = OlcrtcClient();
    _tunnel = TunnelInterface(_olcrtc);

    // Показываем логи olcrtc в UI
    _olcrtc.logs.listen((line) {
      setState(() {
        _logs.add(line);
        if (_logs.length > 200) _logs.removeAt(0);
      });
    });
  }

  Future<void> _toggle() async {
    if (_isLoading) return;
    setState(() => _isLoading = true);

    try {
      if (_isConnected) {
        await _tunnel.stop();
        setState(() => _isConnected = false);
      } else {
        // Запрашиваем разрешение VPN
        final granted = await _requestVpnPermission();
        if (!granted) {
          _showSnack('VPN permission denied');
          return;
        }

        setState(() => _logs.clear());
        final ok = await _tunnel.start();
        if (ok) {
          setState(() => _isConnected = true);
        } else {
          _showSnack('Failed to start tunnel');
        }
      }
    } catch (e) {
      _showSnack('Error: $e');
    } finally {
      setState(() => _isLoading = false);
    }
  }

  Future<bool> _requestVpnPermission() async {
    try {
      return await const MethodChannel('vpn_prepare')
              .invokeMethod<bool>('prepare') ??
          false;
    } catch (e) {
      return false;
    }
  }

  void _showSnack(String msg) {
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  void dispose() {
    _tunnel.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final statusColor = _isConnected ? Colors.green : Colors.grey;
    final statusText  = _isConnected ? 'CONNECTED' : 'DISCONNECTED';

    return Scaffold(
      appBar: AppBar(title: const Text('VPN')),
      body: Column(
        children: [
          const SizedBox(height: 40),

          // Индикатор статуса
          Icon(Icons.circle, color: statusColor, size: 20),
          const SizedBox(height: 8),
          Text(statusText,
              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),

          const SizedBox(height: 32),

          // Кнопка подключения
          _isLoading
              ? const CircularProgressIndicator()
              : ElevatedButton(
                  onPressed: _toggle,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: _isConnected ? Colors.red : Colors.blue,
                    padding: const EdgeInsets.symmetric(
                        horizontal: 48, vertical: 16),
                  ),
                  child: Text(
                    _isConnected ? 'Disconnect' : 'Connect',
                    style: const TextStyle(fontSize: 18, color: Colors.white),
                  ),
                ),

          const SizedBox(height: 24),
          const Divider(),

          // Лог-вьюер
          Expanded(
            child: _logs.isEmpty
                ? const Center(
                    child: Text('Logs will appear here',
                        style: TextStyle(color: Colors.grey)))
                : ListView.builder(
                    padding: const EdgeInsets.all(8),
                    itemCount: _logs.length,
                    itemBuilder: (_, i) => Text(
                      _logs[i],
                      style: const TextStyle(
                          fontSize: 11,
                          fontFamily: 'monospace',
                          color: Colors.black87),
                    ),
                  ),
          ),
        ],
      ),
    );
  }
}
