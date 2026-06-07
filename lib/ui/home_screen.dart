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
  bool _isLogsExpanded = false;

  final List<String> _logs = [];
  final ScrollController _scrollController = ScrollController();


  bool _userScrolledUp = false;


  bool _isImportantLog(String line) {
    final lower = line.toLowerCase();

    if (lower.contains('error') || lower.contains('fail') || lower.contains('exception')) {
      return true;
    }

    if (lower.contains('session opened') || lower.contains('session closed')) return true;
    if (lower.contains('socks5 server listening')) return true;
    if (lower.contains('bridge open')) return true;

    if (lower.contains('ice state: connected')) return true;
    if (lower.contains('pc state: connected')) return true;
    if (lower.contains('handshake completed')) return true;

    if (lower.contains('vpn started') || lower.contains('vpn stopped')) return true;
    if (lower.contains('tun2socks started') || lower.contains('tun2socks stopped')) return true;
    if (lower.contains('olcrtc started') || lower.contains('olcrtc stopped')) return true;

    if (lower.contains('waitready completed') || lower.contains('waitready error')) return true;

    if (lower.contains('reconnect') || lower.contains('rejoin')) return true;

    return false;
  }

  @override
  void initState() {
    super.initState();
    _olcrtc = OlcrtcClient();
    _tunnel = TunnelInterface(_olcrtc);

    _scrollController.addListener(() {
      if (_scrollController.hasClients) {
        final maxScroll = _scrollController.position.maxScrollExtent;
        final currentScroll = _scrollController.position.pixels;
        _userScrolledUp = maxScroll - currentScroll > 100;
      }
    });

    _olcrtc.logs.listen((line) {
      if (_isImportantLog(line)) {
        setState(() {
          _logs.add(line);
          if (_logs.length > 500) _logs.removeAt(0);
        });

        if (_isLogsExpanded && !_userScrolledUp) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (_scrollController.hasClients) {
              _scrollController.animateTo(
                _scrollController.position.maxScrollExtent,
                duration: const Duration(milliseconds: 100),
                curve: Curves.easeOut,
              );
            }
          });
        }
      }
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

      final timestamp = DateTime.now()
          .toIso8601String()
          .replaceAll(':', '-')
          .split('.')
          .first;
      final fileName = 'dreamwalker_logs_$timestamp.txt';
      final filePath = '${directory.path}/$fileName';

      final file = File(filePath);
      await file.writeAsString(_logs.join('\n'));

      _showSnack('Логи сохранены: $fileName');
    } catch (e) {
      _showSnack('Ошибка сохранения: $e');
    }
  }

  void _clearLogs() {
    setState(() {
      _logs.clear();
      _userScrolledUp = false;
    });
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
      ),
      body: Column(
        children: [
          Expanded(
            child: Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.circle, color: statusColor, size: 20),
                  const SizedBox(height: 8),
                  Text(
                    statusText,
                    style: const TextStyle(
                        fontSize: 16, fontWeight: FontWeight.w600),
                  ),
                  const SizedBox(height: 24),
                  _isLoading
                      ? const CircularProgressIndicator()
                      : ElevatedButton(
                    onPressed: _toggle,
                    style: ElevatedButton.styleFrom(
                      backgroundColor:
                      _isConnected ? Colors.red : Colors.blue,
                      padding: const EdgeInsets.symmetric(
                          horizontal: 48, vertical: 16),
                    ),
                    child: Text(
                      _isConnected ? 'Disconnect' : 'Connect',
                      style: const TextStyle(
                          fontSize: 18, color: Colors.white),
                    ),
                  ),
                ],
              ),
            ),
          ),

          if (_logs.isNotEmpty || _isLogsExpanded) _buildLogsPanel(),
        ],
      ),
    );
  }

  Widget _buildLogsPanel() {
    return Container(
      decoration: BoxDecoration(
        color: Colors.grey.shade100,
        border: Border(
          top: BorderSide(
            color: Colors.grey.shade400,
            width: 1,
          ),
        ),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          InkWell(
            onTap: () {
              setState(() {
                _isLogsExpanded = !_isLogsExpanded;
                if (_isLogsExpanded) {
                  _userScrolledUp = false;
                  WidgetsBinding.instance.addPostFrameCallback((_) {
                    if (_scrollController.hasClients) {
                      _scrollController.animateTo(
                        _scrollController.position.maxScrollExtent,
                        duration: const Duration(milliseconds: 200),
                        curve: Curves.easeOut,
                      );
                    }
                  });
                }
              });
            },
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
              child: Row(
                children: [
                  Icon(
                    _isLogsExpanded
                        ? Icons.keyboard_arrow_down
                        : Icons.keyboard_arrow_up,
                    color: Colors.grey.shade700,
                    size: 20,
                  ),
                  const SizedBox(width: 8),
                  const Text(
                    'Логи',
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                      color: Colors.black87,
                    ),
                  ),
                ],
              ),
            ),
          ),

          if (_isLogsExpanded) ...[
            Padding(
              padding: const EdgeInsets.only(left: 16, top: 4),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  '${_logs.length} строк',
                  style: TextStyle(
                    fontSize: 11,
                    color: Colors.grey.shade600,
                  ),
                ),
              ),
            ),

            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              color: Colors.grey.shade200,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton.icon(
                    onPressed: _logs.isEmpty ? null : _saveLogsToFile,
                    icon: const Icon(Icons.save, size: 16),
                    label: const Text('Сохранить',
                        style: TextStyle(fontSize: 12)),
                  ),
                  TextButton.icon(
                    onPressed: _logs.isEmpty ? null : _copyLogs,
                    icon: const Icon(Icons.copy, size: 16),
                    label: const Text('Копировать',
                        style: TextStyle(fontSize: 12)),
                  ),
                  TextButton.icon(
                    onPressed: _logs.isEmpty ? null : _clearLogs,
                    icon: const Icon(Icons.delete_outline, size: 16),
                    label: const Text('Очистить',
                        style: TextStyle(fontSize: 12)),
                  ),
                ],
              ),
            ),

            Container(
              height: 250,
              decoration: BoxDecoration(
                color: Colors.black,
                border: Border(top: BorderSide(color: Colors.grey.shade400)),
              ),
              child: ListView.builder(
                controller: _scrollController,
                padding: const EdgeInsets.all(8),
                itemCount: _logs.length,
                itemBuilder: (_, i) {
                  final line = _logs[i];
                  return SelectableText(
                    line,
                    style: TextStyle(
                      fontSize: 10,
                      fontFamily: 'monospace',
                      color: _getLogColor(line),
                    ),
                  );
                },
              ),
            ),
          ],
        ],
      ),
    );
  }

  Color _getLogColor(String line) {
    final lower = line.toLowerCase();
    if (lower.contains('error') || lower.contains('fail')) {
      return Colors.red.shade300;
    }
    if (lower.contains('warn')) {
      return Colors.orange.shade300;
    }
    if (lower.contains('connected') ||
        lower.contains('opened') ||
        lower.contains('listening')) {
      return Colors.green.shade300;
    }
    if (lower.contains('[udp]')) {
      return Colors.orange.shade200;
    }
    if (lower.contains('[tcp]')) {
      return Colors.blue.shade200;
    }
    return Colors.white70;
  }
}