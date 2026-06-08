import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import '../vpn/olcrtc_client.dart';
import '../vpn/tunnel_interface.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  // ignore: library_private_types_in_public_api
  _HomeScreenState createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with SingleTickerProviderStateMixin {
  late OlcrtcClient _olcrtc;
  late TunnelInterface _tunnel;

  bool _isConnected = false;
  bool _isLoading = false;
  bool _isLogsExpanded = false;

  final List<String> _logs = [];
  final ScrollController _scrollController = ScrollController();
  bool _userScrolledUp = false;

  late AnimationController _glowController;
  late Animation<double> _glowAnimation;

  static const Color moonlight = Color(0xFFD0E8FF);
  static const Color neonCyan = Color(0xFF00FFFF);
  static const Color neonPurple = Color(0xFFFF00FF);
  static const Color darkBg = Color(0xFF0A0E27);
  static const Color cardBg = Color(0xFF151B3D);

  @override
  void initState() {
    super.initState();
    _olcrtc = OlcrtcClient();
    _tunnel = TunnelInterface(_olcrtc);

    _glowController = AnimationController(
      duration: const Duration(milliseconds: 1500),
      vsync: this,
    )..repeat(reverse: true);

    _glowAnimation = Tween<double>(begin: 0.3, end: 1.0).animate(
      CurvedAnimation(parent: _glowController, curve: Curves.easeInOut),
    );

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

  @override
  void dispose() {
    _glowController.dispose();
    _scrollController.dispose();
    _tunnel.dispose();
    super.dispose();
  }

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
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(msg),
        backgroundColor: cardBg,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(10),
          side: BorderSide(color: moonlight.withValues(alpha: 0.5)),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: darkBg,
      appBar: AppBar(
        title: Text(
          'DreamWalker',
          style: TextStyle(
            fontWeight: FontWeight.bold,
            letterSpacing: 1.2,
            color: moonlight,
            shadows: [
              Shadow(
                color: moonlight.withValues(alpha: 0.8),
                blurRadius: 10,
              ),
              Shadow(
                color: neonPurple.withValues(alpha: 0.5),
                blurRadius: 15,
              ),
            ],
          ),
        ),
        flexibleSpace: Container(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              colors: [darkBg, cardBg],
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
            ),
          ),
        ),
        elevation: 0,
        iconTheme: IconThemeData(color: moonlight),
      ),
      body: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              darkBg,
              cardBg,
              darkBg,
            ],
          ),
        ),
        child: Column(
          children: [
            Expanded(
              child: Center(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    AnimatedBuilder(
                      animation: _glowAnimation,
                      builder: (context, child) {
                        return Container(
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            boxShadow: _isConnected
                                ? [
                              BoxShadow(
                                color: moonlight.withValues(alpha: _glowAnimation.value * 0.8),
                                blurRadius: 30 * _glowAnimation.value,
                                spreadRadius: 10 * _glowAnimation.value,
                              ),
                              BoxShadow(
                                color: neonPurple.withValues(alpha: _glowAnimation.value * 0.6),
                                blurRadius: 20 * _glowAnimation.value,
                                spreadRadius: 5 * _glowAnimation.value,
                              ),
                            ]
                                : [],
                          ),
                          child: Icon(
                            Icons.circle,
                            color: _isConnected ? moonlight : Colors.grey.shade700,
                            size: 24,
                          ),
                        );
                      },
                    ),
                    const SizedBox(height: 16),
                    Text(
                      _isConnected ? 'CONNECTED' : 'DISCONNECTED',
                      style: TextStyle(
                        fontSize: 20,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 2,
                        color: _isConnected ? moonlight : Colors.grey.shade600,
                        shadows: _isConnected
                            ? [
                          Shadow(
                            color: moonlight.withValues(alpha: 0.8),
                            blurRadius: 10,
                          ),
                        ]
                            : [],
                      ),
                    ),
                    const SizedBox(height: 40),
                    _isLoading
                        ? CircularProgressIndicator(
                      valueColor: AlwaysStoppedAnimation<Color>(neonCyan),
                    )
                        : _buildNeonButton(),
                  ],
                ),
              ),
            ),
            if (_logs.isNotEmpty || _isLogsExpanded) _buildLogsPanel(),
          ],
        ),
      ),
    );
  }

  Widget _buildNeonButton() {
    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(30),
        gradient: LinearGradient(
          colors: _isConnected
              ? [Colors.red.shade700, Colors.red.shade900]
              : [neonCyan, neonPurple],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        boxShadow: _isConnected
            ? [
          BoxShadow(
            color: Colors.red.withValues(alpha: 0.5),
            blurRadius: 20,
            spreadRadius: 5,
          ),
        ]
            : [
          BoxShadow(
            color: neonCyan.withValues(alpha: 0.5),
            blurRadius: 20,
            spreadRadius: 2,
          ),
          BoxShadow(
            color: neonPurple.withValues(alpha: 0.3),
            blurRadius: 30,
            spreadRadius: 2,
          ),
        ],
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: _toggle,
          borderRadius: BorderRadius.circular(30),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 56, vertical: 18),
            child: Text(
              _isConnected ? 'DISCONNECT' : 'CONNECT',
              style: const TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
                color: Colors.white,
                letterSpacing: 1.5,
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildLogsPanel() {
    return Container(
      decoration: BoxDecoration(
        color: cardBg.withValues(alpha: 0.9),
        border: Border(
          top: BorderSide(
            color: moonlight.withValues(alpha: 0.3),
            width: 1,
          ),
        ),
        boxShadow: [
          BoxShadow(
            color: neonPurple.withValues(alpha: 0.1),
            blurRadius: 10,
            spreadRadius: 0,
          ),
        ],
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
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              child: Row(
                children: [
                  Icon(
                    _isLogsExpanded
                        ? Icons.keyboard_arrow_down
                        : Icons.keyboard_arrow_up,
                    color: moonlight,
                    size: 22,
                  ),
                  const SizedBox(width: 10),
                  const Text(
                    'Логи',
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                      letterSpacing: 0.5,
                    ),
                  ),
                ],
              ),
            ),
          ),
          if (_isLogsExpanded) ...[
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              color: darkBg.withValues(alpha: 0.5),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  _buildNeonIconButton(
                    icon: Icons.save,
                    label: 'Сохранить',
                    onPressed: _logs.isEmpty ? null : _saveLogsToFile,
                  ),
                  const SizedBox(width: 8),
                  _buildNeonIconButton(
                    icon: Icons.copy,
                    label: 'Копировать',
                    onPressed: _logs.isEmpty ? null : _copyLogs,
                  ),
                  const SizedBox(width: 8),
                  _buildNeonIconButton(
                    icon: Icons.delete_outline,
                    label: 'Очистить',
                    onPressed: _logs.isEmpty ? null : _clearLogs,
                  ),
                ],
              ),
            ),
            Container(
              height: 250,
              decoration: BoxDecoration(
                color: Colors.black.withValues(alpha: 0.8),
                border: Border(
                  top: BorderSide(color: moonlight.withValues(alpha: 0.3)),
                ),
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

  Widget _buildNeonIconButton({
    required IconData icon,
    required String label,
    required VoidCallback? onPressed,
  }) {
    final isEnabled = onPressed != null;

    return TextButton.icon(
      onPressed: onPressed,
      icon: Icon(icon, size: 16, color: isEnabled ? moonlight : Colors.grey),
      label: Text(
        label,
        style: TextStyle(
          fontSize: 12,
          color: isEnabled ? Colors.white : Colors.grey,
        ),
      ),
      style: TextButton.styleFrom(
        backgroundColor: isEnabled ? neonPurple.withValues(alpha: 0.2) : Colors.grey.withValues(alpha: 0.1),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(8),
          side: BorderSide(
            color: isEnabled ? neonPurple.withValues(alpha: 0.5) : Colors.transparent,
          ),
        ),
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
      return neonCyan;
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