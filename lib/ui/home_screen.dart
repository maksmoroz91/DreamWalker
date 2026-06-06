import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
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
  final ScrollController _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    _olcrtc = OlcrtcClient();
    _tunnel = TunnelInterface(_olcrtc);

    _olcrtc.logs.listen((line) {
      setState(() {
        _logs.add(line);
        if (_logs.length > 500) _logs.removeAt(0);
      });

      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_scrollController.hasClients) {
          _scrollController.animateTo(
            _scrollController.position.maxScrollExtent,
            duration: const Duration(milliseconds: 100),
            curve: Curves.easeOut,
          );
        }
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

  void _copyLogs() {
    final text = _logs.join('\n');
    Clipboard.setData(ClipboardData(text: text));
    _showSnack('Скопировано в буфер (${_logs.length} строк)');
  }

  Future<void> _saveLogsToFile() async {
    if (_logs.isEmpty) {
      _showSnack('Нет логов для сохранения');
      return;
    }

    try {
      var directory = Directory('/storage/emulated/0/Download');
      if (!await directory.exists()) {
        directory = await getApplicationDocumentsDirectory();
      }

      final timestamp = DateTime.now().toIso8601String().replaceAll(':', '-').split('.').first;
      final fileName = 'vpn_logs_$timestamp.txt';
      final filePath = '${directory.path}/$fileName';

      final file = File(filePath);
      await file.writeAsString(_logs.join('\n'));

      _showSnack('Логи сохранены: $fileName\nПуть: ${directory.path}');
    } catch (e) {
      _showSnack('Ошибка сохранения: $e');
    }
  }

  void _clearLogs() {
    setState(() => _logs.clear());
  }

  void _showSnack(String msg) {
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  void dispose() {
    _scrollController.dispose();
    _tunnel.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final statusColor = _isConnected ? Colors.green : Colors.grey;
    final statusText = _isConnected ? 'CONNECTED' : 'DISCONNECTED';

    return Scaffold(
      appBar: AppBar(
        title: const Text('DreamWalker'),
        actions: [
          IconButton(
            icon: const Icon(Icons.save),
            tooltip: 'Сохранить логи в файл',
            onPressed: _logs.isEmpty ? null : _saveLogsToFile,
          ),
          IconButton(
            icon: const Icon(Icons.copy),
            tooltip: 'Копировать логи',
            onPressed: _logs.isEmpty ? null : _copyLogs,
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline),
            tooltip: 'Очистить логи',
            onPressed: _logs.isEmpty ? null : _clearLogs,
          ),
        ],
      ),
      body: Column(
        children: [
          const SizedBox(height: 24),
          Icon(Icons.circle, color: statusColor, size: 20),
          const SizedBox(height: 8),
          Text(statusText,
              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
          const SizedBox(height: 24),

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

          const SizedBox(height: 12),
          const Divider(height: 1),

          if (_logs.isNotEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text('${_logs.length} строк',
                      style: const TextStyle(fontSize: 10, color: Colors.grey)),
                  Row(
                    children: [
                      GestureDetector(
                        onTap: _saveLogsToFile,
                        child: const Text('💾 Сохранить',
                            style: TextStyle(fontSize: 10, color: Colors.green)),
                      ),
                      const SizedBox(width: 16),
                      GestureDetector(
                        onTap: _copyLogs,
                        child: const Text('📋 Копировать',
                            style: TextStyle(fontSize: 10, color: Colors.blue)),
                      ),
                    ],
                  ),
                ],
              ),
            ),

          Expanded(
            child: _logs.isEmpty
                ? const Center(
                child: Text('Логи появятся здесь',
                    style: TextStyle(color: Colors.grey)))
                : ListView.builder(
              controller: _scrollController,
              padding: const EdgeInsets.all(8),
              itemCount: _logs.length,
              itemBuilder: (_, i) => SelectableText(
                _logs[i],
                style: TextStyle(
                  fontSize: 10,
                  fontFamily: 'monospace',
                  color: _logs[i].contains('ERROR') ||
                      _logs[i].contains('error')
                      ? Colors.red
                      : _logs[i].contains('WARN')
                      ? Colors.orange
                      : _logs[i].contains('connected') ||
                      _logs[i].contains('opened')
                      ? Colors.green
                      : Colors.black87,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}